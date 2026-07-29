package dev.marketlab.coordinator

import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.data.hyperliquid.StoredHyperliquidSnapshotReader
import dev.marketlab.engine.FeatureVector
import dev.marketlab.engine.ForecastObservation
import dev.marketlab.engine.ForecastMetricSet
import dev.marketlab.engine.HistoricalMeanEstimator
import dev.marketlab.engine.HoldoutBaseline
import dev.marketlab.engine.HoldoutExperiment
import dev.marketlab.engine.HoldoutExperimentResult
import dev.marketlab.engine.LabeledObservation
import dev.marketlab.engine.LinearEstimator
import dev.marketlab.engine.MultipleTesting
import dev.marketlab.engine.PredictiveComparison
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
import kotlin.math.max
import kotlin.math.sqrt
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

internal const val HYPERLIQUID_BTC_MOMENTUM_THEORY_ID =
    "hyperliquid-btc-time-series-momentum"

internal data class MomentumExperimentConfig(
    val minimumSourceRows: Int = 3 * 365,
    val lookbackRows: Int = 365,
    val volatilityRows: Int = 60,
    val targetRows: Int = 30,
    val sealedHoldoutRows: Int = 90,
    val minimumTrainingRows: Int = 180,
    val testRows: Int = 60,
    val stepRows: Int = 60,
    val foldCount: Int = 5,
    val purgeMillis: Long = 30L * DAY_MILLIS,
    val embargoMillis: Long = 30L * DAY_MILLIS,
    val hacLag: Int = 29,
    val predictiveAlpha: Double = 0.05,
) {
    init {
        require(minimumSourceRows > 0)
        require(lookbackRows > 0)
        require(volatilityRows > 1)
        require(targetRows > 0)
        require(sealedHoldoutRows > 0)
        require(minimumTrainingRows > 2)
        require(testRows > 0 && stepRows > 0)
        require(foldCount >= 1)
        require(purgeMillis >= 0 && embargoMillis >= 0)
        require(hacLag >= 0)
        require(predictiveAlpha.isFinite() && predictiveAlpha > 0.0 && predictiveAlpha < 1.0)
    }
}

/**
 * Executes the frozen, single-instrument Hyperliquid BTC momentum adaptation.
 *
 * There is deliberately no fallback to generated observations, shorter
 * lookbacks, a different instrument, or a looser fold plan. A real snapshot
 * that cannot satisfy the registered design terminates as inconclusive.
 */
