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
import dev.marketlab.engine.LogTargetEstimator
import dev.marketlab.engine.NamedVarianceControl
import dev.marketlab.engine.PersistenceEstimator
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

internal const val HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID =
    "hyperliquid-btc-four-hour-log-har-variance"
private const val HAR_FOUR_HOUR_MILLIS = 14_400_000L
private const val HAR_DAY_MILLIS = 86_400_000L

/**
 * The production defaults are the registered experiment, not suggestions.
 * Tests may inject a smaller, internally consistent configuration around an
 * exact real Hyperliquid response; production wiring never does so.
 */
internal data class HarVarianceExperimentConfig(
    val sourceRows: Int = 4_201,
    val returnsPerDay: Int = 6,
    val weeklyDays: Int = 7,
    val monthlyDays: Int = 30,
    val sealedHoldoutRows: Int = 90,
    val minimumTrainingRows: Int = 365,
    val testRows: Int = 30,
    val stepRows: Int = 30,
    val foldCount: Int = 5,
    val purgeMillis: Long = HAR_DAY_MILLIS,
    val embargoMillis: Long = HAR_DAY_MILLIS,
    val hacLag: Int = 7,
    val predictiveAlpha: Double = 0.05,
    val firstSourceAvailableAtEpochMillis: Long = 1_714_435_200_000L,
    val lastSourceAvailableAtEpochMillis: Long = 1_774_915_200_000L,
    val firstHoldoutDecisionEpochMillis: Long = 1_767_139_200_000L,
    val lastHoldoutDecisionEpochMillis: Long = 1_774_828_800_000L,
    val lastHoldoutLabelToEpochMillis: Long = 1_774_915_200_000L,
    val expectedRequestBody: String =
        """{"req":{"coin":"BTC","endTime":1774915199999,"interval":"4h","startTime":1714420800000},"type":"candleSnapshot"}""",
) {
    val dailyRealizedVarianceRows: Int = (sourceRows - 1) / returnsPerDay
    val expectedLabeledRows: Int = dailyRealizedVarianceRows - monthlyDays
    val minimumHistoryMillis: Long = (sourceRows - 1L) * HAR_FOUR_HOUR_MILLIS

    init {
        require(sourceRows > 1)
        require(returnsPerDay > 0)
        require((sourceRows - 1) % returnsPerDay == 0) {
            "source rows must contain one prior endpoint and complete UTC days"
        }
        require(returnsPerDay * HAR_FOUR_HOUR_MILLIS == HAR_DAY_MILLIS) {
            "HAR aggregation must use exactly six four-hour returns per UTC day"
        }
        require(weeklyDays in 2 until monthlyDays)
        require(monthlyDays < dailyRealizedVarianceRows)
        require(expectedLabeledRows > sealedHoldoutRows)
        require(sealedHoldoutRows > 0)
        require(minimumTrainingRows > 4)
        require(testRows > 0 && stepRows > 0)
        require(foldCount >= 1)
        require(purgeMillis >= 0 && embargoMillis >= 0)
        require(hacLag >= 0)
        require(predictiveAlpha.isFinite() && predictiveAlpha > 0.0 && predictiveAlpha < 1.0)
        require(
            lastSourceAvailableAtEpochMillis - firstSourceAvailableAtEpochMillis ==
                minimumHistoryMillis,
        ) {
            "source availability interval must exactly match the native candle count"
        }
        require(firstSourceAvailableAtEpochMillis % HAR_DAY_MILLIS == 0L)
        require(lastSourceAvailableAtEpochMillis % HAR_DAY_MILLIS == 0L)
        require(firstHoldoutDecisionEpochMillis % HAR_DAY_MILLIS == 0L)
        require(lastHoldoutDecisionEpochMillis % HAR_DAY_MILLIS == 0L)
        require(lastHoldoutLabelToEpochMillis % HAR_DAY_MILLIS == 0L)
        require(
            lastHoldoutDecisionEpochMillis - firstHoldoutDecisionEpochMillis ==
                (sealedHoldoutRows - 1L) * HAR_DAY_MILLIS,
        ) {
            "holdout decision interval must contain exactly the configured outcomes"
        }
        require(
            lastHoldoutLabelToEpochMillis ==
                lastHoldoutDecisionEpochMillis + HAR_DAY_MILLIS,
        )
        require(lastHoldoutLabelToEpochMillis == lastSourceAvailableAtEpochMillis)
        require(expectedRequestBody.isNotBlank())
    }
}

/**
 * Executes the fixed BTC four-hour log-HAR variance adaptation.
 *
 * It has no shorter-history, alternate-interval, alternate-instrument, or
 * generated-data path. The sealed holdout is opened unconditionally after all
 * model and control definitions have already been fixed.
 */
