package dev.marketlab.coordinator

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.data.hyperliquid.StoredHyperliquidSnapshotReader
import dev.marketlab.engine.Estimator
import dev.marketlab.engine.FeatureVector
import dev.marketlab.engine.ForecastMetricSet
import dev.marketlab.engine.ForecastObservation
import dev.marketlab.engine.HistoricalMeanEstimator
import dev.marketlab.engine.HoldoutBaseline
import dev.marketlab.engine.HoldoutExperiment
import dev.marketlab.engine.HoldoutExperimentResult
import dev.marketlab.engine.LabeledObservation
import dev.marketlab.engine.LinearEstimator
import dev.marketlab.engine.LinearFittedEstimator
import dev.marketlab.engine.MultipleTesting
import dev.marketlab.engine.PersistenceEstimator
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

internal const val HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID =
    "hyperliquid-btc-hourly-return-reversal"
private const val REVERSAL_HOUR_MILLIS = 3_600_000L
private val REVERSAL_SHA256 = Regex("[0-9a-f]{64}")

internal data class HourlyReversalExperimentConfig(
    val sourceRows: Int = 4_800,
    val expectedLabeledRows: Int = 4_798,
    val sealedHoldoutRows: Int = 720,
    val expectedValidationRows: Int = 4_077,
    val expectedBoundaryPurgedRows: Int = 1,
    val minimumTrainingRows: Int = 2_160,
    val testRows: Int = 168,
    val stepRows: Int = 168,
    val foldCount: Int = 11,
    val purgeMillis: Long = REVERSAL_HOUR_MILLIS,
    val embargoMillis: Long = REVERSAL_HOUR_MILLIS,
    val hacLag: Int = 24,
    val predictiveAlpha: Double = 0.05,
    val expectedSnapshotManifestHash: String =
        "3307c6b61fd76bd44175b2a51b994c5c9b6c9f5cf40a546e95afa3cf72c135da",
    val expectedRawContentHash: String =
        "f00395c84d764f1a31968563fdec25ee7dc7913362141ee467f18f9cb7446b79",
    val expectedRequestBody: String =
        "{\"req\":{\"coin\":\"BTC\",\"endTime\":1785207599999,\"interval\":\"1h\"," +
            "\"startTime\":1767927600000},\"type\":\"candleSnapshot\"}",
    val expectedFirstAvailableAtEpochMillis: Long = 1_767_931_200_000L,
    val expectedLastAvailableAtEpochMillis: Long = 1_785_207_600_000L,
    val firstHoldoutDecisionEpochMillis: Long = 1_782_615_600_000L,
    val lastHoldoutDecisionEpochMillis: Long = 1_785_204_000_000L,
    val holdoutLabelToEpochMillis: Long = 1_785_207_600_000L,
) {
    init {
        require(sourceRows > 3 && expectedLabeledRows == sourceRows - 2)
        require(sealedHoldoutRows > 0 && sealedHoldoutRows < expectedLabeledRows)
        require(expectedValidationRows > minimumTrainingRows + testRows)
        require(expectedBoundaryPurgedRows >= 0)
        require(minimumTrainingRows > 2 && testRows > 0 && stepRows > 0 && foldCount > 0)
        require(purgeMillis >= 0 && embargoMillis >= 0 && hacLag >= 0)
        require(predictiveAlpha.isFinite() && predictiveAlpha > 0.0 && predictiveAlpha < 1.0)
        require(REVERSAL_SHA256.matches(expectedSnapshotManifestHash))
        require(REVERSAL_SHA256.matches(expectedRawContentHash))
        require(expectedFirstAvailableAtEpochMillis < expectedLastAvailableAtEpochMillis)
        require(firstHoldoutDecisionEpochMillis <= lastHoldoutDecisionEpochMillis)
        require(lastHoldoutDecisionEpochMillis < holdoutLabelToEpochMillis)
    }
}

/**
 * Executes the frozen candle-only BTC hourly reversal adaptation.
 *
 * The executor accepts one exact production object. It has no generated-data,
 * alternate-date, alternate-interval, or parameter fallback.
 */
