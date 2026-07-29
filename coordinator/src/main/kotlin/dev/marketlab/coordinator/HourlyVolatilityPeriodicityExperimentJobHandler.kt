package dev.marketlab.coordinator

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.data.hyperliquid.StoredHyperliquidSnapshotReader
import dev.marketlab.engine.FeatureVector
import dev.marketlab.engine.HistoricalMeanEstimator
import dev.marketlab.engine.LabeledObservation
import dev.marketlab.engine.LinearEstimator
import dev.marketlab.engine.LinearFittedEstimator
import dev.marketlab.engine.LogTargetEstimator
import dev.marketlab.engine.NamedVarianceControl
import dev.marketlab.engine.SplitPlanner
import dev.marketlab.engine.VarianceControlEvaluation
import dev.marketlab.engine.VarianceForecastMetricSet
import dev.marketlab.engine.VarianceHoldoutExperiment
import dev.marketlab.engine.VarianceHoldoutExperimentResult
import dev.marketlab.engine.VarianceWalkForwardExperiment
import dev.marketlab.engine.VarianceWalkForwardExperimentResult
import dev.marketlab.engine.WalkForwardConfig
import dev.marketlab.engine.WalkForwardFold
import dev.marketlab.engine.WalkForwardSplitPlanner
import dev.marketlab.persistence.ExperimentRunRow
import dev.marketlab.persistence.JobLease
import dev.marketlab.persistence.JobRow
import dev.marketlab.persistence.PromotionStatus
import dev.marketlab.persistence.RunStatus
import dev.marketlab.persistence.SnapshotStatus
import java.time.Instant
import java.time.ZoneOffset
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID =
    "hyperliquid-eth-hourly-volatility-periodicity"

private const val PERIODICITY_HOUR_MILLIS = 3_600_000L

internal data class HourlyVolatilityPeriodicityExperimentConfig(
    val expectedSnapshotId: UUID = UUID.fromString("af958a14-31b2-3841-9a10-9a6762a1208b"),
    val expectedSnapshotManifestHash: String =
        "7c0d111ac236c58ceeaed3f2dbb9a4d8c99fe54cbdc10b7d1042b57d341a1fa8",
    val expectedRawObjectHash: String =
        "550d601c0ae2aa2f12e3d823fd9f1cdd75972646654cf705890383a037e82707",
    val sourceRows: Int = 4_800,
    val trailingVarianceRows: Int = 24,
    val expectedLabeledRows: Int = 4_775,
    val sealedHoldoutRows: Int = 720,
    val expectedValidationRows: Int = 4_054,
    val expectedBoundaryPurgedRows: Int = 1,
    val minimumTrainingRows: Int = 2_160,
    val testRows: Int = 168,
    val stepRows: Int = 168,
    val foldCount: Int = 11,
    val purgeMillis: Long = PERIODICITY_HOUR_MILLIS,
    val embargoMillis: Long = PERIODICITY_HOUR_MILLIS,
    val hacLag: Int = 24,
    val predictiveAlpha: Double = 0.05,
    val varianceFloor: Double = 1.0e-12,
    val expectedFirstAvailableAtEpochMillis: Long = 1_767_931_200_000L,
    val expectedLastAvailableAtEpochMillis: Long = 1_785_207_600_000L,
    val firstHoldoutDecisionEpochMillis: Long = 1_782_615_600_000L,
    val lastHoldoutDecisionEpochMillis: Long = 1_785_204_000_000L,
    val holdoutLabelToEpochMillis: Long = 1_785_207_600_000L,
    val expectedRequestBody: String =
        """{"req":{"coin":"ETH","endTime":1785207599999,"interval":"1h","startTime":1767927600000},"type":"candleSnapshot"}""",
) {
    val minimumHistoryMillis: Long = (sourceRows - 1L) * PERIODICITY_HOUR_MILLIS

    init {
        require(PERIODICITY_SHA256.matches(expectedSnapshotManifestHash))
        require(PERIODICITY_SHA256.matches(expectedRawObjectHash))
        require(sourceRows > trailingVarianceRows + sealedHoldoutRows)
        require(trailingVarianceRows == 24)
        require(expectedLabeledRows == sourceRows - trailingVarianceRows - 1)
        require(expectedLabeledRows > sealedHoldoutRows)
        require(expectedValidationRows > minimumTrainingRows + testRows)
        require(expectedBoundaryPurgedRows >= 0)
        require(minimumTrainingRows > PERIODICITY_CANDIDATE_FEATURES.size + 1)
        require(testRows > 0 && stepRows > 0 && foldCount > 0)
        require(purgeMillis >= 0 && embargoMillis >= 0 && hacLag >= 0)
        require(predictiveAlpha in 0.0..1.0 && predictiveAlpha != 0.0 && predictiveAlpha != 1.0)
        require(varianceFloor.isFinite() && varianceFloor > 0.0)
        require(
            expectedLastAvailableAtEpochMillis - expectedFirstAvailableAtEpochMillis ==
                minimumHistoryMillis,
        )
        require(
            lastHoldoutDecisionEpochMillis - firstHoldoutDecisionEpochMillis ==
                (sealedHoldoutRows - 1L) * PERIODICITY_HOUR_MILLIS,
        )
        require(holdoutLabelToEpochMillis == lastHoldoutDecisionEpochMillis + PERIODICITY_HOUR_MILLIS)
        require(holdoutLabelToEpochMillis == expectedLastAvailableAtEpochMillis)
        require(expectedRequestBody.isNotBlank())
    }
}