internal class MomentumExperimentJobHandler(
    private val persistence: ExperimentPersistence,
    private val snapshotReader: StoredHyperliquidSnapshotReader,
    private val artifacts: ExperimentArtifactStore,
    private val sourceRevision: String,
    private val expectedPlanHash: String,
    private val config: MomentumExperimentConfig = MomentumExperimentConfig(),
) : ExperimentJobHandler {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    init {
        require(SHA256.matches(sourceRevision)) {
            "momentum experiment source revision must be a lowercase SHA-256 digest"
        }
        require(SHA256.matches(expectedPlanHash)) {
            "momentum theory plan hash must be a lowercase SHA-256 digest"
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
                initial.theoryId != HYPERLIQUID_BTC_MOMENTUM_THEORY_ID ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_EXPERIMENT_THEORY",
                        "Momentum executor received a different theory",
                    )
                initial.theoryVersion != THEORY_VERSION ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_VERSION",
                        "Momentum executor implements only the frozen theory version $THEORY_VERSION",
                    )
                initial.theoryPlanHash != expectedPlanHash ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "THEORY_PLAN_HASH_MISMATCH",
                        "Persisted momentum plan does not match this executor",
                    )
                initial.parameters.isNotEmpty() ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_PARAMETERS",
                        "The frozen momentum adaptation has no runtime parameters",
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
                        exception.message ?: "Momentum estimation could not be completed",
                        failedTrialParameters = initial.parameters,
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.FAILED,
                        "EXPERIMENT_ENGINE_FAILED",
                        exception.message ?: "Momentum experiment engine failed",
                        failedTrialParameters = initial.parameters,
                    )
                }

            val metrics = metricsJson(evaluated, prepared)
            val predictions = predictionsJson(initial, prepared, evaluated)
            val report = reportJson(initial, prepared, evaluated, metrics)

            /*
             * Content-addressed writes make an I/O retry safe. The artifact rows
             * and terminal run transition are then committed atomically under
             * the live lease fence.
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

    private fun prepare(run: ExperimentRunRow): PreparedMomentumData {
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
            "Momentum requires exactly one immutable data requirement"
        }
        val requirement = snapshot.requirements.single()
        require(requirement.observation == ObservationKind.CANDLE) {
            "Momentum requires an immutable candle-only snapshot"
        }
        require(
            requirement.sourcePreference == listOf(dev.marketlab.contracts.DataSourceId(HYPERLIQUID_SOURCE)),
        ) {
            "Momentum requires a Hyperliquid-only source preference"
        }
        require(requirement.instrumentKinds == listOf(InstrumentKind.PERPETUAL)) {
            "Momentum requires a perpetual-only data contract"
        }
        require(
            (requirement.sampling as? Sampling.FixedDuration)?.millis == DAY_MILLIS,
        ) {
            "Momentum requires a one-day sampling contract"
        }
        require("close" in requirement.requiredFields) {
            "Momentum requires observed daily closes"
        }
        require(requirement.minimumHistoryMillis >= config.minimumSourceRows * DAY_MILLIS) {
            "Snapshot requirement declares less history than the registered theory"
        }
        require(
            snapshot.objects.all {
                it.provenance.production && it.provenance.source.value == HYPERLIQUID_SOURCE
            },
        ) {
            "Momentum requires production Hyperliquid source objects"
        }

        val candles = snapshotReader.events(snapshot).filterIsInstance<Candle>()
        require(candles.isNotEmpty()) { "Snapshot replay produced no candles" }
        require(candles.all(Candle::closed)) { "Momentum requires completed candles" }
        require(
            candles.all {
                it.header.source.value == HYPERLIQUID_SOURCE &&
                    it.header.instrument.value == BTC_PERPETUAL_INSTRUMENT
            },
        ) {
            "Momentum requires exactly the Hyperliquid BTC perpetual"
        }
        val daily = validateDailyCandles(candles)
        if (daily.size < config.minimumSourceRows) {
            throw InsufficientRealDataException(
                "Snapshot has ${daily.size} daily candles; the registered data requirement " +
                    "requires at least ${config.minimumSourceRows}",
            )
        }
        val allRows = labeledMomentumRows(daily)
        if (allRows.size <= config.sealedHoldoutRows) {
            throw InsufficientRealDataException(
                "Snapshot has ${allRows.size} labeled rows; more than " +
                    "${config.sealedHoldoutRows} are required to reserve the sealed holdout",
            )
        }
        val sealed = allRows.takeLast(config.sealedHoldoutRows)
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
        return PreparedMomentumData(
            snapshot = snapshot,
            sourceCandleCount = candles.size,
            dailyCloseCount = daily.size,
            validationRows = validationRows,
            totalLabeledRows = allRows.size,
            sealedHoldoutRows = sealed.size,
            sealedHoldout = sealed,
            boundaryPurgedRows = boundaryPurgedRows,
            sealedHoldoutFromEpochMillis = firstHoldoutDecision.epochMillis,
            sealedHoldoutToEpochMillis = sealed.last().labelTo.epochMillis,
        )
    }

    private fun validateDailyCandles(candles: List<Candle>): List<Candle> {
        val ordered =
            candles.sortedWith(
                compareBy<Candle>(
                    { it.header.exchangeTime },
                    { it.header.id.value },
                ),
            )
        require(ordered.map { it.header.id }.distinct().size == ordered.size) {
            "Candle snapshot contains duplicate event ids"
        }
        require(ordered.all { it.intervalMillis == DAY_MILLIS }) {
            "Momentum requires native one-day candles"
        }
        ordered.forEach { candle ->
            require(
                candle.header.availableAt.epochMillis ==
                    Math.addExact(candle.header.exchangeTime.epochMillis, 1L),
            ) {
                "Historical daily candle availability must be its close plus one millisecond"
            }
            require(candle.close.toBigDecimal().signum() > 0) {
                "Observed daily close must be positive"
            }
        }
        ordered.zipWithNext().forEach { (previous, next) ->
            require(
                next.header.exchangeTime.epochMillis - previous.header.exchangeTime.epochMillis ==
                    DAY_MILLIS,
            ) {
                "Daily candle snapshot has a gap or duplicate timestamp"
            }
            require(next.header.availableAt > previous.header.availableAt) {
                "Daily candle availability is not strictly chronological"
            }
        }
        return ordered
    }

    private fun labeledMomentumRows(daily: List<Candle>): List<LabeledObservation> {
        val firstDecisionIndex = max(config.lookbackRows, config.volatilityRows)
        val minimumCandleCount = firstDecisionIndex + config.targetRows + 1
        if (daily.size < minimumCandleCount) {
            throw InsufficientRealDataException(
                "Snapshot has ${daily.size} daily candles; $minimumCandleCount are required " +
                    "for one fully labeled momentum observation",
            )
        }
        val closes = daily.map { it.close.toBigDecimal().toDouble() }
        require(closes.all { it.isFinite() && it > 0.0 }) {
            "Observed daily closes are not finite and positive"
        }
        val oneDayReturns =
            (1 until closes.size).map { index ->
                finiteLogReturn(closes[index], closes[index - 1])
            }
        return (firstDecisionIndex until daily.size - config.targetRows).map { index ->
            val decisionCandle = daily[index]
            val targetCandle = daily[index + config.targetRows]
            val rowId =
                "${decisionCandle.header.instrument.value}:" +
                    decisionCandle.header.exchangeTime.epochMillis
            val trailingReturns =
                ((index - config.volatilityRows + 1)..index).map { returnEndIndex ->
                    oneDayReturns[returnEndIndex - 1]
                }
            LabeledObservation(
                rowId = rowId,
                decisionTime = decisionCandle.header.availableAt,
                labelFrom = decisionCandle.header.availableAt,
                labelTo = targetCandle.header.availableAt,
                features =
                    FeatureVector(
                        rowId = rowId,
                        values =
                            mapOf(
                                RETURN_FEATURE to
                                    finiteLogReturn(
                                        closes[index],
                                        closes[index - config.lookbackRows],
                                    ),
                                VOLATILITY_FEATURE to sampleStandardDeviation(trailingReturns),
                            ),
                    ),
                label = finiteLogReturn(closes[index + config.targetRows], closes[index]),
            )
        }
    }

    private fun evaluate(
        rows: List<LabeledObservation>,
        sealedHoldout: List<LabeledObservation>,
    ): MomentumEvaluation {
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
        val againstMean = experiment(planner, HistoricalMeanEstimator()).run(rows)
        val againstZero = experiment(planner, ZeroReturnEstimator()).run(rows)
        requireComparableRuns(againstMean, againstZero)
        val developmentAdjusted =
            MultipleTesting.benjaminiHochberg(
                listOf(
                    againstMean.comparison.twoSidedPValue,
                    againstZero.comparison.twoSidedPValue,
                ),
            )
        val developmentComparisons =
            listOf(
                baselineComparison(
                    name = HISTORICAL_MEAN_BASELINE,
                    result = againstMean,
                    adjustedPValue = developmentAdjusted[0],
                ),
                baselineComparison(
                    name = ZERO_RETURN_BASELINE,
                    result = againstZero,
                    adjustedPValue = developmentAdjusted[1],
                ),
            )
        /*
         * The holdout path is unconditional: no development metric changes the
         * model, controls, or decision to open the frozen holdout.
         */
        val holdout =
            holdoutExperiment().run(rows, sealedHoldout)
        val holdoutByName = holdout.baselines.associateBy { it.name }
        val holdoutAdjusted =
            MultipleTesting.benjaminiHochberg(
                listOf(
                    holdoutByName.getValue(HISTORICAL_MEAN_BASELINE).comparison.twoSidedPValue,
                    holdoutByName.getValue(ZERO_RETURN_BASELINE).comparison.twoSidedPValue,
                ),
            )
        val holdoutComparisons =
            listOf(
                baselineComparison(
                    result = holdoutByName.getValue(HISTORICAL_MEAN_BASELINE),
                    adjustedPValue = holdoutAdjusted[0],
                ),
                baselineComparison(
                    result = holdoutByName.getValue(ZERO_RETURN_BASELINE),
                    adjustedPValue = holdoutAdjusted[1],
                ),
            )
        val supported =
            holdoutComparisons.all {
                it.comparison.meanSquaredErrorImprovement > 0.0 &&
                    it.adjustedPValue < config.predictiveAlpha
            }
        return MomentumEvaluation(
            againstHistoricalMean = againstMean,
            againstZero = againstZero,
            developmentComparisons = developmentComparisons,
            holdout = holdout,
            holdoutComparisons = holdoutComparisons,
            evidenceStatus =
                if (supported) {
                    "FORECAST_LOSS_SCREEN_PASSED_COEFFICIENT_SIGN_NOT_INFERRED"
                } else {
                    "FORECAST_LOSS_SCREEN_NOT_SUPPORTED"
                },
        )
    }

    private fun holdoutExperiment(): HoldoutExperiment =
        HoldoutExperiment(
            candidate = LinearEstimator(listOf(RETURN_FEATURE, VOLATILITY_FEATURE), intercept = true),
            baselines =
                listOf(
                    HoldoutBaseline(HISTORICAL_MEAN_BASELINE, HistoricalMeanEstimator()),
                    HoldoutBaseline(ZERO_RETURN_BASELINE, ZeroReturnEstimator()),
                ),
            hacLag = config.hacLag,
        )

    private fun baselineComparison(
        name: String,
        result: WalkForwardExperimentResult,
        adjustedPValue: Double,
    ): BaselineComparison =
        BaselineComparison(
            name = name,
            baselineMetrics = result.baselineMetrics,
            comparison = result.comparison,
            adjustedPValue = adjustedPValue,
        )

    private fun baselineComparison(
        result: dev.marketlab.engine.HoldoutBaselineEvaluation,
        adjustedPValue: Double,
    ): BaselineComparison =
        BaselineComparison(
            name = result.name,
            baselineMetrics = result.metrics,
            comparison = result.comparison,
            adjustedPValue = adjustedPValue,
        )

    private fun experiment(
        planner: SplitPlanner,
        baseline: dev.marketlab.engine.Estimator,
    ): WalkForwardExperiment =
        WalkForwardExperiment(
            splitPlanner = planner,
            candidate = LinearEstimator(listOf(RETURN_FEATURE, VOLATILITY_FEATURE), intercept = true),
            baseline = baseline,
            hacLag = config.hacLag,
        )

    private fun requireComparableRuns(
        left: WalkForwardExperimentResult,
        right: WalkForwardExperimentResult,
    ) {
        check(left.observations.size == right.observations.size) {
            "Control comparisons emitted different sample sizes"
        }
        left.observations.zip(right.observations).forEach { (first, second) ->
            check(
                first.fold == second.fold &&
                    first.rowId == second.rowId &&
                    first.decisionTime == second.decisionTime &&
                    first.actual == second.actual &&
                    first.candidate == second.candidate,
            ) {
                "Control comparisons did not use identical folds and candidate forecasts"
            }
        }
    }

    private fun predictionsJson(
        run: ExperimentRunRow,
        prepared: PreparedMomentumData,
        evaluated: MomentumEvaluation,
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
            put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
            put("source", HYPERLIQUID_SOURCE)
            put("sourceRevision", sourceRevision)
            put("instrument", BTC_PERPETUAL_INSTRUMENT)
            put("candidateEstimator", CANDIDATE_ESTIMATOR)
            put("realDataOnly", true)
            put(
                "observations",
                buildJsonArray {
                    evaluated.againstHistoricalMean.observations.indices.forEach { index ->
                        val mean = evaluated.againstHistoricalMean.observations[index]
                        val zero = evaluated.againstZero.observations[index]
                        val source = requireNotNull(rowsById[mean.rowId])
                        add(
                            buildJsonObject {
                                put("phase", "DEVELOPMENT_OOS")
                                put("fold", mean.fold)
                                put("rowId", mean.rowId)
                                put("decisionTimeEpochMillis", mean.decisionTime.epochMillis)
                                put("labelToEpochMillis", source.labelTo.epochMillis)
                                put(
                                    RETURN_FEATURE,
                                    source.features.values.getValue(RETURN_FEATURE),
                                )
                                put(
                                    VOLATILITY_FEATURE,
                                    source.features.values.getValue(VOLATILITY_FEATURE),
                                )
                                put("actualLogReturn30d", mean.actual)
                                put("candidateForecast", mean.candidate)
                                put("historicalMeanForecast", mean.baseline)
                                put("zeroReturnForecast", zero.baseline)
                            },
                        )
                    }
                    evaluated.holdout.observations.forEach { observation ->
                        val source = requireNotNull(rowsById[observation.rowId])
                        add(
                            buildJsonObject {
                                put("phase", "SEALED_HOLDOUT")
                                put("fold", 0)
                                put("rowId", observation.rowId)
                                put(
                                    "decisionTimeEpochMillis",
                                    observation.decisionTime.epochMillis,
                                )
                                put("labelToEpochMillis", source.labelTo.epochMillis)
                                put(
                                    RETURN_FEATURE,
                                    source.features.values.getValue(RETURN_FEATURE),
                                )
                                put(
                                    VOLATILITY_FEATURE,
                                    source.features.values.getValue(VOLATILITY_FEATURE),
                                )
                                put("actualLogReturn30d", observation.actual)
                                put("candidateForecast", observation.candidate)
                                put(
                                    "historicalMeanForecast",
                                    observation.baselines.getValue(HISTORICAL_MEAN_BASELINE),
                                )
                                put(
                                    "zeroReturnForecast",
                                    observation.baselines.getValue(ZERO_RETURN_BASELINE),
                                )
                            },
                        )
                    }
                },
            )
        }

    private fun reportJson(
        run: ExperimentRunRow,
        prepared: PreparedMomentumData,
        evaluated: MomentumEvaluation,
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
            put("sourceCandleCount", prepared.sourceCandleCount)
            put("dailyCloseCount", prepared.dailyCloseCount)
            put("totalLabeledRowCount", prepared.totalLabeledRows)
            put("validationRowCount", prepared.validationRows.size)
            put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
            put("sealedHoldoutRows", prepared.sealedHoldoutRows)
            put("sealedHoldoutFromEpochMillis", prepared.sealedHoldoutFromEpochMillis)
            put("sealedHoldoutToEpochMillis", prepared.sealedHoldoutToEpochMillis)
            put("sealedHoldoutEvaluated", true)
            put("singleUnconditionalHoldoutDecision", true)
            put("modelSelectionUsedHoldout", false)
            put("researcherBlind", false)
            put("crossRunHoldoutReusePrevented", false)
            put(
                "featureSemantics",
                buildJsonObject {
                    put(RETURN_FEATURE, "ln(C_t/C_t-${config.lookbackRows})")
                    put(
                        VOLATILITY_FEATURE,
                        "sample standard deviation of ${config.volatilityRows} completed daily log returns ending at t",
                    )
                    put("target", "ln(C_t+${config.targetRows}/C_t)")
                    put("decisionClock", "completed C_t availableAt")
                    put("labelEndClock", "completed C_t+${config.targetRows} availableAt")
                },
            )
            put(
                "validation",
                buildJsonObject {
                    put("scheme", "EXPANDING")
                    put("minimumSourceRows", config.minimumSourceRows)
                    put("minimumTrainingRows", config.minimumTrainingRows)
                    put("testRows", config.testRows)
                    put("stepRows", config.stepRows)
                    put("foldCount", config.foldCount)
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
                    put("returnCoefficientSignSeparatelyInferred", false)
                },
            )
            put("candidateEstimator", CANDIDATE_ESTIMATOR)
            put(
                "controls",
                buildJsonArray {
                    add(HISTORICAL_MEAN_BASELINE)
                    add(ZERO_RETURN_BASELINE)
                    add("ALWAYS_LONG_DIRECTIONAL_DIAGNOSTIC")
                },
            )
            put("metrics", metrics)
            put("evidenceStatus", evaluated.evidenceStatus)
            put("promotionStatus", PromotionStatus.BLOCKED.name)
            put("promotionReasons", promotionReasonsJson())
        }

    private fun metricsJson(
        evaluated: MomentumEvaluation,
        prepared: PreparedMomentumData,
    ): JsonObject =
        buildJsonObject {
            put("schemaVersion", METRICS_SCHEMA)
            put("instrument", BTC_PERPETUAL_INSTRUMENT)
            put(
                "development",
                phaseMetricsJson(
                    observations = evaluated.againstHistoricalMean.observations,
                    candidateMetrics = evaluated.againstHistoricalMean.candidateMetrics,
                    comparisons = evaluated.developmentComparisons,
                ),
            )
            put(
                "sealedHoldout",
                phaseMetricsJson(
                    observations =
                        evaluated.holdout.observations.map {
                            ForecastObservation(
                                fold = 0,
                                rowId = it.rowId,
                                decisionTime = it.decisionTime,
                                actual = it.actual,
                                candidate = it.candidate,
                                baseline =
                                    it.baselines.getValue(HISTORICAL_MEAN_BASELINE),
                            )
                        },
                    candidateMetrics = evaluated.holdout.candidateMetrics,
                    comparisons = evaluated.holdoutComparisons,
                ),
            )
            put("evidenceStatus", evaluated.evidenceStatus)
            put("evidenceDecisionPhase", "SEALED_HOLDOUT")
            put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
            put("sealedHoldoutRows", prepared.sealedHoldoutRows)
            put("sealedHoldoutEvaluated", true)
        }

    private fun phaseMetricsJson(
        observations: List<ForecastObservation>,
        candidateMetrics: ForecastMetricSet,
        comparisons: List<BaselineComparison>,
    ): JsonObject =
        buildJsonObject {
            put("count", observations.size)
            put("foldCount", observations.map(ForecastObservation::fold).distinct().size)
            put("candidate", forecastMetricJson(candidateMetrics))
            put(
                "baselines",
                buildJsonArray {
                    comparisons.forEach { comparison ->
                        add(
                            buildJsonObject {
                                put("name", comparison.name)
                                put("metrics", forecastMetricJson(comparison.baselineMetrics))
                                put(
                                    "meanSquaredErrorImprovement",
                                    comparison.comparison.meanSquaredErrorImprovement,
                                )
                                put(
                                    "hacStandardError",
                                    comparison.comparison.hacStandardError,
                                )
                                put(
                                    "zStatistic",
                                    finiteOrNull(comparison.comparison.zStatistic),
                                )
                                put(
                                    "twoSidedPValue",
                                    comparison.comparison.twoSidedPValue,
                                )
                                put("benjaminiHochbergPValue", comparison.adjustedPValue)
                                put("hacLag", comparison.comparison.hacLag)
                            },
                        )
                    }
                },
            )
            put(
                "alwaysLongDirectionalDiagnostic",
                buildJsonObject {
                    put(
                        "positiveTargetFraction",
                        observations.count { it.actual > 0.0 }.toDouble() / observations.size,
                    )
                    put("representsExecutionBacktest", false)
                    put("overlappingTargets", config.targetRows > 1)
                },
            )
        }

    private fun forecastMetricJson(metrics: ForecastMetricSet): JsonObject =
        buildJsonObject {
            put("count", metrics.count)
            put("meanAbsoluteError", metrics.meanAbsoluteError)
            put("rootMeanSquaredError", metrics.rootMeanSquaredError)
            put("directionalAccuracy", metrics.directionalAccuracy)
        }

    private fun promotionReasonsJson() =
        buildJsonArray {
            add("The registered adaptation is forecast-only and is not eligible for paper trading")
            add("Local HAC comparisons with BH correction do not constitute the registered global SPA family test")
            add("The return_365d coefficient sign was not separately subjected to dependence-aware inference")
            add("Researchers were not blind to prior analyses over overlapping Hyperliquid history")
            add("The control plane does not prevent the same sealed period from being opened by a different run")
        }

    private fun artifactManifest(
        run: ExperimentRunRow,
        prepared: PreparedMomentumData,
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
        require(run.theoryId == HYPERLIQUID_BTC_MOMENTUM_THEORY_ID) {
            "Only a momentum run can be recovered by the momentum handler"
        }
        require(run.theoryVersion == THEORY_VERSION && run.theoryPlanHash == expectedPlanHash) {
            "Completed momentum run does not match the frozen executor plan"
        }
        require(run.promotionStatus == PromotionStatus.BLOCKED) {
            "A completed forecast-only momentum run must have promotion blocked"
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

    private fun finiteLogReturn(
        ending: Double,
        beginning: Double,
    ): Double {
        val value = ln(ending / beginning)
        require(value.isFinite()) { "Observed candle return is not finite" }
        return value
    }

    private fun sampleStandardDeviation(values: List<Double>): Double {
        require(values.size > 1 && values.all(Double::isFinite))
        val mean = values.average()
        val variance = values.sumOf { value -> (value - mean) * (value - mean) } / (values.size - 1)
        val result = sqrt(variance.coerceAtLeast(0.0))
        require(result.isFinite())
        return result
    }

    private fun finiteOrNull(value: Double): JsonElement =
        if (value.isFinite()) JsonPrimitive(value) else JsonNull

    private fun sanitize(message: String): String =
        message
            .replace(CONTROL_CHARACTERS, " ")
            .trim()
            .ifBlank { "Momentum experiment could not be completed" }
            .take(MAX_MESSAGE_CHARS)

    private data class PreparedMomentumData(
        val snapshot: DataSnapshot,
        val sourceCandleCount: Int,
        val dailyCloseCount: Int,
        val validationRows: List<LabeledObservation>,
        val totalLabeledRows: Int,
        val sealedHoldoutRows: Int,
        val sealedHoldout: List<LabeledObservation>,
        val boundaryPurgedRows: Int,
        val sealedHoldoutFromEpochMillis: Long,
        val sealedHoldoutToEpochMillis: Long,
    )

    private data class BaselineComparison(
        val name: String,
        val baselineMetrics: ForecastMetricSet,
        val comparison: PredictiveComparison,
        val adjustedPValue: Double,
    )

    private data class MomentumEvaluation(
        val againstHistoricalMean: WalkForwardExperimentResult,
        val againstZero: WalkForwardExperimentResult,
        val developmentComparisons: List<BaselineComparison>,
        val holdout: HoldoutExperimentResult,
        val holdoutComparisons: List<BaselineComparison>,
        val evidenceStatus: String,
    )

    private class FixedFoldCountPlanner(
        private val delegate: SplitPlanner,
        private val requiredFoldCount: Int,
    ) : SplitPlanner {
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
            return available.takeLast(requiredFoldCount)
        }
    }

    private class InsufficientRealDataException(
        override val message: String,
    ) : IllegalArgumentException(message)

    private companion object {
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val THEORY_VERSION = "1.0.0"
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val BTC_PERPETUAL_INSTRUMENT = "hyperliquid:perpetual:BTC"
        const val RETURN_FEATURE = "return_365d"
        const val VOLATILITY_FEATURE = "volatility_60d"
        const val HISTORICAL_MEAN_BASELINE = "EXPANDING_HISTORICAL_MEAN"
        const val ZERO_RETURN_BASELINE = "ZERO_RETURN_RANDOM_WALK"
        const val CANDIDATE_ESTIMATOR = "OLS_INTERCEPT_RETURN_365D_VOLATILITY_60D"
        const val EXPERIMENT_JOB_KIND = "EXPERIMENT_RUN"
        const val RUN_RESOURCE_TYPE = "run"
        const val PREDICTIONS_ARTIFACT_KIND = "PREDICTIONS"
        const val REPORT_ARTIFACT_KIND = "REPORT"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ENGINE_NAME = "marketlab-kotlin-btc-momentum-engine-v1"
        const val PREDICTIONS_SCHEMA = "marketlab.btc-momentum-predictions.v1"
        const val REPORT_SCHEMA = "marketlab.btc-momentum-report.v1"
        const val METRICS_SCHEMA = "marketlab.btc-momentum-metrics.v1"
        const val ARTIFACT_MANIFEST_SCHEMA = "marketlab.artifact-manifest.v1"
        const val RUN_MANIFEST_SCHEMA = "marketlab.btc-momentum-run-manifest.v1"
        const val MAX_MESSAGE_CHARS = 1_024
    }
}

private const val DAY_MILLIS = 86_400_000L
