package dev.marketlab.coordinator

import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.market.Candle
import dev.marketlab.data.hyperliquid.StoredHyperliquidSnapshotReader
import dev.marketlab.engine.Estimator
import dev.marketlab.engine.FeatureVector
import dev.marketlab.engine.HistoricalMeanEstimator
import dev.marketlab.engine.LabeledObservation
import dev.marketlab.engine.PersistenceEstimator
import dev.marketlab.engine.SplitPlanner
import dev.marketlab.engine.WalkForwardConfig
import dev.marketlab.engine.WalkForwardExperiment
import dev.marketlab.engine.WalkForwardExperimentResult
import dev.marketlab.engine.WalkForwardFold
import dev.marketlab.engine.WalkForwardSplitPlanner
import dev.marketlab.engine.ZeroReturnEstimator
import dev.marketlab.persistence.ExperimentRunRow
import dev.marketlab.persistence.JobLease
import dev.marketlab.persistence.JobRow
import dev.marketlab.persistence.PromotionStatus
import dev.marketlab.persistence.RunStatus
import dev.marketlab.persistence.SnapshotStatus
import java.util.UUID
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ControlExperimentConfig(
    val minimumTrainingRows: Int = 180,
    val testRows: Int = 30,
    val stepRows: Int = 30,
    val foldCount: Int = 5,
    val purgeMillis: Long = DAY_MILLIS,
    val embargoMillis: Long = DAY_MILLIS,
    val sealedHoldoutRows: Int = 60,
    val hacLag: Int = 5,
) {
    init {
        require(minimumTrainingRows > 1)
        require(testRows > 0 && stepRows > 0)
        require(foldCount >= 2)
        require(purgeMillis >= 0 && embargoMillis >= 0)
        require(sealedHoldoutRows >= 0)
        require(hacLag >= 0)
    }
}

/**
 * Executes the three registered return controls against a frozen, hash-checked
 * Hyperliquid candle snapshot. It intentionally has no random-data or synthetic
 * fallback: inadequate or invalid real observations produce a terminal result.
 */