/**
 * Executes the one frozen ETH hourly-volatility periodicity screen.
 *
 * There is deliberately no alternate asset, interval, clock encoding,
 * snapshot, parameter grid, or generated-data execution path.
 */
internal class HourlyVolatilityPeriodicityExperimentJobHandler(
    private val persistence: ExperimentPersistence,
    private val snapshotReader: StoredHyperliquidSnapshotReader,
    private val artifacts: ExperimentArtifactStore,
    private val sourceRevision: String,
    private val expectedPlanHash: String,
    private val config: HourlyVolatilityPeriodicityExperimentConfig =
        HourlyVolatilityPeriodicityExperimentConfig(),
) : ExperimentJobHandler {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    init {
        require(PERIODICITY_SHA256.matches(sourceRevision))
        require(PERIODICITY_SHA256.matches(expectedPlanHash))
    }

    override suspend fun execute(lease: JobLease): JobExecutionResult =
        withContext(Dispatchers.IO) {
            val job = lease.job
            validateJobEnvelope(job)
            val runId = parseUuid(requirePayloadString(job, "runId"), "runId")
            require(job.resourceId == runId.toString())
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
                else ->
                    return@withContext JobExecutionResult.Failed(
                        "EXPERIMENT_ALREADY_TERMINAL",
                        "Experiment run is already ${initial.status}",
                        false,
                    )
            }
            when {
                initial.theoryId != HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_EXPERIMENT_THEORY",
                        "Periodicity executor received a different theory",
                    )
                initial.theoryVersion != THEORY_VERSION ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_VERSION",
                        "Periodicity executor implements only $THEORY_VERSION",
                    )
                initial.theoryPlanHash != expectedPlanHash ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "THEORY_PLAN_HASH_MISMATCH",
                        "Persisted periodicity plan does not match this executor",
                    )
                initial.snapshotId != config.expectedSnapshotId ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNREGISTERED_CONFIRMATION_SNAPSHOT",
                        "Run does not use the preregistered ETH confirmation snapshot",
                    )
                initial.parameters.isNotEmpty() ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_PARAMETERS",
                        "The frozen periodicity design has no runtime parameters",
                    )
            }

            val prepared =
                try {
                    prepare(initial)
                } catch (exception: InsufficientPeriodicityDataException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.INCONCLUSIVE,
                        "INSUFFICIENT_REAL_DATA",
                        exception.message,
                    )
                } catch (exception: SerializationException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "INVALID_SNAPSHOT_CONTRACT",
                        exception.message ?: "Snapshot contract could not be decoded",
                    )
                } catch (exception: IllegalArgumentException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "INVALID_REAL_DATA_SNAPSHOT",
                        exception.message ?: "Real-data snapshot validation failed",
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.FAILED,
                        "IMMUTABLE_DATA_REPLAY_FAILED",
                        exception.message ?: "Immutable data replay failed",
                    )
                }
            val evaluated =
                try {
                    evaluate(prepared.validationRows, prepared.sealedHoldout)
                } catch (exception: IllegalArgumentException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.INCONCLUSIVE,
                        "EXPERIMENT_ESTIMATION_INVALID",
                        exception.message ?: "Periodicity estimation could not be completed",
                        initial.parameters,
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.FAILED,
                        "EXPERIMENT_ENGINE_FAILED",
                        exception.message ?: "Periodicity engine failed",
                        initial.parameters,
                    )
                }

            val metrics = metricsJson(evaluated, prepared)
            val predictions = predictionsJson(initial, prepared, evaluated)
            val report = reportJson(initial, prepared, evaluated, metrics)
            val predictionArtifact = artifacts.putJson(predictions)
            val reportArtifact = artifacts.putJson(report)
            val predictionArtifactId = UUID.randomUUID()
            val reportArtifactId = UUID.randomUUID()
            val manifest =
                buildJsonObject {
                    put("schemaVersion", RUN_MANIFEST_SCHEMA)
                    put("engine", ENGINE_NAME)
                    put("sourceRevision", sourceRevision)
                    put("source", HYPERLIQUID_SOURCE)
                    put("productionSource", true)
                    put("realDataOnly", true)
                    put("snapshotId", initial.snapshotId.toString())
                    put("contractSnapshotId", prepared.snapshot.id.value)
                    put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
                    put("theoryPlanHash", expectedPlanHash)
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
                    put("promotionReasons", promotionReasonsJson())
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
                            predictionArtifactId,
                            PREDICTIONS_ARTIFACT_KIND,
                            predictionArtifact,
                            artifactManifest(
                                initial,
                                prepared,
                                fence,
                                PREDICTIONS_ARTIFACT_KIND,
                                predictionArtifact,
                            ),
                        ),
                        ArtifactWrite(
                            reportArtifactId,
                            REPORT_ARTIFACT_KIND,
                            reportArtifact,
                            artifactManifest(
                                initial,
                                prepared,
                                fence,
                                REPORT_ARTIFACT_KIND,
                                reportArtifact,
                            ),
                        ),
                    ),
                trials =
                    listOf(
                        TrialWrite(
                            0,
                            initial.parameters,
                            TrialStatus.SUCCEEDED,
                            metrics,
                            null,
                        ),
                    ),
            )
            JobExecutionResult.Succeeded(
                buildJsonObject {
                    put("runId", initial.id.toString())
                    put("status", RunStatus.SUCCEEDED.name)
                    put("promotionStatus", PromotionStatus.BLOCKED.name)
                    put("evidenceStatus", evaluated.evidenceStatus)
                    put("reportHash", reportArtifact.contentHash)
                    put("predictionHash", predictionArtifact.contentHash)
                    put("realDataOnly", true)
                    put("recovered", false)
                },
            )
        }

    private fun prepare(run: ExperimentRunRow): PreparedPeriodicityData {
        val row =
            persistence.getSnapshot(run.snapshotId)
                ?: throw IllegalArgumentException("Experiment snapshot does not exist")
        require(row.status == SnapshotStatus.READY)
        require(row.fatalFindingCount == 0)
        require(row.manifestHash == config.expectedSnapshotManifestHash)
        val element =
            row.metadata["contractSnapshot"]
                ?: throw IllegalArgumentException("Snapshot metadata lacks immutable contract")
        val snapshot = json.decodeFromJsonElement(DataSnapshot.serializer(), element)
        require(snapshot.manifestHash.hex == row.manifestHash)
        require(snapshot.objects.size == row.objectCount && snapshot.objects.size == 1)
        require(row.metadata["contractSnapshotId"]?.jsonPrimitive?.content == snapshot.id.value)
        require(snapshot.requirements.size == 1)
        val requirement = snapshot.requirements.single()
        require(requirement.observation == ObservationKind.CANDLE)
        require(requirement.sourcePreference == listOf(DataSourceId(HYPERLIQUID_SOURCE)))
        require(requirement.instrumentKinds == listOf(InstrumentKind.PERPETUAL))
        require((requirement.sampling as? Sampling.FixedDuration)?.millis == PERIODICITY_HOUR_MILLIS)
        require("close" in requirement.requiredFields)
        require(requirement.minimumHistoryMillis >= config.minimumHistoryMillis)
        val raw = snapshot.objects.single()
        require(raw.contentHash.hex == config.expectedRawObjectHash)
        require(raw.provenance.production && raw.provenance.source.value == HYPERLIQUID_SOURCE)
        require(raw.rowCount == config.sourceRows.toLong())
        require(raw.provenance.request.method == "POST")
        require(raw.provenance.request.uri == HYPERLIQUID_INFO_URI)
        require(raw.provenance.request.parameters == mapOf("body" to config.expectedRequestBody))

        val candles = snapshotReader.events(snapshot).filterIsInstance<Candle>()
        require(candles.size == config.sourceRows)
        require(candles.all(Candle::closed))
        require(
            candles.all {
                it.header.source.value == HYPERLIQUID_SOURCE &&
                    it.header.instrument.value == ETH_PERPETUAL_INSTRUMENT
            },
        )
        val hourly = validateCandles(candles)
        val allRows = labeledRows(hourly)
        require(allRows.size == config.expectedLabeledRows)
        val sealed = allRows.takeLast(config.sealedHoldoutRows)
        require(sealed.first().decisionTime.epochMillis == config.firstHoldoutDecisionEpochMillis)
        require(sealed.last().decisionTime.epochMillis == config.lastHoldoutDecisionEpochMillis)
        require(sealed.last().labelTo.epochMillis == config.holdoutLabelToEpochMillis)
        val firstHoldout = sealed.first().decisionTime.epochMillis
        val candidates = allRows.dropLast(config.sealedHoldoutRows)
        val validation =
            candidates.filter { observation ->
                observation.labelTo.epochMillis <= firstHoldout - config.purgeMillis &&
                    observation.labelTo.epochMillis + config.embargoMillis <= firstHoldout
            }
        val boundaryPurged = candidates.size - validation.size
        require(validation.size == config.expectedValidationRows)
        require(boundaryPurged == config.expectedBoundaryPurgedRows)
        return PreparedPeriodicityData(
            snapshot,
            hourly.size,
            allRows.size,
            validation,
            sealed,
            boundaryPurged,
        )
    }

    private fun validateCandles(candles: List<Candle>): List<Candle> {
        val ordered =
            candles.sortedWith(compareBy<Candle>({ it.header.exchangeTime }, { it.header.id.value }))
        require(ordered.map { it.header.id }.distinct().size == ordered.size)
        require(ordered.all { it.intervalMillis == PERIODICITY_HOUR_MILLIS })
        ordered.forEach { candle ->
            require(candle.header.availableAt.epochMillis == candle.header.exchangeTime.epochMillis + 1L)
            require(candle.close.toBigDecimal().signum() > 0)
            require(candle.tradeCount > 0)
        }
        ordered.zipWithNext().forEach { (previous, next) ->
            require(
                next.header.exchangeTime.epochMillis - previous.header.exchangeTime.epochMillis ==
                    PERIODICITY_HOUR_MILLIS,
            )
            require(next.header.availableAt > previous.header.availableAt)
        }
        require(
            ordered.first().header.availableAt.epochMillis ==
                config.expectedFirstAvailableAtEpochMillis,
        )
        require(
            ordered.last().header.availableAt.epochMillis ==
                config.expectedLastAvailableAtEpochMillis,
        )
        return ordered
    }

    private fun labeledRows(hourly: List<Candle>): List<LabeledObservation> {
        val closes = hourly.map { it.close.toBigDecimal().toDouble() }
        require(closes.all { it.isFinite() && it > 0.0 })
        val returns =
            DoubleArray(hourly.size) { index ->
                if (index == 0) {
                    Double.NaN
                } else {
                    ln(closes[index] / closes[index - 1]).also { require(it.isFinite()) }
                }
            }
        return (config.trailingVarianceRows until hourly.lastIndex).map { index ->
            val decision = hourly[index]
            val target = hourly[index + 1]
            val currentVariance = floorVariance(returns[index] * returns[index])
            val trailing =
                ((index - config.trailingVarianceRows + 1)..index)
                    .map { returns[it] * returns[it] }
                    .average()
                    .let(::floorVariance)
            val targetVariance = floorVariance(returns[index + 1] * returns[index + 1])
            val hour =
                Instant
                    .ofEpochMilli(decision.header.availableAt.epochMillis)
                    .atZone(ZoneOffset.UTC)
                    .hour
            val rowId =
                "${decision.header.instrument.value}:${decision.header.exchangeTime.epochMillis}"
            val values =
                buildMap {
                    put(LOG_RV_1H_FEATURE, ln(currentVariance))
                    put(LOG_RV_24H_FEATURE, ln(trailing))
                    (1..23).forEach { candidateHour ->
                        put(hourFeature(candidateHour), if (hour == candidateHour) 1.0 else 0.0)
                    }
                }
            LabeledObservation(
                rowId = rowId,
                decisionTime = decision.header.availableAt,
                labelFrom = decision.header.availableAt,
                labelTo = target.header.availableAt,
                features = FeatureVector(rowId, values),
                label = targetVariance,
            )
        }
    }

    private fun floorVariance(value: Double): Double {
        require(value.isFinite() && value >= 0.0)
        return value.coerceAtLeast(config.varianceFloor)
    }

    private fun evaluate(
        rows: List<LabeledObservation>,
        sealed: List<LabeledObservation>,
    ): PeriodicityEvaluation {
        val planner =
            ExactPeriodicityFoldPlanner(
                WalkForwardSplitPlanner(
                    WalkForwardConfig(
                        config.minimumTrainingRows,
                        config.testRows,
                        config.stepRows,
                        config.purgeMillis,
                        config.embargoMillis,
                    ),
                ),
                config.foldCount,
            )
        val development =
            VarianceWalkForwardExperiment(
                planner,
                candidateEstimator(),
                controls(),
                config.hacLag,
            ).run(rows)
        val holdout =
            VarianceHoldoutExperiment(
                candidateEstimator(),
                controls(),
                config.hacLag,
            ).run(rows, sealed)
        val supported =
            holdout.controls.all {
                holdout.candidateMetrics.meanQlikeLoss < it.metrics.meanQlikeLoss &&
                    it.comparison.meanQlikeImprovement > 0.0 &&
                    it.benjaminiHochbergPValue < config.predictiveAlpha
            }
        return PeriodicityEvaluation(
            development,
            holdout,
            planner.availableFoldCount,
            planner.audits,
            coefficients(rows),
            if (supported) {
                "SEALED_HOLDOUT_HOURLY_VOLATILITY_PERIODICITY_SUPPORTED"
            } else {
                "SEALED_HOLDOUT_HOURLY_VOLATILITY_PERIODICITY_NOT_SUPPORTED"
            },
        )
    }

    private fun candidateEstimator() =
        LogTargetEstimator(LinearEstimator(CANDIDATE_FEATURES, intercept = true))

    private fun controls() =
        listOf(
            NamedVarianceControl(
                DYNAMIC_CONTROL,
                LogTargetEstimator(LinearEstimator(DYNAMIC_FEATURES, intercept = true)),
            ),
            NamedVarianceControl(HISTORICAL_MEAN_CONTROL, HistoricalMeanEstimator()),
        )

    private fun coefficients(rows: List<LabeledObservation>): Map<String, Double> {
        val logRows = rows.map { it.copy(label = ln(it.label)) }
        val fit =
            LinearEstimator(CANDIDATE_FEATURES, intercept = true).fit(logRows)
                as LinearFittedEstimator
        require(fit.coefficients.size == CANDIDATE_FEATURES.size + 1)
        return (listOf("intercept") + CANDIDATE_FEATURES)
            .zip(fit.coefficients.toList())
            .toMap()
    }

    private fun predictionsJson(
        run: ExperimentRunRow,
        prepared: PreparedPeriodicityData,
        evaluated: PeriodicityEvaluation,
    ) = buildJsonObject {
        val rows = (prepared.validationRows + prepared.sealedHoldout).associateBy { it.rowId }
        put("schemaVersion", PREDICTIONS_SCHEMA)
        put("runId", run.id.toString())
        put("theoryId", run.theoryId)
        put("theoryVersion", run.theoryVersion)
        put("theoryPlanHash", run.theoryPlanHash)
        put("snapshotId", run.snapshotId.toString())
        put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
        put("sourceRevision", sourceRevision)
        put("source", HYPERLIQUID_SOURCE)
        put("instrument", ETH_PERPETUAL_INSTRUMENT)
        put("realDataOnly", true)
        put("productionSource", true)
        put(
            "observations",
            buildJsonArray {
                evaluated.development.observations.forEach { observation ->
                    val row = requireNotNull(rows[observation.rowId])
                    add(
                        varianceObservationJson(
                            "DEVELOPMENT",
                            observation.fold,
                            row,
                            observation.actual,
                            observation.candidate,
                            observation.controls,
                        ),
                    )
                }
                evaluated.holdout.observations.forEach { observation ->
                    val row = requireNotNull(rows[observation.rowId])
                    add(
                        varianceObservationJson(
                            "SEALED_HOLDOUT",
                            null,
                            row,
                            observation.actual,
                            observation.candidate,
                            observation.controls,
                        ),
                    )
                }
            },
        )
    }

    private fun varianceObservationJson(
        phase: String,
        fold: Int?,
        row: LabeledObservation,
        actual: Double,
        candidate: Double,
        controls: Map<String, Double>,
    ) = buildJsonObject {
        put("phase", phase)
        if (fold == null) put("fold", JsonNull) else put("fold", fold)
        put("rowId", row.rowId)
        put("decisionTimeEpochMillis", row.decisionTime.epochMillis)
        put("labelFromEpochMillis", row.labelFrom.epochMillis)
        put("labelToEpochMillis", row.labelTo.epochMillis)
        put("features", numericMapJson(row.features.values))
        put("actualVariance", actual)
        put("candidateVariance", candidate)
        put("controls", numericMapJson(controls))
    }

    private fun reportJson(
        run: ExperimentRunRow,
        prepared: PreparedPeriodicityData,
        evaluated: PeriodicityEvaluation,
        metrics: JsonObject,
    ) = buildJsonObject {
        put("schemaVersion", REPORT_SCHEMA)
        put("runId", run.id.toString())
        put("theoryId", run.theoryId)
        put("theoryVersion", run.theoryVersion)
        put("theoryPlanHash", run.theoryPlanHash)
        put("snapshotId", run.snapshotId.toString())
        put("contractSnapshotId", prepared.snapshot.id.value)
        put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
        put("sourceRevision", sourceRevision)
        put("source", HYPERLIQUID_SOURCE)
        put("instrument", ETH_PERPETUAL_INSTRUMENT)
        put("realDataOnly", true)
        put("productionSource", true)
        put("sourceCandleCount", prepared.sourceRows)
        put("totalLabeledRowCount", prepared.totalLabeledRows)
        put("validationRowCount", prepared.validationRows.size)
        put("sealedHoldoutRows", prepared.sealedHoldout.size)
        put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
        put("sealedHoldoutFromEpochMillis", prepared.sealedHoldout.first().decisionTime.epochMillis)
        put("sealedHoldoutLastDecisionEpochMillis", prepared.sealedHoldout.last().decisionTime.epochMillis)
        put("sealedHoldoutToEpochMillis", prepared.sealedHoldout.last().labelTo.epochMillis)
        put("sealedHoldoutEvaluated", true)
        put("singleUnconditionalHoldoutDecision", true)
        put("modelSelectionUsedHoldout", false)
        put("crossRunHoldoutReusePrevented", false)
        put("candidateEstimator", CANDIDATE_ESTIMATOR)
        put("controls", buildJsonArray { controls().forEach { add(it.name) } })
        put("fittedDevelopmentCoefficients", numericMapJson(evaluated.coefficients))
        put("validation", validationJson(evaluated))
        put("rawSourceObjects", rawSourceObjectsJson(prepared.snapshot))
        put("metrics", metrics)
        put("evidenceStatus", evaluated.evidenceStatus)
        put("promotionStatus", PromotionStatus.BLOCKED.name)
        put("promotionReasons", promotionReasonsJson())
        put("limitations", limitationsJson())
    }

    private fun metricsJson(
        evaluated: PeriodicityEvaluation,
        prepared: PreparedPeriodicityData,
    ) = buildJsonObject {
        put("schemaVersion", METRICS_SCHEMA)
        put("instrument", ETH_PERPETUAL_INSTRUMENT)
        put(
            "development",
            evaluationJson(
                evaluated.development.observations.size,
                config.foldCount,
                evaluated.development.candidateMetrics,
                evaluated.development.controls,
            ),
        )
        put(
            "sealedHoldout",
            evaluationJson(
                evaluated.holdout.observations.size,
                1,
                evaluated.holdout.candidateMetrics,
                evaluated.holdout.controls,
            ),
        )
        put("evidenceStatus", evaluated.evidenceStatus)
        put("evidenceDecisionPhase", "SEALED_HOLDOUT")
        put("sealedHoldoutRows", prepared.sealedHoldout.size)
        put("sealedHoldoutEvaluated", true)
        put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
        put("singleUnconditionalHoldoutDecision", true)
    }

    private fun evaluationJson(
        count: Int,
        folds: Int,
        candidate: VarianceForecastMetricSet,
        controls: List<VarianceControlEvaluation>,
    ) = buildJsonObject {
        put("count", count)
        put("foldCount", folds)
        put("candidate", varianceMetricsJson(candidate))
        put(
            "controls",
            buildJsonArray {
                controls.forEach {
                    add(
                        buildJsonObject {
                            put("name", it.name)
                            put("metrics", varianceMetricsJson(it.metrics))
                            put("meanQlikeImprovement", it.comparison.meanQlikeImprovement)
                            put("hacStandardError", it.comparison.hacStandardError)
                            put("zStatistic", finiteOrNull(it.comparison.zStatistic))
                            put("twoSidedPValue", it.comparison.twoSidedPValue)
                            put("benjaminiHochbergPValue", it.benjaminiHochbergPValue)
                            put("hacLag", it.comparison.hacLag)
                        },
                    )
                }
            },
        )
    }

    private fun varianceMetricsJson(value: VarianceForecastMetricSet) =
        buildJsonObject {
            put("count", value.count)
            put("meanQlikeLoss", value.meanQlikeLoss)
            put("meanAbsoluteError", value.meanAbsoluteError)
            put("rootMeanSquaredError", value.rootMeanSquaredError)
        }

    private fun validationJson(evaluated: PeriodicityEvaluation) =
        buildJsonObject {
            put("scheme", "EXPANDING")
            put("minimumTrainingRows", config.minimumTrainingRows)
            put("testRows", config.testRows)
            put("stepRows", config.stepRows)
            put("foldCount", config.foldCount)
            put("availableFoldCount", evaluated.availableFoldCount)
            put("purgeMillis", config.purgeMillis)
            put("embargoMillis", config.embargoMillis)
            put("hacLag", config.hacLag)
            put("comparisonCount", 2)
            put("localFamilyCorrection", "BENJAMINI_HOCHBERG")
            put("registeredGlobalSpaEvaluated", false)
            put(
                "folds",
                buildJsonArray {
                    evaluated.audits.forEach { audit ->
                        add(
                            buildJsonObject {
                                put("index", audit.index)
                                put("trainingCount", audit.trainingCount)
                                put("testCount", audit.testCount)
                                put("trainingLastLabelToEpochMillis", audit.trainingLastLabelTo)
                                put("testFirstDecisionEpochMillis", audit.testFirstDecision)
                                put("testLastDecisionEpochMillis", audit.testLastDecision)
                            },
                        )
                    }
                },
            )
        }

    private fun rawSourceObjectsJson(snapshot: DataSnapshot) =
        buildJsonArray {
            snapshot.objects.forEach { raw ->
                add(
                    buildJsonObject {
                        put("contentHash", raw.contentHash.hex)
                        put("byteCount", raw.byteCount)
                        put("rowCount", raw.rowCount)
                        put("source", raw.provenance.source.value)
                        put("production", raw.provenance.production)
                        put("objectUri", raw.uri)
                        put("requestMethod", raw.provenance.request.method)
                        put("requestUri", raw.provenance.request.uri)
                        put(
                            "requestParameters",
                            buildJsonObject {
                                raw.provenance.request.parameters.toSortedMap().forEach { (key, value) ->
                                    put(key, value)
                                }
                            },
                        )
                        put("retrievedAtEpochMillis", raw.provenance.retrievedAt.epochMillis)
                        put("eventFromInclusiveEpochMillis", raw.eventTimeRange.fromInclusive.epochMillis)
                        put("eventToExclusiveEpochMillis", raw.eventTimeRange.toExclusive.epochMillis)
                        put(
                            "availabilityFromInclusiveEpochMillis",
                            raw.availabilityTimeRange.fromInclusive.epochMillis,
                        )
                        put(
                            "availabilityToExclusiveEpochMillis",
                            raw.availabilityTimeRange.toExclusive.epochMillis,
                        )
                        put("schemaVersion", raw.provenance.schemaVersion)
                        put("adapterVersion", raw.provenance.adapterVersion)
                    },
                )
            }
        }

    private fun promotionReasonsJson() =
        buildJsonArray {
            add("The periodicity adaptation is forecast-only and paper-ineligible")
            add("One squared hourly return is a noisy variance proxy")
            add("The ETH-only result does not reproduce the published multi-venue GARCH study")
            add("No spread, depth, fees, latency, capacity, funding, or execution was evaluated")
            add("Local HAC/BH comparisons do not implement the registered global SPA test")
            add("The confirmation dates overlap prior BTC analyses and no global holdout lock exists")
        }

    private fun limitationsJson() =
        buildJsonArray {
            add("ETH-only retrospective Hyperliquid perpetual sample")
            add("Hourly squared-return proxy rather than high-frequency realized variance")
            add("UTC categorical OLS rather than the paper's multi-venue periodic GARCH models")
            add("No economic or execution evaluation")
            add("Cross-asset calendar overlap with prior BTC screens")
        }

    private fun artifactManifest(
        run: ExperimentRunRow,
        prepared: PreparedPeriodicityData,
        fence: ExperimentLeaseFence,
        kind: String,
        artifact: StoredExperimentArtifact,
    ) = buildJsonObject {
        put("schemaVersion", ARTIFACT_MANIFEST_SCHEMA)
        put("runId", run.id.toString())
        put("kind", kind)
        put("contentHash", artifact.contentHash)
        put("byteCount", artifact.byteCount)
        put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
        put("theoryPlanHash", expectedPlanHash)
        put("jobId", fence.jobId.toString())
        put("attemptId", fence.attemptId.toString())
        put("leaseOwner", fence.workerId)
        put("leaseToken", fence.leaseToken.toString())
        put("producer", ENGINE_NAME)
        put("sourceRevision", sourceRevision)
        put("realDataOnly", true)
    }

    private fun artifactReference(id: UUID, artifact: StoredExperimentArtifact) =
        buildJsonObject {
            put("id", id.toString())
            put("contentHash", artifact.contentHash)
            put("uri", artifact.uri)
            put("byteCount", artifact.byteCount)
            put("mediaType", JSON_MEDIA_TYPE)
        }

    private fun terminalFailure(
        fence: ExperimentLeaseFence,
        status: RunStatus,
        code: String,
        message: String,
        failedTrialParameters: JsonObject? = null,
    ): JobExecutionResult.Failed {
        val safe = sanitize(message)
        val failure =
            buildJsonObject {
                put("code", code)
                put("message", safe)
                put("retryable", false)
                put("realDataOnly", true)
            }
        persistence.finishRun(
            fence,
            status,
            buildJsonObject {
                put("schemaVersion", RUN_MANIFEST_SCHEMA)
                put("realDataOnly", true)
                put("sourceRevision", sourceRevision)
                put("theoryPlanHash", expectedPlanHash)
                put("jobId", fence.jobId.toString())
                put("attemptId", fence.attemptId.toString())
                put("leaseOwner", fence.workerId)
                put("leaseToken", fence.leaseToken.toString())
                put("promotionStatus", PromotionStatus.BLOCKED.name)
                put("promotionReasons", promotionReasonsJson())
            },
            null,
            failure,
            PromotionStatus.BLOCKED,
            trials =
                failedTrialParameters?.let {
                    listOf(TrialWrite(0, it, TrialStatus.FAILED, null, failure))
                }.orEmpty(),
        )
        return JobExecutionResult.Failed(code, safe, false)
    }

    private fun recovered(run: ExperimentRunRow): JobExecutionResult.Succeeded {
        require(run.theoryId == HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID)
        require(run.theoryVersion == THEORY_VERSION && run.theoryPlanHash == expectedPlanHash)
        require(run.promotionStatus == PromotionStatus.BLOCKED)
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

    private fun validatePayloadIdentity(job: JobRow, run: ExperimentRunRow) {
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

    private fun requirePayloadEquals(job: JobRow, name: String, expected: String) {
        if (requirePayloadString(job, name) != expected) {
            throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment payload $name does not match the persisted run",
            )
        }
    }

    private fun requirePayloadString(job: JobRow, name: String): String =
        job.payload[name]?.jsonPrimitive?.content
            ?: throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment payload is missing $name",
            )

    private fun parseUuid(value: String, field: String): UUID =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw PermanentJobException(
                "INVALID_EXPERIMENT_PAYLOAD",
                "Experiment $field must be a UUID",
            )
        }

    private fun numericMapJson(values: Map<String, Double>) =
        buildJsonObject { values.toSortedMap().forEach { (key, value) -> put(key, value) } }

    private fun finiteOrNull(value: Double): JsonElement =
        if (value.isFinite()) JsonPrimitive(value) else JsonNull

    private fun sanitize(message: String): String =
        message
            .replace(CONTROL_CHARACTERS, " ")
            .trim()
            .ifBlank { "Hourly volatility periodicity experiment could not be completed" }
            .take(MAX_MESSAGE_CHARS)

    private data class PreparedPeriodicityData(
        val snapshot: DataSnapshot,
        val sourceRows: Int,
        val totalLabeledRows: Int,
        val validationRows: List<LabeledObservation>,
        val sealedHoldout: List<LabeledObservation>,
        val boundaryPurgedRows: Int,
    )

    private data class PeriodicityEvaluation(
        val development: VarianceWalkForwardExperimentResult,
        val holdout: VarianceHoldoutExperimentResult,
        val availableFoldCount: Int,
        val audits: List<PeriodicityFoldAudit>,
        val coefficients: Map<String, Double>,
        val evidenceStatus: String,
    )

    private class ExactPeriodicityFoldPlanner(
        private val delegate: SplitPlanner,
        private val required: Int,
    ) : SplitPlanner {
        var availableFoldCount = 0
            private set
        var audits: List<PeriodicityFoldAudit> = emptyList()
            private set

        override fun plan(rows: List<LabeledObservation>): List<WalkForwardFold> {
            val available =
                try {
                    delegate.plan(rows)
                } catch (exception: IllegalArgumentException) {
                    throw InsufficientPeriodicityDataException(
                        exception.message ?: "Real data cannot form a walk-forward fold",
                    )
                }
            if (available.size < required) {
                throw InsufficientPeriodicityDataException(
                    "Real data yields ${available.size} folds; $required are required",
                )
            }
            availableFoldCount = available.size
            val selected = available.takeLast(required)
            val byId = rows.associateBy { it.rowId }
            audits =
                selected.map { fold ->
                    val test = fold.testRowIds.map { requireNotNull(byId[it]) }
                    PeriodicityFoldAudit(
                        fold.index,
                        fold.training.size,
                        test.size,
                        fold.training.maxOf { it.labelTo.epochMillis },
                        test.minOf { it.decisionTime.epochMillis },
                        test.maxOf { it.decisionTime.epochMillis },
                    )
                }
            return selected
        }
    }

    private data class PeriodicityFoldAudit(
        val index: Int,
        val trainingCount: Int,
        val testCount: Int,
        val trainingLastLabelTo: Long,
        val testFirstDecision: Long,
        val testLastDecision: Long,
    )

    private class InsufficientPeriodicityDataException(
        override val message: String,
    ) : IllegalArgumentException(message)

    private companion object {
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        const val THEORY_VERSION = "1.0.0"
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val HYPERLIQUID_INFO_URI = "https://api.hyperliquid.xyz/info"
        const val ETH_PERPETUAL_INSTRUMENT = "hyperliquid:perpetual:ETH"
        const val LOG_RV_1H_FEATURE = "log_rv_1h"
        const val LOG_RV_24H_FEATURE = "log_rv_24h"
        val DYNAMIC_FEATURES = PERIODICITY_DYNAMIC_FEATURES
        fun hourFeature(hour: Int) = periodicityHourFeature(hour)
        val CANDIDATE_FEATURES = PERIODICITY_CANDIDATE_FEATURES
        const val DYNAMIC_CONTROL = "CLOCK_FREE_DYNAMIC_LOG_VARIANCE"
        const val HISTORICAL_MEAN_CONTROL = "EXPANDING_HISTORICAL_MEAN_VARIANCE"
        const val CANDIDATE_ESTIMATOR =
            "LOG_TARGET_OLS_INTERCEPT_LOG_RV_1H_LOG_RV_24H_PLUS_23_UTC_HOUR_INDICATORS"
        const val EXPERIMENT_JOB_KIND = "EXPERIMENT_RUN"
        const val RUN_RESOURCE_TYPE = "run"
        const val PREDICTIONS_ARTIFACT_KIND = "PREDICTIONS"
        const val REPORT_ARTIFACT_KIND = "REPORT"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ENGINE_NAME = "marketlab-kotlin-eth-hourly-volatility-periodicity-engine-v1"
        const val PREDICTIONS_SCHEMA = "marketlab.eth-hourly-volatility-periodicity-predictions.v1"
        const val REPORT_SCHEMA = "marketlab.eth-hourly-volatility-periodicity-report.v1"
        const val METRICS_SCHEMA = "marketlab.eth-hourly-volatility-periodicity-metrics.v1"
        const val ARTIFACT_MANIFEST_SCHEMA = "marketlab.artifact-manifest.v1"
        const val RUN_MANIFEST_SCHEMA = "marketlab.run-manifest.v1"
        const val MAX_MESSAGE_CHARS = 1_000
    }
}

private val PERIODICITY_SHA256 = Regex("[0-9a-f]{64}")
private val PERIODICITY_DYNAMIC_FEATURES = listOf("log_rv_1h", "log_rv_24h")
private fun periodicityHourFeature(hour: Int) =
    "next_hour_${hour.toString().padStart(2, '0')}_utc"
private val PERIODICITY_CANDIDATE_FEATURES =
    PERIODICITY_DYNAMIC_FEATURES + (1..23).map(::periodicityHourFeature)