internal class HourlyReversalExperimentJobHandler(
    private val persistence: ExperimentPersistence,
    private val snapshotReader: StoredHyperliquidSnapshotReader,
    private val artifacts: ExperimentArtifactStore,
    private val sourceRevision: String,
    private val expectedPlanHash: String,
    private val config: HourlyReversalExperimentConfig = HourlyReversalExperimentConfig(),
) : ExperimentJobHandler {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    init {
        require(REVERSAL_SHA256.matches(sourceRevision))
        require(REVERSAL_SHA256.matches(expectedPlanHash))
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
                initial.theoryId != HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_EXPERIMENT_THEORY",
                        "Hourly reversal executor received a different theory",
                    )
                initial.theoryVersion != THEORY_VERSION ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_VERSION",
                        "Hourly reversal executor supports only version $THEORY_VERSION",
                    )
                initial.theoryPlanHash != expectedPlanHash ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "THEORY_PLAN_HASH_MISMATCH",
                        "Persisted hourly reversal plan does not match this executor",
                    )
                initial.parameters.isNotEmpty() ->
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.REJECTED,
                        "UNSUPPORTED_THEORY_PARAMETERS",
                        "The frozen hourly reversal adaptation has no runtime parameters",
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
                } catch (exception: IllegalArgumentException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.INCONCLUSIVE,
                        "EXPERIMENT_ESTIMATION_INVALID",
                        exception.message ?: "Hourly reversal estimation could not be completed",
                        failedTrialParameters = initial.parameters,
                    )
                } catch (exception: IllegalStateException) {
                    return@withContext terminalFailure(
                        fence,
                        RunStatus.FAILED,
                        "EXPERIMENT_ENGINE_FAILED",
                        exception.message ?: "Hourly reversal engine failed",
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
                    put("realDataOnly", true)
                    put("productionSource", true)
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

    private fun prepare(run: ExperimentRunRow): PreparedHourlyReversalData {
        val snapshotRow =
            persistence.getSnapshot(run.snapshotId)
                ?: throw IllegalArgumentException("Experiment snapshot does not exist")
        require(snapshotRow.status == SnapshotStatus.READY) {
            "Experiment snapshot is not READY"
        }
        require(snapshotRow.fatalFindingCount == 0) {
            "Experiment snapshot has fatal data-quality findings"
        }
        require(snapshotRow.manifestHash == config.expectedSnapshotManifestHash) {
            "Hourly reversal requires the frozen snapshot manifest"
        }
        val contractElement =
            snapshotRow.metadata["contractSnapshot"]
                ?: throw IllegalArgumentException("Snapshot metadata lacks its immutable contract")
        val snapshot = json.decodeFromJsonElement(DataSnapshot.serializer(), contractElement)
        require(snapshot.manifestHash.hex == snapshotRow.manifestHash)
        require(snapshot.objects.size == snapshotRow.objectCount)
        require(
            snapshotRow.metadata["contractSnapshotId"]?.jsonPrimitive?.content == snapshot.id.value,
        )
        require(snapshot.requirements.size == 1) {
            "Hourly reversal requires exactly one immutable data requirement"
        }
        val requirement = snapshot.requirements.single()
        require(requirement.observation == ObservationKind.CANDLE)
        require(requirement.sourcePreference == listOf(DataSourceId(HYPERLIQUID_SOURCE)))
        require(requirement.instrumentKinds == listOf(InstrumentKind.PERPETUAL))
        require((requirement.sampling as? Sampling.FixedDuration)?.millis == REVERSAL_HOUR_MILLIS)
        require("close" in requirement.requiredFields)
        require(requirement.minimumHistoryMillis >= config.sourceRows * REVERSAL_HOUR_MILLIS)
        require(snapshot.objects.size == 1) {
            "Hourly reversal requires exactly one immutable raw source object"
        }
        val rawObject = snapshot.objects.single()
        require(rawObject.contentHash.hex == config.expectedRawContentHash) {
            "Hourly reversal raw-object hash differs from the frozen source"
        }
        require(rawObject.rowCount == config.sourceRows.toLong())
        require(
            rawObject.provenance.production &&
                rawObject.provenance.source.value == HYPERLIQUID_SOURCE,
        )
        require(rawObject.provenance.request.method == "POST")
        require(rawObject.provenance.request.uri == HYPERLIQUID_INFO_URI)
        require(
            rawObject.provenance.request.parameters ==
                mapOf("body" to config.expectedRequestBody),
        ) {
            "Hourly reversal raw object has different canonical request parameters"
        }

        val candles = snapshotReader.events(snapshot).filterIsInstance<Candle>()
        require(candles.size == config.sourceRows) {
            "Snapshot replay emitted ${candles.size} candles; ${config.sourceRows} are required"
        }
        require(candles.all(Candle::closed))
        require(
            candles.all {
                it.header.source.value == HYPERLIQUID_SOURCE &&
                    it.header.instrument.value == BTC_PERPETUAL_INSTRUMENT
            },
        )
        val hourly = validateHourlyCandles(candles)
        val allRows = labeledRows(hourly)
        require(allRows.size == config.expectedLabeledRows)
        val sealed = allRows.takeLast(config.sealedHoldoutRows)
        require(sealed.first().decisionTime.epochMillis == config.firstHoldoutDecisionEpochMillis)
        require(sealed.last().decisionTime.epochMillis == config.lastHoldoutDecisionEpochMillis)
        require(sealed.last().labelTo.epochMillis == config.holdoutLabelToEpochMillis)
        val firstHoldoutDecision = sealed.first().decisionTime
        val developmentCandidates = allRows.dropLast(config.sealedHoldoutRows)
        val validationRows =
            developmentCandidates.filter { row ->
                row.labelTo.epochMillis <=
                    Math.subtractExact(firstHoldoutDecision.epochMillis, config.purgeMillis) &&
                    Math.addExact(row.labelTo.epochMillis, config.embargoMillis) <=
                    firstHoldoutDecision.epochMillis
            }
        val boundaryPurgedRows = developmentCandidates.size - validationRows.size
        require(validationRows.size == config.expectedValidationRows) {
            "Hourly reversal boundary rules produced ${validationRows.size} development rows; " +
                "${config.expectedValidationRows} are required"
        }
        require(boundaryPurgedRows == config.expectedBoundaryPurgedRows)
        return PreparedHourlyReversalData(
            snapshot = snapshot,
            sourceCandleCount = hourly.size,
            validationRows = validationRows,
            totalLabeledRows = allRows.size,
            sealedHoldout = sealed,
            boundaryPurgedRows = boundaryPurgedRows,
            sealedHoldoutFromEpochMillis = sealed.first().decisionTime.epochMillis,
            sealedHoldoutLastDecisionEpochMillis = sealed.last().decisionTime.epochMillis,
            sealedHoldoutToEpochMillis = sealed.last().labelTo.epochMillis,
        )
    }

    private fun validateHourlyCandles(candles: List<Candle>): List<Candle> {
        val ordered =
            candles.sortedWith(compareBy<Candle>({ it.header.exchangeTime }, { it.header.id.value }))
        require(ordered.map { it.header.id }.distinct().size == ordered.size)
        require(ordered.all { it.intervalMillis == REVERSAL_HOUR_MILLIS })
        require(
            ordered.first().header.availableAt.epochMillis ==
                config.expectedFirstAvailableAtEpochMillis,
        )
        require(
            ordered.last().header.availableAt.epochMillis ==
                config.expectedLastAvailableAtEpochMillis,
        )
        ordered.forEach { candle ->
            require(
                candle.header.availableAt.epochMillis ==
                    Math.addExact(candle.header.exchangeTime.epochMillis, 1L),
            )
            require(candle.close.toBigDecimal().signum() > 0)
            require(candle.tradeCount > 0)
        }
        ordered.zipWithNext().forEach { (previous, next) ->
            require(
                next.header.exchangeTime.epochMillis - previous.header.exchangeTime.epochMillis ==
                    REVERSAL_HOUR_MILLIS,
            ) {
                "Hourly candle snapshot has a gap or duplicate timestamp"
            }
            require(next.header.availableAt > previous.header.availableAt)
        }
        return ordered
    }

    private fun labeledRows(hourly: List<Candle>): List<LabeledObservation> {
        val closes = hourly.map { it.close.toBigDecimal().toDouble() }
        require(closes.all { it.isFinite() && it > 0.0 })
        return (1 until hourly.lastIndex).map { index ->
            val decision = hourly[index]
            val target = hourly[index + 1]
            val rowId =
                "${decision.header.instrument.value}:${decision.header.exchangeTime.epochMillis}"
            LabeledObservation(
                rowId = rowId,
                decisionTime = decision.header.availableAt,
                labelFrom = decision.header.availableAt,
                labelTo = target.header.availableAt,
                features =
                    FeatureVector(
                        rowId = rowId,
                        values =
                            mapOf(
                                RETURN_FEATURE to finiteLogReturn(closes[index], closes[index - 1]),
                            ),
                    ),
                label = finiteLogReturn(closes[index + 1], closes[index]),
            )
        }
    }

    private fun evaluate(
        rows: List<LabeledObservation>,
        sealedHoldout: List<LabeledObservation>,
    ): HourlyReversalEvaluation {
        val planner =
            ExactFoldCountPlanner(
                WalkForwardSplitPlanner(
                    WalkForwardConfig(
                        minimumTrainingRows = config.minimumTrainingRows,
                        testRows = config.testRows,
                        stepRows = config.stepRows,
                        purgeMillis = config.purgeMillis,
                        embargoMillis = config.embargoMillis,
                    ),
                ),
                config.foldCount,
            )
        val folds = planner.plan(rows)
        val developmentCoefficients = folds.map { coefficients(it.training) }
        val againstMean = experiment(planner, HistoricalMeanEstimator()).run(rows)
        val againstZero = experiment(planner, ZeroReturnEstimator()).run(rows)
        val againstPersistence =
            experiment(planner, PersistenceEstimator(RETURN_FEATURE)).run(rows)
        requireComparableRuns(againstMean, againstZero)
        requireComparableRuns(againstMean, againstPersistence)
        val developmentRaw =
            listOf(
                againstMean.comparison.twoSidedPValue,
                againstZero.comparison.twoSidedPValue,
                againstPersistence.comparison.twoSidedPValue,
            )
        val developmentAdjusted = MultipleTesting.benjaminiHochberg(developmentRaw)
        val developmentComparisons =
            listOf(
                baselineComparison(HISTORICAL_MEAN_BASELINE, againstMean, developmentAdjusted[0]),
                baselineComparison(ZERO_RETURN_BASELINE, againstZero, developmentAdjusted[1]),
                baselineComparison(PERSISTENCE_BASELINE, againstPersistence, developmentAdjusted[2]),
            )

        /*
         * The final candidate and all control forecast vectors are constructed
         * before HoldoutExperiment opens any holdout label.
         */
        val holdoutCoefficients = coefficients(rows)
        val holdout =
            HoldoutExperiment(
                candidate = candidate(),
                baselines =
                    listOf(
                        HoldoutBaseline(HISTORICAL_MEAN_BASELINE, HistoricalMeanEstimator()),
                        HoldoutBaseline(ZERO_RETURN_BASELINE, ZeroReturnEstimator()),
                        HoldoutBaseline(PERSISTENCE_BASELINE, PersistenceEstimator(RETURN_FEATURE)),
                    ),
                hacLag = config.hacLag,
            ).run(rows, sealedHoldout)
        val holdoutByName = holdout.baselines.associateBy { it.name }
        val holdoutOrder =
            listOf(HISTORICAL_MEAN_BASELINE, ZERO_RETURN_BASELINE, PERSISTENCE_BASELINE)
        val holdoutAdjusted =
            MultipleTesting.benjaminiHochberg(
                holdoutOrder.map { holdoutByName.getValue(it).comparison.twoSidedPValue },
            )
        val holdoutComparisons =
            holdoutOrder.mapIndexed { index, name ->
                baselineComparison(holdoutByName.getValue(name), holdoutAdjusted[index])
            }
        val coefficientsNegative =
            developmentCoefficients.all { it.slope < 0.0 } && holdoutCoefficients.slope < 0.0
        val supported =
            coefficientsNegative &&
                holdoutComparisons.all {
                    it.comparison.meanSquaredErrorImprovement > 0.0 &&
                        it.adjustedPValue < config.predictiveAlpha
                }
        return HourlyReversalEvaluation(
            againstHistoricalMean = againstMean,
            againstZero = againstZero,
            againstPersistence = againstPersistence,
            developmentComparisons = developmentComparisons,
            developmentCoefficients = developmentCoefficients,
            holdout = holdout,
            holdoutComparisons = holdoutComparisons,
            holdoutCoefficients = holdoutCoefficients,
            evidenceStatus =
                if (supported) {
                    "SEALED_HOLDOUT_HOURLY_REVERSAL_SUPPORTED"
                } else {
                    "SEALED_HOLDOUT_HOURLY_REVERSAL_NOT_SUPPORTED"
                },
        )
    }

    private fun candidate(): LinearEstimator =
        LinearEstimator(listOf(RETURN_FEATURE), intercept = true)

    private fun coefficients(rows: List<LabeledObservation>): Coefficients {
        val fitted = candidate().fit(rows) as LinearFittedEstimator
        require(fitted.coefficients.size == 2)
        return Coefficients(
            intercept = fitted.coefficients[0],
            slope = fitted.coefficients[1],
        )
    }

    private fun experiment(
        planner: SplitPlanner,
        baseline: Estimator,
    ): WalkForwardExperiment =
        WalkForwardExperiment(
            splitPlanner = planner,
            candidate = candidate(),
            baseline = baseline,
            hacLag = config.hacLag,
        )

    private fun baselineComparison(
        name: String,
        result: WalkForwardExperimentResult,
        adjustedPValue: Double,
    ) = BaselineComparison(
        name,
        result.baselineMetrics,
        result.comparison,
        adjustedPValue,
    )

    private fun baselineComparison(
        result: dev.marketlab.engine.HoldoutBaselineEvaluation,
        adjustedPValue: Double,
    ) = BaselineComparison(
        result.name,
        result.metrics,
        result.comparison,
        adjustedPValue,
    )

    private fun requireComparableRuns(
        left: WalkForwardExperimentResult,
        right: WalkForwardExperimentResult,
    ) {
        check(left.observations.size == right.observations.size)
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
        prepared: PreparedHourlyReversalData,
        evaluated: HourlyReversalEvaluation,
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
            put("snapshotManifestHash", prepared.snapshot.manifestHash.hex)
            put("sourceRevision", sourceRevision)
            put("source", HYPERLIQUID_SOURCE)
            put("instrument", BTC_PERPETUAL_INSTRUMENT)
            put("realDataOnly", true)
            put("productionSource", true)
            put("candidateEstimator", CANDIDATE_ESTIMATOR)
            put("rawSourceObjects", rawSourceObjectsJson(prepared.snapshot))
            put(
                "observations",
                buildJsonArray {
                    evaluated.againstHistoricalMean.observations.indices.forEach { index ->
                        val mean = evaluated.againstHistoricalMean.observations[index]
                        val zero = evaluated.againstZero.observations[index]
                        val persistence = evaluated.againstPersistence.observations[index]
                        val source = rowsById.getValue(mean.rowId)
                        add(
                            observationJson(
                                phase = "DEVELOPMENT_OOS",
                                fold = mean.fold,
                                source = source,
                                actual = mean.actual,
                                candidateForecast = mean.candidate,
                                historicalMeanForecast = mean.baseline,
                                zeroReturnForecast = zero.baseline,
                                persistenceForecast = persistence.baseline,
                            ),
                        )
                    }
                    evaluated.holdout.observations.forEach { observation ->
                        add(
                            observationJson(
                                phase = "SEALED_HOLDOUT",
                                fold = 0,
                                source = rowsById.getValue(observation.rowId),
                                actual = observation.actual,
                                candidateForecast = observation.candidate,
                                historicalMeanForecast =
                                    observation.baselines.getValue(HISTORICAL_MEAN_BASELINE),
                                zeroReturnForecast =
                                    observation.baselines.getValue(ZERO_RETURN_BASELINE),
                                persistenceForecast =
                                    observation.baselines.getValue(PERSISTENCE_BASELINE),
                            ),
                        )
                    }
                },
            )
        }

    private fun observationJson(
        phase: String,
        fold: Int,
        source: LabeledObservation,
        actual: Double,
        candidateForecast: Double,
        historicalMeanForecast: Double,
        zeroReturnForecast: Double,
        persistenceForecast: Double,
    ): JsonObject =
        buildJsonObject {
            put("phase", phase)
            put("fold", fold)
            put("rowId", source.rowId)
            put("decisionTimeEpochMillis", source.decisionTime.epochMillis)
            put("labelFromEpochMillis", source.labelFrom.epochMillis)
            put("labelToEpochMillis", source.labelTo.epochMillis)
            put(RETURN_FEATURE, source.features.values.getValue(RETURN_FEATURE))
            put("actualNextHourLogReturn", actual)
            put("candidateForecast", candidateForecast)
            put("historicalMeanForecast", historicalMeanForecast)
            put("zeroReturnForecast", zeroReturnForecast)
            put("positiveReturnPersistenceForecast", persistenceForecast)
        }

    private fun reportJson(
        run: ExperimentRunRow,
        prepared: PreparedHourlyReversalData,
        evaluated: HourlyReversalEvaluation,
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
            put("totalLabeledRowCount", prepared.totalLabeledRows)
            put("validationRowCount", prepared.validationRows.size)
            put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
            put("sealedHoldoutRows", prepared.sealedHoldout.size)
            put("sealedHoldoutFromEpochMillis", prepared.sealedHoldoutFromEpochMillis)
            put(
                "sealedHoldoutLastDecisionEpochMillis",
                prepared.sealedHoldoutLastDecisionEpochMillis,
            )
            put("sealedHoldoutToEpochMillis", prepared.sealedHoldoutToEpochMillis)
            put("sealedHoldoutEvaluated", true)
            put("singleUnconditionalHoldoutDecision", true)
            put("modelSelectionUsedHoldout", false)
            put("researcherBlind", false)
            put("crossRunHoldoutReusePrevented", false)
            put(
                "featureSemantics",
                buildJsonObject {
                    put(RETURN_FEATURE, "ln(C_t/C_t-1) from completed native one-hour candles")
                    put("target", "ln(C_t+1/C_t)")
                    put("decisionClock", "completed C_t availableAt")
                    put("labelEndClock", "completed C_t+1 availableAt")
                },
            )
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
                    put("localFamilyCorrection", "BENJAMINI_HOCHBERG")
                    put("comparisonCount", 3)
                    put("registeredGlobalSpaEvaluated", false)
                },
            )
            put(
                "fittedCoefficients",
                buildJsonObject {
                    put(
                        "development",
                        buildJsonArray {
                            evaluated.developmentCoefficients.forEachIndexed { index, fit ->
                                add(
                                    buildJsonObject {
                                        put("fold", index)
                                        put("intercept", fit.intercept)
                                        put("return_1h", fit.slope)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "holdoutFit",
                        buildJsonObject {
                            put("intercept", evaluated.holdoutCoefficients.intercept)
                            put("return_1h", evaluated.holdoutCoefficients.slope)
                        },
                    )
                    put(
                        "allReversalSlopesNegative",
                        evaluated.developmentCoefficients.all { it.slope < 0.0 } &&
                            evaluated.holdoutCoefficients.slope < 0.0,
                    )
                },
            )
            put("candidateEstimator", CANDIDATE_ESTIMATOR)
            put(
                "controls",
                buildJsonArray {
                    add(HISTORICAL_MEAN_BASELINE)
                    add(ZERO_RETURN_BASELINE)
                    add(PERSISTENCE_BASELINE)
                },
            )
            put("rawSourceObjects", rawSourceObjectsJson(prepared.snapshot))
            put("metrics", metrics)
            put("evidenceStatus", evaluated.evidenceStatus)
            put("promotionStatus", PromotionStatus.BLOCKED.name)
            put("promotionReasons", promotionReasonsJson())
            put(
                "limitations",
                buildJsonArray {
                    add("The candle-only screen cannot identify the parent theory's liquidity mechanism")
                    add("The result is BTC-only and retrospective")
                    add("No spread, depth, fees, latency, capacity, funding, or execution was evaluated")
                    add("Local HAC/BH comparisons do not implement the registered global SPA family test")
                    add("Cross-run reuse of this sealed period is not prevented by the control plane")
                },
            )
        }

    private fun metricsJson(
        evaluated: HourlyReversalEvaluation,
        prepared: PreparedHourlyReversalData,
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
                                baseline = it.baselines.getValue(HISTORICAL_MEAN_BASELINE),
                            )
                        },
                    candidateMetrics = evaluated.holdout.candidateMetrics,
                    comparisons = evaluated.holdoutComparisons,
                ),
            )
            put(
                "allReversalSlopesNegative",
                evaluated.developmentCoefficients.all { it.slope < 0.0 } &&
                    evaluated.holdoutCoefficients.slope < 0.0,
            )
            put("evidenceStatus", evaluated.evidenceStatus)
            put("evidenceDecisionPhase", "SEALED_HOLDOUT")
            put("holdoutBoundaryPurgedRows", prepared.boundaryPurgedRows)
            put("sealedHoldoutRows", prepared.sealedHoldout.size)
            put("sealedHoldoutEvaluated", true)
            put("singleUnconditionalHoldoutDecision", true)
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
                "controls",
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
                                put("hacStandardError", comparison.comparison.hacStandardError)
                                put("zStatistic", finiteOrNull(comparison.comparison.zStatistic))
                                put("twoSidedPValue", comparison.comparison.twoSidedPValue)
                                put("benjaminiHochbergPValue", comparison.adjustedPValue)
                                put("hacLag", comparison.comparison.hacLag)
                            },
                        )
                    }
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

    private fun rawSourceObjectsJson(snapshot: DataSnapshot) =
        buildJsonArray {
            snapshot.objects.forEach { raw ->
                add(
                    buildJsonObject {
                        put("contentHash", raw.contentHash.hex)
                        put("objectUri", raw.uri)
                        put("rowCount", raw.rowCount)
                        put("byteCount", raw.byteCount)
                        put("source", raw.provenance.source.value)
                        put("production", raw.provenance.production)
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
                        put(
                            "eventFromInclusiveEpochMillis",
                            raw.eventTimeRange.fromInclusive.epochMillis,
                        )
                        put(
                            "eventToExclusiveEpochMillis",
                            raw.eventTimeRange.toExclusive.epochMillis,
                        )
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
            add("The hourly reversal adaptation is forecast-only and paper-ineligible")
            add("The candle-only screen cannot identify liquidity-conditioned reversal")
            add("Historical execution costs and observed-book replay are absent")
            add("Local HAC comparisons with BH correction do not constitute global SPA inference")
            add("Researchers were not blind to overlapping Hyperliquid market history")
            add("The control plane does not prevent cross-run reuse of the sealed period")
        }

    private fun artifactManifest(
        run: ExperimentRunRow,
        prepared: PreparedHourlyReversalData,
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
                failedTrialParameters?.let {
                    listOf(
                        TrialWrite(
                            trialNumber = 0,
                            parameters = it,
                            status = TrialStatus.FAILED,
                            metrics = null,
                            failure = failure,
                        ),
                    )
                }.orEmpty(),
        )
        return JobExecutionResult.Failed(code, safeMessage, retryable = false)
    }

    private fun recovered(run: ExperimentRunRow): JobExecutionResult.Succeeded {
        require(run.theoryId == HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID)
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
        require(value.isFinite())
        return value
    }

    private fun finiteOrNull(value: Double): JsonElement =
        if (value.isFinite()) JsonPrimitive(value) else JsonNull

    private fun sanitize(message: String): String =
        message
            .replace(CONTROL_CHARACTERS, " ")
            .trim()
            .ifBlank { "Hourly reversal experiment could not be completed" }
            .take(MAX_MESSAGE_CHARS)

    private data class PreparedHourlyReversalData(
        val snapshot: DataSnapshot,
        val sourceCandleCount: Int,
        val validationRows: List<LabeledObservation>,
        val totalLabeledRows: Int,
        val sealedHoldout: List<LabeledObservation>,
        val boundaryPurgedRows: Int,
        val sealedHoldoutFromEpochMillis: Long,
        val sealedHoldoutLastDecisionEpochMillis: Long,
        val sealedHoldoutToEpochMillis: Long,
    )

    private data class Coefficients(
        val intercept: Double,
        val slope: Double,
    )

    private data class BaselineComparison(
        val name: String,
        val baselineMetrics: ForecastMetricSet,
        val comparison: PredictiveComparison,
        val adjustedPValue: Double,
    )

    private data class HourlyReversalEvaluation(
        val againstHistoricalMean: WalkForwardExperimentResult,
        val againstZero: WalkForwardExperimentResult,
        val againstPersistence: WalkForwardExperimentResult,
        val developmentComparisons: List<BaselineComparison>,
        val developmentCoefficients: List<Coefficients>,
        val holdout: HoldoutExperimentResult,
        val holdoutComparisons: List<BaselineComparison>,
        val holdoutCoefficients: Coefficients,
        val evidenceStatus: String,
    )

    private class ExactFoldCountPlanner(
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
            if (available.size != requiredFoldCount) {
                throw InsufficientRealDataException(
                    "Real snapshot yields ${available.size} folds; exactly $requiredFoldCount are frozen",
                )
            }
            return available
        }
    }

    private class InsufficientRealDataException(
        override val message: String,
    ) : IllegalArgumentException(message)

    private companion object {
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        const val THEORY_VERSION = "1.0.0"
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val HYPERLIQUID_INFO_URI = "https://api.hyperliquid.xyz/info"
        const val BTC_PERPETUAL_INSTRUMENT = "hyperliquid:perpetual:BTC"
        const val RETURN_FEATURE = "return_1h"
        const val HISTORICAL_MEAN_BASELINE = "EXPANDING_HISTORICAL_MEAN"
        const val ZERO_RETURN_BASELINE = "ZERO_RETURN_RANDOM_WALK"
        const val PERSISTENCE_BASELINE = "POSITIVE_RETURN_PERSISTENCE"
        const val CANDIDATE_ESTIMATOR = "OLS_INTERCEPT_RETURN_1H_NEGATIVE_SIGN_REQUIRED"
        const val EXPERIMENT_JOB_KIND = "EXPERIMENT_RUN"
        const val RUN_RESOURCE_TYPE = "run"
        const val PREDICTIONS_ARTIFACT_KIND = "PREDICTIONS"
        const val REPORT_ARTIFACT_KIND = "REPORT"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ENGINE_NAME = "marketlab-kotlin-btc-hourly-return-reversal-engine-v1"
        const val RUN_MANIFEST_SCHEMA = "marketlab.run-manifest.v1"
        const val ARTIFACT_MANIFEST_SCHEMA = "marketlab.artifact-manifest.v1"
        const val PREDICTIONS_SCHEMA = "marketlab.btc-hourly-reversal-predictions.v1"
        const val REPORT_SCHEMA = "marketlab.btc-hourly-reversal-report.v1"
        const val METRICS_SCHEMA = "marketlab.btc-hourly-reversal-metrics.v1"
        const val MAX_MESSAGE_CHARS = 1_000
    }
}