internal class ControlExperimentJobHandler(
    private val persistence: ExperimentPersistence,
    private val snapshotReader: StoredHyperliquidSnapshotReader,
    private val artifacts: ExperimentArtifactStore,
    private val sourceRevision: String,
    private val config: ControlExperimentConfig = ControlExperimentConfig(),
) : ExperimentJobHandler {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    init {
        require(SHA256.matches(sourceRevision)) {
            "control experiment source revision must be a lowercase SHA-256 digest"
        }
    }

    override suspend fun execute(lease: JobLease): JobExecutionResult =
        withContext(Dispatchers.IO) {
            val job = lease.job
            validateJobEnvelope(job)
            val runId = parseUuid(requirePayloadString(job, "runId"), "runId")
            require(job.resourceId == runId.toString()) {
                "Experiment run identity does not match the job resource"
            }
            val initial =
                persistence.getRun(runId)
                    ?: throw PermanentJobException(
                        "MISSING_EXPERIMENT_RUN",
                        "Experiment job references a missing run",
                    )
            validatePayloadIdentity(job, initial)
            val fence = ExperimentLeaseFence.from(lease, runId)
            when (initial.status) {
                RunStatus.SUCCEEDED -> return@withContext recovered(initial)
                RunStatus.QUEUED,
                RunStatus.RUNNING,
                -> persistence.markRunning(fence)
                RunStatus.FAILED,
                RunStatus.CANCELLED,
                RunStatus.REJECTED,
                RunStatus.INCONCLUSIVE,
                -> {
                    return@withContext JobExecutionResult.Failed(
                        code = "EXPERIMENT_ALREADY_TERMINAL",
                        message = "Experiment run is already ${initial.status}",
                        retryable = false,
                    )
                }
            }

            val specification =
                CONTROL_SPECS[initial.theoryId]
                    ?: return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.REJECTED,
                        code = "UNSUPPORTED_EXPERIMENT_THEORY",
                        message = "Coordinator supports only registered return-control theories",
                    )
            if (initial.theoryVersion != CONTROL_THEORY_VERSION) {
                return@withContext terminalFailure(
                    fence,
                    initial,
                    status = RunStatus.REJECTED,
                    code = "UNSUPPORTED_THEORY_VERSION",
                    message = "Coordinator does not implement this control-theory version",
                )
            }

            val prepared =
                try {
                    prepare(initial)
                } catch (exception: InsufficientRealDataException) {
                    return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.INCONCLUSIVE,
                        code = "INSUFFICIENT_REAL_DATA",
                        message = exception.message,
                    )
                } catch (exception: SerializationException) {
                    return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.REJECTED,
                        code = "INVALID_SNAPSHOT_CONTRACT",
                        message = exception.message ?: "Snapshot contract could not be decoded",
                    )
                } catch (exception: IllegalArgumentException) {
                    return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.REJECTED,
                        code = "INVALID_REAL_DATA_SNAPSHOT",
                        message = exception.message ?: "Real-data snapshot validation failed",
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.FAILED,
                        code = "IMMUTABLE_DATA_REPLAY_FAILED",
                        message = exception.message ?: "Immutable data replay failed",
                    )
                }

            val result =
                try {
                    runExperiment(prepared.rows, specification)
                } catch (exception: IllegalArgumentException) {
                    return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.INCONCLUSIVE,
                        code = "INSUFFICIENT_REAL_DATA",
                        message = exception.message ?: "Walk-forward validation could not be completed",
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        initial,
                        status = RunStatus.FAILED,
                        code = "EXPERIMENT_ENGINE_FAILED",
                        message = exception.message ?: "Experiment engine failed",
                    )
                }

            val metrics = metricsJson(result, prepared)
            val predictions = predictionsJson(initial, prepared, specification, result)
            val report = reportJson(initial, prepared, specification, metrics)

            /*
             * I/O errors remain retryable and leave the run RUNNING. The
             * content-addressed writes and database upserts are idempotent.
             */
            val predictionArtifact = artifacts.putJson(predictions)
            val reportArtifact = artifacts.putJson(report)
            val predictionArtifactId = UUID.randomUUID()
            val reportArtifactId = UUID.randomUUID()
            val manifest =
                buildJsonObject {
                    put("schemaVersion", RUN_MANIFEST_SCHEMA)
                    put("engine", ENGINE_NAME)
                    put("sourceRevision", sourceRevision)
                    put("realDataOnly", true)
                    put("source", HYPERLIQUID_SOURCE)
                    put("snapshotId", initial.snapshotId.toString())
                    put("contractSnapshotId", prepared.snapshot.id.value)
                    put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
                    put("jobId", fence.jobId.toString())
                    put("attemptId", fence.attemptId.toString())
                    put("leaseOwner", fence.workerId)
                    put("leaseToken", fence.leaseToken.toString())
                    put(
                        "artifacts",
                        buildJsonArray {
                            add(artifactReference(predictionArtifactId, predictionArtifact))
                            add(artifactReference(reportArtifactId, reportArtifact))
                        },
                    )
                    put("promotionStatus", PromotionStatus.BLOCKED.name)
                    put("promotionReason", CONTROL_PROMOTION_REASON)
                }
            persistence.finishRun(
                fence = fence,
                status = RunStatus.SUCCEEDED,
                manifest = manifest,
                metrics = metrics,
                failure = null,
                promotionStatus = PromotionStatus.BLOCKED,
                artifacts =
                    listOf(
                        ArtifactWrite(
                            id = predictionArtifactId,
                            kind = PREDICTIONS_ARTIFACT_KIND,
                            artifact = predictionArtifact,
                            manifest =
                                artifactManifest(
                                    initial,
                                    prepared,
                                    fence,
                                    PREDICTIONS_ARTIFACT_KIND,
                                    predictionArtifact,
                                ),
                        ),
                        ArtifactWrite(
                            id = reportArtifactId,
                            kind = REPORT_ARTIFACT_KIND,
                            artifact = reportArtifact,
                            manifest =
                                artifactManifest(
                                    initial,
                                    prepared,
                                    fence,
                                    REPORT_ARTIFACT_KIND,
                                    reportArtifact,
                                ),
                        ),
                    ),
            )
            JobExecutionResult.Succeeded(
                buildJsonObject {
                    put("runId", initial.id.toString())
                    put("status", RunStatus.SUCCEEDED.name)
                    put("promotionStatus", PromotionStatus.BLOCKED.name)
                    put("reportHash", reportArtifact.contentHash)
                    put("predictionHash", predictionArtifact.contentHash)
                    put("realDataOnly", true)
                    put("recovered", false)
                },
            )
        }

    private fun prepare(run: ExperimentRunRow): PreparedControlData {
        val snapshotRow =
            persistence.getSnapshot(run.snapshotId)
                ?: throw IllegalArgumentException("Experiment snapshot does not exist")
        require(snapshotRow.status == SnapshotStatus.READY) {
            "Experiment snapshot is not READY"
        }
        require(snapshotRow.fatalFindingCount == 0) {
            "Experiment snapshot has fatal data-quality findings"
        }
        val contractElement =
            snapshotRow.metadata["contractSnapshot"]
                ?: throw IllegalArgumentException("Snapshot metadata lacks its immutable contract")
        val snapshot = json.decodeFromJsonElement(DataSnapshot.serializer(), contractElement)
        require(snapshot.manifestHash.hex == snapshotRow.manifestHash) {
            "Persisted and contract snapshot hashes differ"
        }
        require(snapshot.objects.size == snapshotRow.objectCount) {
            "Persisted and contract snapshot object counts differ"
        }
        require(
            snapshotRow.metadata["contractSnapshotId"]?.jsonPrimitive?.content ==
                snapshot.id.value,
        ) {
            "Persisted and contract snapshot identifiers differ"
        }
        require(
            snapshot.requirements.map { it.observation }.toSet() == setOf(ObservationKind.CANDLE),
        ) {
            "Return controls require an immutable candle-only snapshot"
        }
        val candles = snapshotReader.events(snapshot).filterIsInstance<Candle>()
        require(candles.isNotEmpty()) { "Snapshot replay produced no candles" }
        require(candles.all(Candle::closed)) { "Return controls require completed candles" }
        val instruments = candles.map { it.header.instrument }.distinct()
        require(instruments.size == 1) {
            "This control runner requires exactly one snapshot instrument"
        }
        val daily = dailyCloses(candles)
        val rows = labeledDailyReturns(daily)
        if (rows.size <= config.sealedHoldoutRows) {
            throw InsufficientRealDataException(
                "Snapshot has no validation rows after reserving its sealed holdout",
            )
        }
        return PreparedControlData(
            snapshot = snapshot,
            instrument = instruments.single().value,
            sourceCandleCount = candles.size,
            candleIntervalMillis = candles.first().intervalMillis,
            dailyCloseCount = daily.size,
            rows = rows.dropLast(config.sealedHoldoutRows),
            sealedHoldoutRows = config.sealedHoldoutRows,
        )
    }

    private fun dailyCloses(candles: List<Candle>): List<Candle> {
        val ordered =
            candles.sortedWith(
                compareBy<Candle>(
                    { it.header.exchangeTime },
                    { it.header.id.value },
                ),
            )
        val intervals = ordered.map(Candle::intervalMillis).distinct()
        require(intervals.size == 1) { "Candle interval changes inside the snapshot" }
        val interval = intervals.single()
        require(interval in 1..DAY_MILLIS && DAY_MILLIS % interval == 0L) {
            "Candle interval must divide one UTC day exactly"
        }
        ordered.zipWithNext().forEach { (previous, next) ->
            require(
                next.header.exchangeTime.epochMillis - previous.header.exchangeTime.epochMillis ==
                    interval,
            ) {
                "Candle snapshot has a gap or duplicate timestamp"
            }
            require(next.header.availableAt > previous.header.availableAt) {
                "Candle availability is not strictly chronological"
            }
        }
        val barsPerDay = (DAY_MILLIS / interval).toInt()
        return ordered.filterIndexed { index, _ -> index % barsPerDay == 0 }
    }

    private fun labeledDailyReturns(daily: List<Candle>): List<LabeledObservation> {
        if (daily.size < 3) {
            throw InsufficientRealDataException(
                "At least three observed daily closes are required",
            )
        }
        daily.zipWithNext().forEach { (previous, next) ->
            require(
                next.header.exchangeTime.epochMillis - previous.header.exchangeTime.epochMillis ==
                    DAY_MILLIS,
            ) {
                "Daily close series is not contiguous"
            }
        }
        val returns =
            daily.zipWithNext { previous, next ->
                val value = ln(next.close.toBigDecimal().toDouble() / previous.close.toBigDecimal().toDouble())
                require(value.isFinite()) { "Observed candle return is not finite" }
                value
            }
        return (1 until returns.size).map { index ->
            val decision = daily[index].header.availableAt
            val labelTo = daily[index + 1].header.availableAt
            val rowId = "${daily[index].header.instrument.value}:${daily[index].header.exchangeTime.epochMillis}"
            LabeledObservation(
                rowId = rowId,
                decisionTime = decision,
                labelFrom = decision,
                labelTo = labelTo,
                features =
                    FeatureVector(
                        rowId = rowId,
                        values = mapOf(LAST_RETURN_FEATURE to returns[index - 1]),
                    ),
                label = returns[index],
            )
        }
    }

    private fun runExperiment(
        rows: List<LabeledObservation>,
        specification: ControlSpecification,
    ): WalkForwardExperimentResult {
        val delegate =
            WalkForwardSplitPlanner(
                WalkForwardConfig(
                    minimumTrainingRows = config.minimumTrainingRows,
                    testRows = config.testRows,
                    stepRows = config.stepRows,
                    purgeMillis = config.purgeMillis,
                    embargoMillis = config.embargoMillis,
                ),
            )
        val planner = FixedFoldCountPlanner(delegate, config.foldCount)
        return WalkForwardExperiment(
            splitPlanner = planner,
            candidate = specification.candidate(),
            baseline = specification.baseline(),
            hacLag = config.hacLag,
        ).run(rows)
    }

    private fun predictionsJson(
        run: ExperimentRunRow,
        prepared: PreparedControlData,
        specification: ControlSpecification,
        result: WalkForwardExperimentResult,
    ): JsonObject =
        buildJsonObject {
            put("schemaVersion", PREDICTIONS_SCHEMA)
            put("runId", run.id.toString())
            put("theoryId", run.theoryId)
            put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
            put("source", HYPERLIQUID_SOURCE)
            put("sourceRevision", sourceRevision)
            put("instrument", prepared.instrument)
            put("candidateEstimator", specification.candidateName)
            put("baselineEstimator", specification.baselineName)
            put("realDataOnly", true)
            put(
                "observations",
                buildJsonArray {
                    result.observations.forEach { observation ->
                        add(
                            buildJsonObject {
                                put("fold", observation.fold)
                                put("rowId", observation.rowId)
                                put("decisionTimeEpochMillis", observation.decisionTime.epochMillis)
                                put("actualLogReturn", observation.actual)
                                put("candidateForecast", observation.candidate)
                                put("baselineForecast", observation.baseline)
                            },
                        )
                    }
                },
            )
        }

    private fun reportJson(
        run: ExperimentRunRow,
        prepared: PreparedControlData,
        specification: ControlSpecification,
        metrics: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("schemaVersion", REPORT_SCHEMA)
            put("runId", run.id.toString())
            put("theoryId", run.theoryId)
            put("theoryVersion", run.theoryVersion)
            put("theoryPlanHash", run.theoryPlanHash)
            put("sourceRevision", sourceRevision)
            put("snapshotId", run.snapshotId.toString())
            put("contractSnapshotId", prepared.snapshot.id.value)
            put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
            put("source", HYPERLIQUID_SOURCE)
            put("productionSource", true)
            put("realDataOnly", true)
            put("instrument", prepared.instrument)
            put("sourceCandleCount", prepared.sourceCandleCount)
            put("sourceCandleIntervalMillis", prepared.candleIntervalMillis)
            put("dailyCloseCount", prepared.dailyCloseCount)
            put("validationRowCount", prepared.rows.size)
            put("sealedHoldoutRows", prepared.sealedHoldoutRows)
            put("sealedHoldoutEvaluated", false)
            put("candidateEstimator", specification.candidateName)
            put("baselineEstimator", specification.baselineName)
            put(
                "validation",
                buildJsonObject {
                    put("scheme", "EXPANDING")
                    put("minimumTrainingRows", config.minimumTrainingRows)
                    put("testRows", config.testRows)
                    put("stepRows", config.stepRows)
                    put("foldCount", config.foldCount)
                    put("purgeMillis", config.purgeMillis)
                    put("embargoMillis", config.embargoMillis)
                    put("hacLag", config.hacLag)
                },
            )
            put("metrics", metrics)
            put("promotionStatus", PromotionStatus.BLOCKED.name)
            put("promotionReason", CONTROL_PROMOTION_REASON)
        }

    private fun metricsJson(
        result: WalkForwardExperimentResult,
        prepared: PreparedControlData,
    ): JsonObject =
        buildJsonObject {
            put("schemaVersion", METRICS_SCHEMA)
            put("instrument", prepared.instrument)
            put("outOfSampleCount", result.observations.size)
            put("foldCount", result.observations.map { it.fold }.distinct().size)
            put("candidate", forecastMetricJson(result.candidateMetrics))
            put("baseline", forecastMetricJson(result.baselineMetrics))
            put(
                "comparison",
                buildJsonObject {
                    put(
                        "meanSquaredErrorImprovement",
                        result.comparison.meanSquaredErrorImprovement,
                    )
                    put("hacStandardError", result.comparison.hacStandardError)
                    put("zStatistic", finiteOrNull(result.comparison.zStatistic))
                    put("twoSidedPValue", result.comparison.twoSidedPValue)
                    put("hacLag", result.comparison.hacLag)
                },
            )
        }

    private fun forecastMetricJson(metrics: dev.marketlab.engine.ForecastMetricSet): JsonObject =
        buildJsonObject {
            put("count", metrics.count)
            put("meanAbsoluteError", metrics.meanAbsoluteError)
            put("rootMeanSquaredError", metrics.rootMeanSquaredError)
            put("directionalAccuracy", metrics.directionalAccuracy)
        }

    private fun artifactManifest(
        run: ExperimentRunRow,
        prepared: PreparedControlData,
        fence: ExperimentLeaseFence,
        kind: String,
        artifact: StoredExperimentArtifact,
    ): JsonObject =
        buildJsonObject {
            put("schemaVersion", ARTIFACT_MANIFEST_SCHEMA)
            put("runId", run.id.toString())
            put("kind", kind)
            put("contentHash", artifact.contentHash)
            put("byteCount", artifact.byteCount)
            put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
            put("jobId", fence.jobId.toString())
            put("attemptId", fence.attemptId.toString())
            put("leaseOwner", fence.workerId)
            put("leaseToken", fence.leaseToken.toString())
            put("producer", ENGINE_NAME)
            put("sourceRevision", sourceRevision)
            put("realDataOnly", true)
        }

    private fun artifactReference(
        id: UUID,
        artifact: StoredExperimentArtifact,
    ): JsonObject =
        buildJsonObject {
            put("id", id.toString())
            put("contentHash", artifact.contentHash)
            put("uri", artifact.uri)
            put("byteCount", artifact.byteCount)
            put("mediaType", JSON_MEDIA_TYPE)
        }

    private fun terminalFailure(
        fence: ExperimentLeaseFence,
        run: ExperimentRunRow,
        status: RunStatus,
        code: String,
        message: String,
    ): JobExecutionResult.Failed {
        val safeMessage = sanitize(message)
        val failure =
            buildJsonObject {
                put("code", code)
                put("message", safeMessage)
                put("retryable", false)
                put("realDataOnly", true)
            }
        persistence.finishRun(
            fence = fence,
            status = status,
            manifest =
                buildJsonObject {
                    put("schemaVersion", RUN_MANIFEST_SCHEMA)
                    put("realDataOnly", true)
                    put("sourceRevision", sourceRevision)
                    put("jobId", fence.jobId.toString())
                    put("attemptId", fence.attemptId.toString())
                    put("leaseOwner", fence.workerId)
                    put("leaseToken", fence.leaseToken.toString())
                    put("promotionStatus", PromotionStatus.BLOCKED.name)
                    put("promotionReason", CONTROL_PROMOTION_REASON)
                },
            metrics = null,
            failure = failure,
            promotionStatus = PromotionStatus.BLOCKED,
        )
        return JobExecutionResult.Failed(
            code = code,
            message = safeMessage,
            retryable = false,
        )
    }

    private fun recovered(run: ExperimentRunRow): JobExecutionResult.Succeeded {
        require(run.theoryId in CONTROL_SPECS) {
            "Only a control run can be recovered by the control handler"
        }
        require(run.promotionStatus == PromotionStatus.BLOCKED) {
            "A completed control run must have promotion blocked"
        }
        return JobExecutionResult.Succeeded(
            buildJsonObject {
                put("runId", run.id.toString())
                put("status", run.status.name)
                put("promotionStatus", run.promotionStatus.name)
                put("realDataOnly", true)
                put("recovered", true)
            },
        )
    }

    private fun validateJobEnvelope(job: JobRow) {
        if (job.kind != EXPERIMENT_JOB_KIND || job.resourceType != RUN_RESOURCE_TYPE) {
            throw PermanentJobException(
                "INVALID_JOB_RESOURCE",
                "Experiment job has an invalid kind or resource type",
            )
        }
    }

    private fun validatePayloadIdentity(
        job: JobRow,
        run: ExperimentRunRow,
    ) {
        requirePayloadEquals(job, "theoryId", run.theoryId)
        requirePayloadEquals(job, "theoryVersion", run.theoryVersion)
        requirePayloadEquals(job, "theoryPlanHash", run.theoryPlanHash)
        requirePayloadEquals(job, "snapshotId", run.snapshotId.toString())
        val parameters =
            job.payload["parameters"]?.jsonObject
                ?: throw PermanentJobException(
                    "INVALID_EXPERIMENT_PAYLOAD",
                    "Experiment payload is missing parameters",
                )
        if (parameters != run.parameters) {
            throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment payload parameters do not match the persisted run",
            )
        }
    }

    private fun requirePayloadEquals(
        job: JobRow,
        name: String,
        expected: String,
    ) {
        if (requirePayloadString(job, name) != expected) {
            throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment payload $name does not match the persisted run",
            )
        }
    }

    private fun requirePayloadString(
        job: JobRow,
        name: String,
    ): String =
        job.payload[name]?.jsonPrimitive?.content
            ?: throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment payload is missing $name",
            )

    private fun parseUuid(
        value: String,
        field: String,
    ): UUID =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment $field must be a UUID",
            )
        }

    private fun finiteOrNull(value: Double): JsonElement =
        if (value.isFinite()) JsonPrimitive(value) else JsonNull

    private fun sanitize(message: String): String =
        message
            .replace(CONTROL_CHARACTERS, " ")
            .trim()
            .ifBlank { "Experiment could not be completed" }
            .take(MAX_MESSAGE_CHARS)

    private data class PreparedControlData(
        val snapshot: DataSnapshot,
        val instrument: String,
        val sourceCandleCount: Int,
        val candleIntervalMillis: Long,
        val dailyCloseCount: Int,
        val rows: List<LabeledObservation>,
        val sealedHoldoutRows: Int,
    )

    private data class ControlSpecification(
        val candidateName: String,
        val baselineName: String,
        val candidate: () -> Estimator,
        val baseline: () -> Estimator,
    )

    private class FixedFoldCountPlanner(
        private val delegate: SplitPlanner,
        private val requiredFoldCount: Int,
    ) : SplitPlanner {
        override fun plan(rows: List<LabeledObservation>): List<WalkForwardFold> {
            val available = delegate.plan(rows)
            if (available.size < requiredFoldCount) {
                throw InsufficientRealDataException(
                    "Real snapshot yields ${available.size} folds; $requiredFoldCount are required",
                )
            }
            return available.takeLast(requiredFoldCount)
        }
    }

    private class InsufficientRealDataException(
        override val message: String,
    ) : IllegalArgumentException(message)

    private companion object {
        val CONTROL_SPECS =
            mapOf(
                "control-random-walk" to
                    ControlSpecification(
                        candidateName = "ZERO_RETURN_RANDOM_WALK",
                        baselineName = "EXPANDING_HISTORICAL_MEAN",
                        candidate = ::ZeroReturnEstimator,
                        baseline = ::HistoricalMeanEstimator,
                    ),
                "control-historical-mean" to
                    ControlSpecification(
                        candidateName = "EXPANDING_HISTORICAL_MEAN",
                        baselineName = "ZERO_RETURN_RANDOM_WALK",
                        candidate = ::HistoricalMeanEstimator,
                        baseline = ::ZeroReturnEstimator,
                    ),
                "control-persistence" to
                    ControlSpecification(
                        candidateName = "LAST_COMPLETED_RETURN",
                        baselineName = "EXPANDING_HISTORICAL_MEAN",
                        candidate = { PersistenceEstimator(LAST_RETURN_FEATURE) },
                        baseline = ::HistoricalMeanEstimator,
                    ),
            )
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val CONTROL_THEORY_VERSION = "1.0.0"
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val LAST_RETURN_FEATURE = "last_return"
        const val EXPERIMENT_JOB_KIND = "EXPERIMENT_RUN"
        const val RUN_RESOURCE_TYPE = "run"
        const val PREDICTIONS_ARTIFACT_KIND = "PREDICTIONS"
        const val REPORT_ARTIFACT_KIND = "REPORT"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ENGINE_NAME = "marketlab-kotlin-control-engine-v1"
        const val PREDICTIONS_SCHEMA = "marketlab.control-predictions.v1"
        const val REPORT_SCHEMA = "marketlab.control-report.v1"
        const val METRICS_SCHEMA = "marketlab.return-forecast-metrics.v1"
        const val ARTIFACT_MANIFEST_SCHEMA = "marketlab.artifact-manifest.v1"
        const val RUN_MANIFEST_SCHEMA = "marketlab.control-run-manifest.v1"
        const val CONTROL_PROMOTION_REASON =
            "Control theories are reference forecasts and are never promotion-eligible"
        const val MAX_MESSAGE_CHARS = 1_024
    }
}

private const val DAY_MILLIS = 86_400_000L