internal class HarVarianceExperimentJobHandler(
    private val persistence: ExperimentPersistence,
    private val snapshotReader: StoredHyperliquidSnapshotReader,
    private val artifacts: ExperimentArtifactStore,
    private val sourceRevision: String,
    private val expectedPlanHash: String,
    private val config: HarVarianceExperimentConfig = HarVarianceExperimentConfig(),
) : ExperimentJobHandler {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    init {
        require(SHA256.matches(sourceRevision)) {
            "HAR variance experiment source revision must be a lowercase SHA-256 digest"
        }
        require(SHA256.matches(expectedPlanHash)) {
            "HAR variance theory plan hash must be a lowercase SHA-256 digest"
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

            when {
                initial.theoryId != HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_EXPERIMENT_THEORY",
                        "HAR variance executor received a different theory",
                    )
                initial.theoryVersion != THEORY_VERSION ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_VERSION",
                        "HAR variance executor implements only the frozen theory version $THEORY_VERSION",
                    )
                initial.theoryPlanHash != expectedPlanHash ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "THEORY_PLAN_HASH_MISMATCH",
                        "Persisted HAR variance plan does not match this executor",
                    )
                initial.parameters.isNotEmpty() ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_PARAMETERS",
                        "The frozen HAR variance adaptation has no runtime parameters",
                    )
            }

            val prepared =
                try {
                    prepare(initial)
                } catch (exception: InsufficientRealDataException) {
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
                } catch (exception: InsufficientRealDataException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.INCONCLUSIVE,
                        "INSUFFICIENT_REAL_DATA",
                        exception.message,
                    )
                } catch (exception: IllegalArgumentException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.INCONCLUSIVE,
                        "EXPERIMENT_ESTIMATION_INVALID",
                        exception.message ?: "HAR variance estimation could not be completed",
                        failedTrialParameters = initial.parameters,
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.FAILED,
                        "EXPERIMENT_ENGINE_FAILED",
                        exception.message ?: "HAR variance experiment engine failed",
                        failedTrialParameters = initial.parameters,
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
                trials =
                    listOf(
                        TrialWrite(
                            trialNumber = 0,
                            parameters = initial.parameters,
                            status = TrialStatus.SUCCEEDED,
                            metrics = metrics,
                            failure = null,
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

    private fun prepare(run: ExperimentRunRow): PreparedHarVarianceData {
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
        require(snapshot.requirements.size == 1) {
            "HAR variance requires exactly one immutable data requirement"
        }
        val requirement = snapshot.requirements.single()
        require(requirement.observation == ObservationKind.CANDLE) {
            "HAR variance requires an immutable candle-only snapshot"
        }
        require(
            requirement.sourcePreference == listOf(DataSourceId(HYPERLIQUID_SOURCE)),
        ) {
            "HAR variance requires a Hyperliquid-only source preference"
        }
        require(requirement.instrumentKinds == listOf(InstrumentKind.PERPETUAL)) {
            "HAR variance requires a perpetual-only data contract"
        }
        require(
            (requirement.sampling as? Sampling.FixedDuration)?.millis == FOUR_HOUR_MILLIS,
        ) {
            "HAR variance requires a native four-hour sampling contract"
        }
        require("close" in requirement.requiredFields) {
            "HAR variance requires observed four-hour closes"
        }
        require(requirement.minimumHistoryMillis >= config.minimumHistoryMillis) {
            "Snapshot requirement declares less history than the frozen HAR variance design"
        }
        require(snapshot.objects.size == 1) {
            "HAR variance requires exactly one immutable raw source object"
        }
        val rawObject = snapshot.objects.single()
        require(
            rawObject.provenance.production &&
                rawObject.provenance.source.value == HYPERLIQUID_SOURCE,
        ) {
            "HAR variance requires a production Hyperliquid source object"
        }
        require(rawObject.rowCount == config.sourceRows.toLong()) {
            "HAR variance raw-object row count differs from the frozen source count"
        }
        require(rawObject.provenance.request.method == "POST") {
            "HAR variance requires the frozen Hyperliquid POST request"
        }
        require(rawObject.provenance.request.uri == HYPERLIQUID_INFO_URI) {
            "HAR variance raw object has a different source URI"
        }
        require(
            rawObject.provenance.request.parameters ==
                mapOf("body" to config.expectedRequestBody),
        ) {
            "HAR variance raw object has different canonical request parameters"
        }

        val candles = snapshotReader.events(snapshot).filterIsInstance<Candle>()
        require(candles.isNotEmpty()) { "Snapshot replay produced no candles" }
        require(candles.all(Candle::closed)) {
            "HAR variance requires completed candles"
        }
        require(
            candles.all {
                it.header.source.value == HYPERLIQUID_SOURCE &&
                    it.header.instrument.value == BTC_PERPETUAL_INSTRUMENT
            },
        ) {
            "HAR variance requires exactly the Hyperliquid BTC perpetual"
        }
        val sourceCandles = validateFourHourCandles(candles)
        val dailyVariances = aggregateDailyRealizedVariances(sourceCandles)
        val allRows = labeledHarRows(dailyVariances)
        require(allRows.size == config.expectedLabeledRows) {
            "Exact HAR interval produced ${allRows.size} labels; " +
                "${config.expectedLabeledRows} are required"
        }
        val sealed = allRows.takeLast(config.sealedHoldoutRows)
        require(
            sealed.first().decisionTime.epochMillis ==
                config.firstHoldoutDecisionEpochMillis,
        ) {
            "Sealed HAR holdout begins at a different UTC decision"
        }
        require(
            sealed.last().decisionTime.epochMillis ==
                config.lastHoldoutDecisionEpochMillis,
        ) {
            "Sealed HAR holdout ends at a different UTC decision"
        }
        require(
            sealed.last().labelTo.epochMillis ==
                config.lastHoldoutLabelToEpochMillis,
        ) {
            "Sealed HAR holdout label ends at a different UTC boundary"
        }
        val firstHoldoutDecision = sealed.first().decisionTime
        val developmentCandidates = allRows.dropLast(config.sealedHoldoutRows)
        val validationRows =
            developmentCandidates.filter { row ->
                row.labelTo.epochMillis <=
                    Math.subtractExact(
                        firstHoldoutDecision.epochMillis,
                        config.purgeMillis,
                    ) &&
                    Math.addExact(row.labelTo.epochMillis, config.embargoMillis) <=
                    firstHoldoutDecision.epochMillis
            }
        val boundaryPurgedRows = developmentCandidates.size - validationRows.size
        if (validationRows.isEmpty()) {
            throw InsufficientRealDataException(
                "No development rows remain after the sealed-holdout boundary purge",
            )
        }
        return PreparedHarVarianceData(
            snapshot = snapshot,
            sourceCandles = sourceCandles,
            dailyVariances = dailyVariances,
            validationRows = validationRows,
            totalLabeledRows = allRows.size,
            sealedHoldout = sealed,
            boundaryPurgedRows = boundaryPurgedRows,
        )
    }

    private fun validateFourHourCandles(candles: List<Candle>): List<Candle> {
        val ordered =
            candles.sortedWith(
                compareBy<Candle>(
                    { it.header.exchangeTime },
                    { it.header.id.value },
                ),
            )
        require(ordered.size == config.sourceRows) {
            "HAR variance requires exactly ${config.sourceRows} native four-hour candles; " +
                "snapshot contains ${ordered.size}"
        }
        require(ordered.map { it.header.id }.distinct().size == ordered.size) {
            "Candle snapshot contains duplicate event ids"
        }
        require(ordered.all { it.intervalMillis == FOUR_HOUR_MILLIS }) {
            "HAR variance requires native four-hour candles"
        }
        ordered.forEach { candle ->
            require(
                candle.header.availableAt.epochMillis ==
                    Math.addExact(candle.header.exchangeTime.epochMillis, 1L),
            ) {
                "Historical four-hour candle availability must be its close plus one millisecond"
            }
            require(candle.close.toBigDecimal().signum() > 0) {
                "Observed four-hour close must be positive"
            }
            require(candle.tradeCount > 0) {
                "Observed four-hour candle trade count must be positive"
            }
        }
        ordered.zipWithNext().forEach { (previous, next) ->
            require(
                next.header.exchangeTime.epochMillis - previous.header.exchangeTime.epochMillis ==
                    FOUR_HOUR_MILLIS,
            ) {
                "Four-hour candle snapshot has a gap or duplicate timestamp"
            }
            require(next.header.availableAt > previous.header.availableAt) {
                "Four-hour candle availability is not strictly chronological"
            }
        }
        require(
            ordered.first().header.availableAt.epochMillis ==
                config.firstSourceAvailableAtEpochMillis,
        ) {
            "HAR source interval has a different first availability clock"
        }
        require(
            ordered.last().header.availableAt.epochMillis ==
                config.lastSourceAvailableAtEpochMillis,
        ) {
            "HAR source interval has a different last availability clock"
        }
        return ordered
    }

    private fun aggregateDailyRealizedVariances(
        candles: List<Candle>,
    ): List<DailyRealizedVariance> {
        val closes = candles.map { it.close.toBigDecimal().toDouble() }
        require(closes.all { it.isFinite() && it > 0.0 }) {
            "Observed four-hour closes are not finite and positive"
        }
        return (0 until config.dailyRealizedVarianceRows).map { day ->
            val first = day * config.returnsPerDay
            val last = first + config.returnsPerDay
            val decisionCandle = candles[last]
            require(decisionCandle.header.availableAt.epochMillis % DAY_MILLIS == 0L) {
                "Daily realized variance does not end at UTC midnight"
            }
            val variance =
                ((first + 1)..last).sumOf { index ->
                    val value = ln(closes[index] / closes[index - 1])
                    require(value.isFinite()) {
                        "Observed four-hour log return is not finite"
                    }
                    value * value
                }
            require(variance.isFinite() && variance > 0.0) {
                "Every daily realized variance must be positive and finite"
            }
            DailyRealizedVariance(
                decisionCandle = decisionCandle,
                value = variance,
            )
        }
    }

    private fun labeledHarRows(
        dailyVariances: List<DailyRealizedVariance>,
    ): List<LabeledObservation> {
        require(dailyVariances.size == config.dailyRealizedVarianceRows)
        return ((config.monthlyDays - 1) until dailyVariances.lastIndex).map { index ->
            val current = dailyVariances[index]
            val target = dailyVariances[index + 1]
            val weekly =
                dailyVariances
                    .subList(index - config.weeklyDays + 1, index + 1)
                    .map(DailyRealizedVariance::value)
                    .average()
            val monthly =
                dailyVariances
                    .subList(index - config.monthlyDays + 1, index + 1)
                    .map(DailyRealizedVariance::value)
                    .average()
            require(weekly.isFinite() && weekly > 0.0)
            require(monthly.isFinite() && monthly > 0.0)
            val rowId =
                "$BTC_PERPETUAL_INSTRUMENT:" +
                    current.decisionCandle.header.availableAt.epochMillis
            LabeledObservation(
                rowId = rowId,
                decisionTime = current.decisionCandle.header.availableAt,
                labelFrom = current.decisionCandle.header.availableAt,
                labelTo = target.decisionCandle.header.availableAt,
                features =
                    FeatureVector(
                        rowId = rowId,
                        values =
                            mapOf(
                                LOG_RV_DAILY_FEATURE to ln(current.value),
                                LOG_RV_WEEKLY_FEATURE to ln(weekly),
                                LOG_RV_MONTHLY_FEATURE to ln(monthly),
                                CURRENT_RV_FEATURE to current.value,
                            ),
                    ),
                label = target.value,
            )
        }
    }

    private fun evaluate(
        rows: List<LabeledObservation>,
        sealedHoldout: List<LabeledObservation>,
    ): HarVarianceEvaluation {
        val planner =
            FixedFoldCountPlanner(
                delegate =
                    WalkForwardSplitPlanner(
                        WalkForwardConfig(
                            minimumTrainingRows = config.minimumTrainingRows,
                            testRows = config.testRows,
                            stepRows = config.stepRows,
                            purgeMillis = config.purgeMillis,
                            embargoMillis = config.embargoMillis,
                        ),
                    ),
                requiredFoldCount = config.foldCount,
            )
        val development =
            VarianceWalkForwardExperiment(
                splitPlanner = planner,
                candidate = candidateEstimator(),
                controls = controls(),
                hacLag = config.hacLag,
            ).run(rows)
        /*
         * No development result changes this branch. The same preregistered
         * candidate and controls are fitted once and the holdout is opened.
         */
        val holdout =
            VarianceHoldoutExperiment(
                candidate = candidateEstimator(),
                controls = controls(),
                hacLag = config.hacLag,
            ).run(rows, sealedHoldout)
        val supported =
            holdout.controls.all { control ->
                holdout.candidateMetrics.meanQlikeLoss < control.metrics.meanQlikeLoss &&
                    control.comparison.meanQlikeImprovement > 0.0 &&
                    control.benjaminiHochbergPValue < config.predictiveAlpha
            }
        return HarVarianceEvaluation(
            development = development,
            holdout = holdout,
            availableDevelopmentFoldCount = planner.availableFoldCount,
            selectedDevelopmentFolds = planner.selectedFoldAudits,
            evidenceStatus =
                if (supported) {
                    "SEALED_HOLDOUT_QLIKE_SCREEN_SUPPORTED"
                } else {
                    "SEALED_HOLDOUT_QLIKE_SCREEN_NOT_SUPPORTED"
                },
        )
    }

    private fun candidateEstimator() =
        LogTargetEstimator(
            LinearEstimator(
                listOf(
                    LOG_RV_DAILY_FEATURE,
                    LOG_RV_WEEKLY_FEATURE,
                    LOG_RV_MONTHLY_FEATURE,
                ),
                intercept = true,
            ),
        )

    private fun controls(): List<NamedVarianceControl> =
        listOf(
            NamedVarianceControl(
                HISTORICAL_MEAN_CONTROL,
                HistoricalMeanEstimator(),
            ),
            NamedVarianceControl(
                PERSISTENCE_CONTROL,
                PersistenceEstimator(CURRENT_RV_FEATURE),
            ),
        )

    private fun predictionsJson(
        run: ExperimentRunRow,
        prepared: PreparedHarVarianceData,
        evaluated: HarVarianceEvaluation,
    ): JsonObject =
        buildJsonObject {
            val rowsById =
                (prepared.validationRows + prepared.sealedHoldout)
                    .associateBy(LabeledObservation::rowId)
            put("schemaVersion", PREDICTIONS_SCHEMA)
            put("runId", run.id.toString())
            put("theoryId", run.theoryId)
            put("theoryVersion", run.theoryVersion)
            put("theoryPlanHash", run.theoryPlanHash)
            put("snapshotId", run.snapshotId.toString())
            put("contractSnapshotId", prepared.snapshot.id.value)
            put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
            put("source", HYPERLIQUID_SOURCE)
            put("sourceRevision", sourceRevision)
            put("productionSource", true)
            put("realDataOnly", true)
            put("instrument", BTC_PERPETUAL_INSTRUMENT)
            put("candidateEstimator", CANDIDATE_ESTIMATOR)
            put("aggregationSemantics", aggregationSemanticsJson())
            put("rawSourceObjects", rawSourceObjectsJson(prepared.snapshot))
            put(
                "observations",
                buildJsonArray {
                    evaluated.development.observations.forEach { observation ->
                        val source = requireNotNull(rowsById[observation.rowId])
                        add(
                            forecastObservationJson(
                                phase = "DEVELOPMENT_OOS",
                                fold = observation.fold,
                                source = source,
                                actual = observation.actual,
                                candidate = observation.candidate,
                                controlForecasts = observation.controls,
                            ),
                        )
                    }
                    evaluated.holdout.observations.forEach { observation ->
                        val source = requireNotNull(rowsById[observation.rowId])
                        add(
                            forecastObservationJson(
                                phase = "SEALED_HOLDOUT",
                                fold = 0,
                                source = source,
                                actual = observation.actual,
                                candidate = observation.candidate,
                                controlForecasts = observation.controls,
                            ),
                        )
                    }
                },
            )
        }

    private fun forecastObservationJson(
        phase: String,
        fold: Int,
        source: LabeledObservation,
        actual: Double,
        candidate: Double,
        controlForecasts: Map<String, Double>,
    ): JsonObject =
        buildJsonObject {
            require(actual > 0.0 && candidate > 0.0)
            require(controlForecasts.values.all { it > 0.0 })
            put("phase", phase)
            put("fold", fold)
            put("rowId", source.rowId)
            put("decisionTimeEpochMillis", source.decisionTime.epochMillis)
            put("labelFromEpochMillis", source.labelFrom.epochMillis)
            put("labelToEpochMillis", source.labelTo.epochMillis)
            put(
                LOG_RV_DAILY_FEATURE,
                source.features.values.getValue(LOG_RV_DAILY_FEATURE),
            )
            put(
                LOG_RV_WEEKLY_FEATURE,
                source.features.values.getValue(LOG_RV_WEEKLY_FEATURE),
            )
            put(
                LOG_RV_MONTHLY_FEATURE,
                source.features.values.getValue(LOG_RV_MONTHLY_FEATURE),
            )
            put(
                "realizedVarianceAtDecision",
                source.features.values.getValue(CURRENT_RV_FEATURE),
            )
            put("actualNextDayRealizedVariance", actual)
            put("candidateForecast", candidate)
            put(
                "historicalMeanVarianceForecast",
                controlForecasts.getValue(HISTORICAL_MEAN_CONTROL),
            )
            put(
                "persistenceVarianceForecast",
                controlForecasts.getValue(PERSISTENCE_CONTROL),
            )
            put("allForecastsPositive", true)
        }

    private fun reportJson(
        run: ExperimentRunRow,
        prepared: PreparedHarVarianceData,
        evaluated: HarVarianceEvaluation,
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
            put("instrument", BTC_PERPETUAL_INSTRUMENT)
            put("sourceCandleCount", prepared.sourceCandles.size)
            put(
                "sourceFirstAvailableAtEpochMillis",
                prepared.sourceCandles.first().header.availableAt.epochMillis,
            )
            put(
                "sourceLastAvailableAtEpochMillis",
                prepared.sourceCandles.last().header.availableAt.epochMillis,
            )
            put("dailyRealizedVarianceCount", prepared.dailyVariances.size)
            put("totalLabeledRowCount", prepared.totalLabeledRows)
            put("validationRowCount", prepared.validationRows.size)
            put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
            put("sealedHoldoutRows", prepared.sealedHoldout.size)
            put(
                "sealedHoldoutFromEpochMillis",
                prepared.sealedHoldout.first().decisionTime.epochMillis,
            )
            put(
                "sealedHoldoutLastDecisionEpochMillis",
                prepared.sealedHoldout.last().decisionTime.epochMillis,
            )
            put(
                "sealedHoldoutToEpochMillis",
                prepared.sealedHoldout.last().labelTo.epochMillis,
            )
            put("sealedHoldoutEvaluated", true)
            put("singleUnconditionalHoldoutDecision", true)
            put("modelSelectionUsedHoldout", false)
            put("researcherBlind", false)
            put("crossRunHoldoutReusePrevented", false)
            put("aggregationSemantics", aggregationSemanticsJson())
            put("rawSourceObjects", rawSourceObjectsJson(prepared.snapshot))
            put(
                "validation",
                buildJsonObject {
                    put("scheme", "EXPANDING")
                    put("minimumSourceRows", config.sourceRows)
                    put("minimumHistoryMillis", config.minimumHistoryMillis)
                    put("minimumTrainingRows", config.minimumTrainingRows)
                    put("testRows", config.testRows)
                    put("stepRows", config.stepRows)
                    put("foldCount", config.foldCount)
                    put(
                        "availableDevelopmentFoldCount",
                        evaluated.availableDevelopmentFoldCount,
                    )
                    put("selectedFoldRule", "LAST_FIVE_FEASIBLE_CHRONOLOGICAL_FOLDS")
                    put(
                        "selectedFoldIndexes",
                        buildJsonArray {
                            evaluated.selectedDevelopmentFolds.forEach { add(it.index) }
                        },
                    )
                    put(
                        "selectedFolds",
                        buildJsonArray {
                            evaluated.selectedDevelopmentFolds.forEach { fold ->
                                add(
                                    buildJsonObject {
                                        put("index", fold.index)
                                        put("trainingCount", fold.trainingCount)
                                        put(
                                            "trainingFirstDecisionEpochMillis",
                                            fold.trainingFirstDecisionEpochMillis,
                                        )
                                        put(
                                            "trainingLastDecisionEpochMillis",
                                            fold.trainingLastDecisionEpochMillis,
                                        )
                                        put(
                                            "trainingLastLabelToEpochMillis",
                                            fold.trainingLastLabelToEpochMillis,
                                        )
                                        put("testCount", fold.testCount)
                                        put(
                                            "testFirstDecisionEpochMillis",
                                            fold.testFirstDecisionEpochMillis,
                                        )
                                        put(
                                            "testLastDecisionEpochMillis",
                                            fold.testLastDecisionEpochMillis,
                                        )
                                    },
                                )
                            }
                        },
                    )
                    put("holdoutFitTrainingCount", prepared.validationRows.size)
                    put(
                        "holdoutFitTrainingFirstDecisionEpochMillis",
                        prepared.validationRows.first().decisionTime.epochMillis,
                    )
                    put(
                        "holdoutFitTrainingLastDecisionEpochMillis",
                        prepared.validationRows.last().decisionTime.epochMillis,
                    )
                    put(
                        "holdoutFitTrainingLastLabelToEpochMillis",
                        prepared.validationRows.maxOf { it.labelTo.epochMillis },
                    )
                    put("purgeMillis", config.purgeMillis)
                    put("embargoMillis", config.embargoMillis)
                    put(
                        "holdoutBoundaryRule",
                        "development.labelTo <= firstHoldoutDecision - purgeMillis AND " +
                            "development.labelTo + embargoMillis <= firstHoldoutDecision",
                    )
                    put("hacLag", config.hacLag)
                    put("localFamilyCorrection", "BENJAMINI_HOCHBERG")
                    put("registeredGlobalSpaEvaluated", false)
                    put("samplingStabilityEvaluated", false)
                },
            )
            put("candidateEstimator", CANDIDATE_ESTIMATOR)
            put(
                "controls",
                buildJsonArray {
                    add(HISTORICAL_MEAN_CONTROL)
                    add(PERSISTENCE_CONTROL)
                },
            )
            put("metrics", metrics)
            put("evidenceStatus", evaluated.evidenceStatus)
            put("evidenceDecisionPhase", "SEALED_HOLDOUT")
            put("promotionStatus", PromotionStatus.BLOCKED.name)
            put("promotionReasons", promotionReasonsJson())
            put(
                "limitations",
                buildJsonArray {
                    add("Four-hour returns are a noisy realized-variance proxy")
                    add("The result is BTC-only and does not establish cross-asset validity")
                    add("Sampling stability and the registered global SPA family test were not evaluated")
                    add("The retrospective analysis was not researcher-blind")
                    add("The control plane does not prevent cross-run reuse of the same sealed holdout")
                    add(
                        "Exponentiating log-OLS without a smearing correction is the frozen geometric-scale forecast; this adaptation cannot reject HAR generally",
                    )
                },
            )
        }

    private fun metricsJson(
        evaluated: HarVarianceEvaluation,
        prepared: PreparedHarVarianceData,
    ): JsonObject =
        buildJsonObject {
            put("schemaVersion", METRICS_SCHEMA)
            put("instrument", BTC_PERPETUAL_INSTRUMENT)
            put(
                "development",
                phaseMetricsJson(
                    count = evaluated.development.observations.size,
                    foldCount =
                        evaluated.development.observations
                            .map { it.fold }
                            .distinct()
                            .size,
                    candidateMetrics = evaluated.development.candidateMetrics,
                    controls = evaluated.development.controls,
                ),
            )
            put(
                "sealedHoldout",
                phaseMetricsJson(
                    count = evaluated.holdout.observations.size,
                    foldCount = 1,
                    candidateMetrics = evaluated.holdout.candidateMetrics,
                    controls = evaluated.holdout.controls,
                ),
            )
            put("evidenceStatus", evaluated.evidenceStatus)
            put("evidenceDecisionPhase", "SEALED_HOLDOUT")
            put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
            put("sealedHoldoutRows", prepared.sealedHoldout.size)
            put("sealedHoldoutEvaluated", true)
            put("singleUnconditionalHoldoutDecision", true)
        }

    private fun phaseMetricsJson(
        count: Int,
        foldCount: Int,
        candidateMetrics: VarianceForecastMetricSet,
        controls: List<VarianceControlEvaluation>,
    ): JsonObject =
        buildJsonObject {
            put("count", count)
            put("foldCount", foldCount)
            put("candidate", varianceMetricJson(candidateMetrics))
            put(
                "controls",
                buildJsonArray {
                    controls.forEach { control ->
                        add(
                            buildJsonObject {
                                put("name", control.name)
                                put("metrics", varianceMetricJson(control.metrics))
                                put(
                                    "meanQlikeImprovement",
                                    control.comparison.meanQlikeImprovement,
                                )
                                put(
                                    "hacStandardError",
                                    control.comparison.hacStandardError,
                                )
                                put(
                                    "zStatistic",
                                    finiteOrNull(control.comparison.zStatistic),
                                )
                                put(
                                    "twoSidedPValue",
                                    control.comparison.twoSidedPValue,
                                )
                                put(
                                    "benjaminiHochbergPValue",
                                    control.benjaminiHochbergPValue,
                                )
                                put("hacLag", control.comparison.hacLag)
                            },
                        )
                    }
                },
            )
        }

    private fun varianceMetricJson(metrics: VarianceForecastMetricSet): JsonObject =
        buildJsonObject {
            put("count", metrics.count)
            put("meanQlikeLoss", metrics.meanQlikeLoss)
            put("meanAbsoluteError", metrics.meanAbsoluteError)
            put("rootMeanSquaredError", metrics.rootMeanSquaredError)
        }

    private fun aggregationSemanticsJson(): JsonObject =
        buildJsonObject {
            put("sourceSamplingMillis", FOUR_HOUR_MILLIS)
            put("returnsPerUtcDay", config.returnsPerDay)
            put(
                "dailyRealizedVariance",
                "sum of six squared consecutive four-hour log returns; the first source close is the prior endpoint",
            )
            put(LOG_RV_DAILY_FEATURE, "ln(rv_t)")
            put(
                LOG_RV_WEEKLY_FEATURE,
                "ln(arithmetic mean of daily realized variance from t-6 through t)",
            )
            put(
                LOG_RV_MONTHLY_FEATURE,
                "ln(arithmetic mean of daily realized variance from t-29 through t)",
            )
            put("target", "daily realized variance rv_t+1")
            put("decisionClock", "availableAt of the final completed four-hour candle at UTC midnight")
            put("labelEndClock", "next UTC-midnight availableAt")
            put("targetFitScale", "natural log")
            put("forecastScale", "positive variance level after exponentiation")
            put("logTargetRetransformationSmearingCorrection", false)
            put(
                "forecastInterpretation",
                "exp(log-OLS) is the frozen geometric-scale variance forecast",
            )
        }

    private fun rawSourceObjectsJson(snapshot: DataSnapshot) =
        buildJsonArray {
            snapshot.objects.forEach { objectManifest ->
                add(
                    buildJsonObject {
                        put("contentHash", objectManifest.contentHash.hex)
                        put("byteCount", objectManifest.byteCount)
                        put("rowCount", objectManifest.rowCount)
                        put("source", objectManifest.provenance.source.value)
                        put("production", objectManifest.provenance.production)
                        put("objectUri", objectManifest.uri)
                        put("requestMethod", objectManifest.provenance.request.method)
                        put("requestUri", objectManifest.provenance.request.uri)
                        put(
                            "requestParameters",
                            buildJsonObject {
                                objectManifest
                                    .provenance
                                    .request
                                    .parameters
                                    .toSortedMap()
                                    .forEach { (key, value) -> put(key, value) }
                            },
                        )
                        put(
                            "retrievedAtEpochMillis",
                            objectManifest.provenance.retrievedAt.epochMillis,
                        )
                        put(
                            "eventFromInclusiveEpochMillis",
                            objectManifest.eventTimeRange.fromInclusive.epochMillis,
                        )
                        put(
                            "eventToExclusiveEpochMillis",
                            objectManifest.eventTimeRange.toExclusive.epochMillis,
                        )
                        put(
                            "availabilityFromInclusiveEpochMillis",
                            objectManifest.availabilityTimeRange.fromInclusive.epochMillis,
                        )
                        put(
                            "availabilityToExclusiveEpochMillis",
                            objectManifest.availabilityTimeRange.toExclusive.epochMillis,
                        )
                        put("schemaVersion", objectManifest.provenance.schemaVersion)
                        put("adapterVersion", objectManifest.provenance.adapterVersion)
                    },
                )
            }
        }

    private fun promotionReasonsJson() =
        buildJsonArray {
            add("The registered HAR adaptation is forecast-only and is not eligible for paper trading")
            add("Four-hour realized variance is a noisy proxy and sampling stability was not tested")
            add("A BTC-only result cannot establish cross-asset validity")
            add("Local HAC comparisons with BH correction do not constitute the registered global SPA family test")
            add("Researchers were not blind to prior analyses over overlapping Hyperliquid history")
            add("The control plane does not prevent the same sealed period from being opened by a different run")
            add("The frozen exp(log-OLS) forecast has no retransformation smearing correction")
        }

    private fun artifactManifest(
        run: ExperimentRunRow,
        prepared: PreparedHarVarianceData,
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
            put("theoryPlanHash", expectedPlanHash)
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
        status: RunStatus,
        code: String,
        message: String,
        failedTrialParameters: JsonObject? = null,
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
                    put("theoryPlanHash", expectedPlanHash)
                    put("jobId", fence.jobId.toString())
                    put("attemptId", fence.attemptId.toString())
                    put("leaseOwner", fence.workerId)
                    put("leaseToken", fence.leaseToken.toString())
                    put("promotionStatus", PromotionStatus.BLOCKED.name)
                    put("promotionReasons", promotionReasonsJson())
                },
            metrics = null,
            failure = failure,
            promotionStatus = PromotionStatus.BLOCKED,
            trials =
                failedTrialParameters?.let { parameters ->
                    listOf(
                        TrialWrite(
                            trialNumber = 0,
                            parameters = parameters,
                            status = TrialStatus.FAILED,
                            metrics = null,
                            failure = failure,
                        ),
                    )
                }.orEmpty(),
        )
        return JobExecutionResult.Failed(
            code = code,
            message = safeMessage,
            retryable = false,
        )
    }

    private fun recovered(run: ExperimentRunRow): JobExecutionResult.Succeeded {
        require(run.theoryId == HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID) {
            "Only a HAR variance run can be recovered by this handler"
        }
        require(run.theoryVersion == THEORY_VERSION && run.theoryPlanHash == expectedPlanHash) {
            "Completed HAR variance run does not match the frozen executor plan"
        }
        require(run.promotionStatus == PromotionStatus.BLOCKED) {
            "A completed forecast-only HAR variance run must have promotion blocked"
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
            .ifBlank { "HAR variance experiment could not be completed" }
            .take(MAX_MESSAGE_CHARS)

    private data class DailyRealizedVariance(
        val decisionCandle: Candle,
        val value: Double,
    )

    private data class PreparedHarVarianceData(
        val snapshot: DataSnapshot,
        val sourceCandles: List<Candle>,
        val dailyVariances: List<DailyRealizedVariance>,
        val validationRows: List<LabeledObservation>,
        val totalLabeledRows: Int,
        val sealedHoldout: List<LabeledObservation>,
        val boundaryPurgedRows: Int,
    )

    private data class HarVarianceEvaluation(
        val development: VarianceWalkForwardExperimentResult,
        val holdout: VarianceHoldoutExperimentResult,
        val availableDevelopmentFoldCount: Int,
        val selectedDevelopmentFolds: List<FoldAudit>,
        val evidenceStatus: String,
    )

    private class FixedFoldCountPlanner(
        private val delegate: SplitPlanner,
        private val requiredFoldCount: Int,
    ) : SplitPlanner {
        var availableFoldCount: Int = 0
            private set
        var selectedFoldAudits: List<FoldAudit> = emptyList()
            private set

        override fun plan(rows: List<LabeledObservation>): List<WalkForwardFold> {
            val available =
                try {
                    delegate.plan(rows)
                } catch (exception: IllegalArgumentException) {
                    throw InsufficientRealDataException(
                        exception.message ?: "Real snapshot cannot produce a walk-forward fold",
                    )
                }
            if (available.size < requiredFoldCount) {
                throw InsufficientRealDataException(
                    "Real snapshot yields ${available.size} folds; $requiredFoldCount are required",
                )
            }
            availableFoldCount = available.size
            val selected = available.takeLast(requiredFoldCount)
            val rowsById = rows.associateBy(LabeledObservation::rowId)
            selectedFoldAudits =
                selected.map { fold ->
                    val testRows = fold.testRowIds.map { requireNotNull(rowsById[it]) }
                    FoldAudit(
                        index = fold.index,
                        trainingCount = fold.training.size,
                        trainingFirstDecisionEpochMillis =
                            fold.training.minOf { it.decisionTime.epochMillis },
                        trainingLastDecisionEpochMillis =
                            fold.training.maxOf { it.decisionTime.epochMillis },
                        trainingLastLabelToEpochMillis =
                            fold.training.maxOf { it.labelTo.epochMillis },
                        testCount = testRows.size,
                        testFirstDecisionEpochMillis =
                            testRows.minOf { it.decisionTime.epochMillis },
                        testLastDecisionEpochMillis =
                            testRows.maxOf { it.decisionTime.epochMillis },
                    )
                }
            return selected
        }
    }

    private data class FoldAudit(
        val index: Int,
        val trainingCount: Int,
        val trainingFirstDecisionEpochMillis: Long,
        val trainingLastDecisionEpochMillis: Long,
        val trainingLastLabelToEpochMillis: Long,
        val testCount: Int,
        val testFirstDecisionEpochMillis: Long,
        val testLastDecisionEpochMillis: Long,
    )

    private class InsufficientRealDataException(
        override val message: String,
    ) : IllegalArgumentException(message)

    private companion object {
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val THEORY_VERSION = "1.0.0"
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val HYPERLIQUID_INFO_URI = "https://api.hyperliquid.xyz/info"
        const val BTC_PERPETUAL_INSTRUMENT = "hyperliquid:perpetual:BTC"
        const val FOUR_HOUR_MILLIS = 14_400_000L
        const val DAY_MILLIS = 86_400_000L
        const val LOG_RV_DAILY_FEATURE = "log_rv_daily"
        const val LOG_RV_WEEKLY_FEATURE = "log_rv_weekly"
        const val LOG_RV_MONTHLY_FEATURE = "log_rv_monthly"
        const val CURRENT_RV_FEATURE = "realized_variance_t"
        const val HISTORICAL_MEAN_CONTROL = "EXPANDING_HISTORICAL_MEAN_VARIANCE"
        const val PERSISTENCE_CONTROL = "PERSISTENCE_RV_T"
        const val CANDIDATE_ESTIMATOR =
            "LOG_TARGET_OLS_INTERCEPT_LOG_RV_DAILY_WEEKLY_MONTHLY"
        const val EXPERIMENT_JOB_KIND = "EXPERIMENT_RUN"
        const val RUN_RESOURCE_TYPE = "run"
        const val PREDICTIONS_ARTIFACT_KIND = "PREDICTIONS"
        const val REPORT_ARTIFACT_KIND = "REPORT"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ENGINE_NAME = "marketlab-kotlin-btc-four-hour-log-har-variance-engine-v1"
        const val PREDICTIONS_SCHEMA = "marketlab.btc-four-hour-log-har-predictions.v1"
        const val REPORT_SCHEMA = "marketlab.btc-four-hour-log-har-report.v1"
        const val METRICS_SCHEMA = "marketlab.btc-four-hour-log-har-metrics.v1"
        const val ARTIFACT_MANIFEST_SCHEMA = "marketlab.artifact-manifest.v1"
        const val RUN_MANIFEST_SCHEMA = "marketlab.run-manifest.v1"
        const val MAX_MESSAGE_CHARS = 1_000
    }
}
