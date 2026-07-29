package dev.marketlab.research

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.Funding
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.paper.OrderSide
import dev.marketlab.data.hyperliquid.HyperliquidDataIngestor
import dev.marketlab.data.hyperliquid.IngestionResult
import dev.marketlab.data.storage.ContentAddressedDataStore
import dev.marketlab.engine.BookCapacityAnalyzer
import dev.marketlab.engine.FeatureVector
import dev.marketlab.engine.ForecastMetricSet
import dev.marketlab.engine.HistoricalMeanEstimator
import dev.marketlab.engine.LabeledObservation
import dev.marketlab.engine.LinearEstimator
import dev.marketlab.engine.WalkForwardConfig
import dev.marketlab.engine.WalkForwardExperiment
import dev.marketlab.engine.WalkForwardExperimentResult
import dev.marketlab.engine.WalkForwardSplitPlanner
import dev.marketlab.engine.ZeroReturnEstimator
import dev.marketlab.theories.AcademicTheoryRegistry
import dev.marketlab.theory.TheoryPlanHasher
import java.time.Clock
import java.time.Instant
import kotlin.math.ln

class FundingResearchSuite(
    private val config: ResearchCliConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run(): ResearchSuiteReport {
        val now = clock.instant()
        check(!config.endExclusive.plusMillis(HOUR).isAfter(now)) {
            "end must leave one fully closed hourly candle after the final funding decision"
        }
        val store = ContentAddressedDataStore(config.dataRoot)
        val captures = HyperliquidDataIngestor.mainnet(store, clock).use { ingestor ->
            config.coins.map { coin -> ingestCoin(ingestor, store, coin) }
        }
        val snapshots = captures
            .flatMap { capture ->
                listOf(
                    snapshotEvidence(capture.coin, "CANDLE", capture.candles.snapshot),
                    snapshotEvidence(capture.coin, "FUNDING", capture.funding.snapshot),
                    snapshotEvidence(capture.coin, "L2_BOOK", capture.book.snapshot),
                )
            }
            .sortedWith(compareBy(SnapshotEvidence::coin, SnapshotEvidence::observation))
        val experiments = captures.map { capture ->
            val rows = FundingReturnDataset.align(
                coin = capture.coin,
                startInclusive = config.startInclusive,
                endExclusive = config.endExclusive,
                funding = capture.funding.observations,
                candles = capture.candles.observations,
            )
            evaluate(capture.coin, rows)
        }
        val capacities = captures.map { capacity(it.coin, it.book) }
        val relatedPlan = requireNotNull(
            AcademicTheoryRegistry.find(TheoryId(RELATED_THEORY_ID)),
        ).compile()
        return ResearchSuiteReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            suiteVersion = SUITE_VERSION,
            generatedAtEpochMillis = snapshots.maxOf(SnapshotEvidence::createdAtEpochMillis),
            producer = ProducerReport(
                sourceRevision = config.sourceRevision,
                sourceRevisionOrigin = config.sourceRevisionOrigin.name,
                productionMode = config.productionMode,
                entryPoint = "dev.marketlab.research.MainKt",
                javaRuntimeVersion = requiredRuntimeProperty("java.runtime.version"),
                javaVmName = requiredRuntimeProperty("java.vm.name"),
                javaVendor = requiredRuntimeProperty("java.vendor"),
                kotlinRuntimeVersion = KotlinVersion.CURRENT.toString(),
                operatingSystem = requiredRuntimeProperty("os.name"),
                architecture = requiredRuntimeProperty("os.arch"),
            ),
            configuration = ReportConfiguration(
                dataRoot = config.dataRoot.toString(),
                artifactRoot = config.artifactRoot.toString(),
                coins = config.coins,
                startInclusiveEpochMillis = config.startInclusive.toEpochMilli(),
                endExclusiveEpochMillis = config.endExclusive.toEpochMilli(),
                deterministicSeed = config.deterministicSeed,
                sourceRevision = config.sourceRevision,
                sourceRevisionOrigin = config.sourceRevisionOrigin.name,
                productionMode = config.productionMode,
            ),
            methodology = MethodologyReport(
                experimentId = EXPERIMENT_ID,
                relatedRegisteredTheoryId = RELATED_THEORY_ID,
                relatedRegisteredTheoryPlanSha256 = TheoryPlanHasher.hash(relatedPlan).hex,
                adaptation =
                    "Forecast-only diagnostic adaptation: the latest funding observation known " +
                        "before an hourly boundary predicts that hour's open-to-close return. It " +
                        "does not reproduce the registered delta-neutral funding/basis carry trade.",
                decisionRule =
                    "At each UTC hour, use only the preceding hour-bucket's Hyperliquid funding " +
                        "record, whose availability timestamp must be no later than the decision.",
                target = "Natural log of the next completed 1h candle close divided by its open.",
                candidateEstimator = "Expanding-window OLS with intercept and funding_rate feature.",
                controls = listOf("expanding-historical-mean", "zero-return"),
                minimumTrainingRows = MINIMUM_TRAINING_ROWS,
                testRows = TEST_ROWS,
                stepRows = STEP_ROWS,
                purgeMillis = 0L,
                embargoMillis = 0L,
                hacLag = HAC_LAG,
                researchAdequacyRows = RESEARCH_ADEQUACY_ROWS,
                predictiveAlpha = PREDICTIVE_ALPHA,
                historicalExecutionEvaluated = false,
                fabricatedFills = false,
            ),
            snapshots = snapshots,
            experiments = experiments.sortedBy(CoinExperimentReport::coin),
            capacityObservations = capacities.sortedBy(CapacityObservationReport::coin),
            limitations = listOf(
                "Capacity sweeps use one currently observed L2 snapshot per coin and are not historical backtests.",
                "No order, queue position, latency fill, fee, slippage, funding payment, or paper fill is fabricated.",
                "Passing the predictive screen cannot promote a theory to paper trading without historical observed-book execution evidence.",
                "This narrow funding-rate screen omits mark/oracle basis and is not the registered carry theory reproduction.",
            ),
        )
    }

    private suspend fun ingestCoin(
        ingestor: HyperliquidDataIngestor,
        store: ContentAddressedDataStore,
        coin: String,
    ): CoinCapture {
        val requestedDuration = Math.subtractExact(
            config.endExclusive.toEpochMilli(),
            config.startInclusive.toEpochMilli(),
        )
        val candles = ingestor.ingestCandles(
            coin = coin,
            interval = "1h",
            startInclusive = config.startInclusive.plusMillis(HOUR),
            endExclusive = config.endExclusive.plusMillis(HOUR),
            requirement = DataRequirement(
                key = "research-$coin-hourly-candles",
                observation = ObservationKind.CANDLE,
                sourcePreference = listOf(HYPERLIQUID_MAINNET),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                sampling = Sampling.FixedDuration(HOUR),
                requiredFields = listOf(
                    "baseVolume",
                    "close",
                    "high",
                    "low",
                    "open",
                    "tradeCount",
                ),
                minimumHistoryMillis = requestedDuration,
            ),
        )
        val funding = ingestor.ingestFunding(
            coin = coin,
            startInclusive = config.startInclusive,
            endExclusive = config.endExclusive,
            requirement = DataRequirement(
                key = "research-$coin-hourly-funding",
                observation = ObservationKind.FUNDING,
                sourcePreference = listOf(HYPERLIQUID_MAINNET),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                sampling = Sampling.FixedDuration(HOUR),
                requiredFields = listOf("premium", "rate"),
                // Funding timestamps carry small exchange-side sub-hour jitter. Exact
                // bucket coverage is enforced below, so this span check need only prove
                // that the first and last requested buckets were reached.
                minimumHistoryMillis = maxOf(1L, requestedDuration - HOUR + 1L),
            ),
        )
        val book = ingestor.ingestL2Book(
            coin = coin,
            requirement = DataRequirement(
                key = "research-$coin-current-l2",
                observation = ObservationKind.L2_BOOK,
                sourcePreference = listOf(HYPERLIQUID_MAINNET),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                sampling = Sampling.EventTime,
                requiredFields = listOf("asks", "bids"),
                minimumHistoryMillis = 1L,
            ),
        )
        listOf(candles.snapshot, funding.snapshot, book.snapshot).forEach { snapshot ->
            check(snapshot.quality.usable) { "unusable snapshot escaped ingestion quality gate" }
            check(snapshot.objects.all { it.provenance.production }) {
                "research suite refuses non-production observations"
            }
            snapshot.objects.forEach(store::verifyObject)
        }
        return CoinCapture(coin, candles, funding, book)
    }

    private fun evaluate(
        coin: String,
        rows: List<LabeledObservation>,
    ): CoinExperimentReport {
        val minimumForFold = MINIMUM_TRAINING_ROWS + TEST_ROWS
        if (rows.size < minimumForFold) {
            return CoinExperimentReport(
                coin = coin,
                sample = SampleAdequacyReport(
                    requestedHourlyRows = config.requestedHours,
                    completeAlignedRows = rows.size,
                    minimumRowsForOneFold = minimumForFold,
                    researchAdequacyRows = RESEARCH_ADEQUACY_ROWS,
                    emittedFolds = 0,
                    outOfSampleRows = 0,
                    adequateForResearchScreen = false,
                    explanation =
                        "Only ${rows.size} complete rows; $minimumForFold are required for one " +
                            "predeclared walk-forward fold.",
                ),
                candidate = null,
                evidenceStatus = EvidenceStatus.NOT_RUN_INSUFFICIENT_ROWS,
                promotionStatus = PromotionStatus.INCONCLUSIVE_INSUFFICIENT_SAMPLE,
                promotionExplanation =
                    "No model was fit because the predeclared minimum sample was not met.",
            )
        }
        val plannerConfig = WalkForwardConfig(
            minimumTrainingRows = MINIMUM_TRAINING_ROWS,
            testRows = TEST_ROWS,
            stepRows = STEP_ROWS,
            purgeMillis = 0L,
            embargoMillis = 0L,
        )
        val againstZero = experiment(plannerConfig, ZeroReturnEstimator()).run(rows)
        val againstMean = experiment(plannerConfig, HistoricalMeanEstimator()).run(rows)
        requireComparableRuns(againstZero, againstMean)
        val comparisons = listOf(
            comparison("expanding-historical-mean", againstMean),
            comparison("zero-return", againstZero),
        )
        val forecasts = againstZero.observations.indices.map { index ->
            val zero = againstZero.observations[index]
            val mean = againstMean.observations[index]
            ForecastRecord(
                fold = zero.fold,
                rowId = zero.rowId,
                decisionTimeEpochMillis = zero.decisionTime.epochMillis,
                actualLogReturn = zero.actual,
                candidateForecast = zero.candidate,
                zeroReturnForecast = zero.baseline,
                historicalMeanForecast = mean.baseline,
            )
        }
        val folds = forecasts.maxOf(ForecastRecord::fold) + 1
        val sampleAdequate = rows.size >= RESEARCH_ADEQUACY_ROWS && folds >= MINIMUM_ADEQUATE_FOLDS
        val predictivePass = comparisons.all {
            it.meanSquaredErrorImprovement > 0.0 &&
                it.twoSidedPValue < PREDICTIVE_ALPHA
        }
        val evidenceStatus = if (sampleAdequate && predictivePass) {
            EvidenceStatus.PREDICTIVE_SCREEN_PASSED
        } else {
            EvidenceStatus.INCONCLUSIVE
        }
        val promotionStatus = when {
            !sampleAdequate -> PromotionStatus.INCONCLUSIVE_INSUFFICIENT_SAMPLE
            !predictivePass -> PromotionStatus.INCONCLUSIVE_PREDICTIVE_TEST_FAILED
            else -> PromotionStatus.INCONCLUSIVE_HISTORICAL_EXECUTION_NOT_TESTED
        }
        val promotionExplanation = when (promotionStatus) {
            PromotionStatus.INCONCLUSIVE_INSUFFICIENT_SAMPLE ->
                "The forecast was evaluated, but the predeclared research sample/fold threshold was not met."
            PromotionStatus.INCONCLUSIVE_PREDICTIVE_TEST_FAILED ->
                "The candidate did not improve squared error over both controls at the predeclared HAC alpha."
            PromotionStatus.INCONCLUSIVE_HISTORICAL_EXECUTION_NOT_TESTED ->
                "Predictive evidence passed, but promotion remains blocked because no historical observed-book execution was tested."
        }
        return CoinExperimentReport(
            coin = coin,
            sample = SampleAdequacyReport(
                requestedHourlyRows = config.requestedHours,
                completeAlignedRows = rows.size,
                minimumRowsForOneFold = minimumForFold,
                researchAdequacyRows = RESEARCH_ADEQUACY_ROWS,
                emittedFolds = folds,
                outOfSampleRows = forecasts.size,
                adequateForResearchScreen = sampleAdequate,
                explanation = if (sampleAdequate) {
                    "The predeclared row and fold thresholds were met."
                } else {
                    "Requires at least $RESEARCH_ADEQUACY_ROWS rows and " +
                        "$MINIMUM_ADEQUATE_FOLDS emitted folds; observed ${rows.size} and $folds."
                },
            ),
            candidate = CandidateReport(
                feature = "funding_rate",
                candidateMetrics = metric(againstZero.candidateMetrics),
                comparisons = comparisons,
                forecasts = forecasts,
            ),
            evidenceStatus = evidenceStatus,
            promotionStatus = promotionStatus,
            promotionExplanation = promotionExplanation,
        )
    }

    private fun experiment(
        config: WalkForwardConfig,
        baseline: dev.marketlab.engine.Estimator,
    ): WalkForwardExperiment =
        WalkForwardExperiment(
            splitPlanner = WalkForwardSplitPlanner(config),
            candidate = LinearEstimator(listOf("funding_rate")),
            baseline = baseline,
            hacLag = HAC_LAG,
        )

    private fun comparison(
        control: String,
        result: WalkForwardExperimentResult,
    ): ControlComparisonReport =
        ControlComparisonReport(
            control = control,
            controlMetrics = metric(result.baselineMetrics),
            meanSquaredErrorImprovement = result.comparison.meanSquaredErrorImprovement,
            hacStandardError = result.comparison.hacStandardError,
            zStatistic = result.comparison.zStatistic.toString(),
            twoSidedPValue = result.comparison.twoSidedPValue,
            hacLag = result.comparison.hacLag,
        )

    private fun metric(value: ForecastMetricSet): ForecastMetricReport =
        ForecastMetricReport(
            count = value.count,
            meanAbsoluteError = value.meanAbsoluteError,
            rootMeanSquaredError = value.rootMeanSquaredError,
            directionalAccuracy = value.directionalAccuracy,
        )

    private fun requireComparableRuns(
        left: WalkForwardExperimentResult,
        right: WalkForwardExperimentResult,
    ) {
        check(left.observations.size == right.observations.size)
        left.observations.zip(right.observations).forEach { (first, second) ->
            check(first.fold == second.fold)
            check(first.rowId == second.rowId)
            check(first.decisionTime == second.decisionTime)
            check(first.actual == second.actual)
            check(first.candidate == second.candidate)
        }
    }

    private fun snapshotEvidence(
        coin: String,
        observation: String,
        snapshot: DataSnapshot,
    ): SnapshotEvidence =
        SnapshotEvidence(
            coin = coin,
            observation = observation,
            snapshotId = snapshot.id.value,
            snapshotManifestSha256 = snapshot.manifestHash.hex,
            createdAtEpochMillis = snapshot.createdAt.epochMillis,
            qualityCheckedAtEpochMillis = snapshot.quality.checkedAt.epochMillis,
            qualityUsable = snapshot.quality.usable,
            qualityIssues = snapshot.quality.issues.map {
                QualityIssueEvidence(
                    kind = it.kind.name,
                    severity = it.severity.name,
                    message = it.message,
                )
            }.sortedWith(compareBy(QualityIssueEvidence::severity, QualityIssueEvidence::kind)),
            rawObjects = snapshot.objects.map {
                RawObjectEvidence(
                    contentSha256 = it.contentHash.hex,
                    uri = it.uri,
                    byteCount = it.byteCount,
                    rowCount = it.rowCount,
                    eventFromInclusiveEpochMillis = it.eventTimeRange.fromInclusive.epochMillis,
                    eventToExclusiveEpochMillis = it.eventTimeRange.toExclusive.epochMillis,
                    availableFromInclusiveEpochMillis =
                        it.availabilityTimeRange.fromInclusive.epochMillis,
                    availableToExclusiveEpochMillis =
                        it.availabilityTimeRange.toExclusive.epochMillis,
                    source = it.provenance.source.value,
                    production = it.provenance.production,
                    retrievedAtEpochMillis = it.provenance.retrievedAt.epochMillis,
                    requestMethod = it.provenance.request.method,
                    requestUri = it.provenance.request.uri,
                    requestParameters = it.provenance.request.parameters.toSortedMap(),
                    schemaVersion = it.provenance.schemaVersion,
                    adapterVersion = it.provenance.adapterVersion,
                )
            }.sortedBy(RawObjectEvidence::contentSha256),
        )

    private fun capacity(
        coin: String,
        result: IngestionResult<L2Book>,
    ): CapacityObservationReport {
        val book = result.observations.single()
        val rawObject = result.snapshot.objects.single()
        check(book.checksum == rawObject.contentHash.hex) {
            "normalized book checksum does not match its stored raw response"
        }
        val fraction = DecimalValue.of("0.10")
        val sweeps = OrderSide.entries.flatMap { side ->
            CAPACITY_NOTIONALS.map { notional ->
                val estimate = BookCapacityAnalyzer.sweep(
                    book = book,
                    side = side,
                    notional = notional,
                    maximumDisplayedDepthFraction = fraction,
                )
                CapacitySweepReport(
                    side = side.name,
                    requestedNotional = estimate.requestedNotional.canonical,
                    filledNotional = estimate.filledNotional.canonical,
                    visibleQuantity = estimate.filledQuantity.canonical,
                    vwap = estimate.vwap?.canonical,
                    referencePrice = estimate.referencePrice.canonical,
                    impactBasisPoints = estimate.impactBasisPoints?.let(::normalizeFloatingZero),
                    completeWithinObservedDepth = estimate.complete,
                )
            }
        }
        return CapacityObservationReport(
            coin = coin,
            classification = "POINT_IN_TIME_NON_HISTORICAL_L2_CAPACITY_DIAGNOSTIC",
            historical = false,
            representsExecutionOrFill = false,
            exchangeTimeEpochMillis = book.header.exchangeTime.epochMillis,
            receivedAtEpochMillis = book.header.receivedAt.epochMillis,
            availableAtEpochMillis = book.header.availableAt.epochMillis,
            rawBookSha256 = rawObject.contentHash.hex,
            maximumDisplayedDepthFraction = fraction.canonical,
            sweeps = sweeps,
        )
    }

    private data class CoinCapture(
        val coin: String,
        val candles: IngestionResult<Candle>,
        val funding: IngestionResult<Funding>,
        val book: IngestionResult<L2Book>,
    )

    private fun normalizeFloatingZero(value: Double): Double =
        if (kotlin.math.abs(value) < FLOATING_ZERO_TOLERANCE) 0.0 else value

    private fun requiredRuntimeProperty(name: String): String =
        requireNotNull(System.getProperty(name)?.takeIf(String::isNotBlank)) {
            "missing runtime property $name"
        }

    private companion object {
        const val REPORT_SCHEMA_VERSION = "marketlab-research-report-v1"
        const val SUITE_VERSION = "funding-return-screen-v1"
        const val EXPERIMENT_ID = "causal-funding-rate-next-hour-return"
        const val RELATED_THEORY_ID = "funding-basis-carry"
        const val HOUR = ResearchCliConfig.HOUR_MILLIS
        const val MINIMUM_TRAINING_ROWS = 720
        const val TEST_ROWS = 168
        const val STEP_ROWS = 168
        const val HAC_LAG = 24
        const val RESEARCH_ADEQUACY_ROWS = 2_160
        const val MINIMUM_ADEQUATE_FOLDS = 5
        const val PREDICTIVE_ALPHA = 0.05
        const val FLOATING_ZERO_TOLERANCE = 1.0e-12
        val HYPERLIQUID_MAINNET = DataSourceId("hyperliquid-mainnet")
        val CAPACITY_NOTIONALS = listOf("10000", "100000", "1000000").map(DecimalValue::of)
    }
}

internal object FundingReturnDataset {
    fun align(
        coin: String,
        startInclusive: Instant,
        endExclusive: Instant,
        funding: List<Funding>,
        candles: List<Candle>,
    ): List<LabeledObservation> {
        val expectedFundingBuckets = hourlyRange(startInclusive, endExclusive)
        val expectedCandleStarts = expectedFundingBuckets.map { Math.addExact(it, HOUR) }
        val fundingByBucket = funding.groupBy { floorHour(it.header.exchangeTime.epochMillis) }
        check(fundingByBucket.keys == expectedFundingBuckets.toSet()) {
            "funding buckets do not exactly cover the requested range"
        }
        check(fundingByBucket.values.all { it.size == 1 }) {
            "each requested funding hour must contain exactly one observation"
        }
        val candlesByStart = candles.groupBy(::candleStart)
        check(candlesByStart.keys == expectedCandleStarts.toSet()) {
            "closed candle hours do not exactly cover all forecast labels"
        }
        check(candlesByStart.values.all { it.size == 1 }) {
            "each forecast hour must contain exactly one closed candle"
        }
        return expectedFundingBuckets.mapIndexed { index, bucket ->
            val fundingEvent = requireNotNull(fundingByBucket[bucket]).single()
            val candleStart = expectedCandleStarts[index]
            val candle = requireNotNull(candlesByStart[candleStart]).single()
            val expectedInstrument = "hyperliquid:perpetual:$coin"
            check(fundingEvent.header.source.value == "hyperliquid-mainnet")
            check(candle.header.source.value == "hyperliquid-mainnet")
            check(fundingEvent.header.instrument.value == expectedInstrument)
            check(candle.header.instrument.value == expectedInstrument)
            check(fundingEvent.header.exchangeTime.epochMillis in bucket until Math.addExact(bucket, HOUR)) {
                "funding timestamp escaped its expected UTC-hour bucket"
            }
            check(fundingEvent.header.availableAt <= MarketTimestamp(candleStart)) {
                "funding was not causally available by the forecast decision"
            }
            check(candle.closed) { "open candle cannot become a label" }
            check(candle.intervalMillis == HOUR) { "only exact hourly candles are accepted" }
            check(candle.header.availableAt == MarketTimestamp(Math.addExact(candleStart, HOUR))) {
                "candle semantic availability is inconsistent with its close"
            }
            val rowId = "$coin:$bucket"
            val label = ln(
                candle.close.toBigDecimal().toDouble() /
                    candle.open.toBigDecimal().toDouble(),
            )
            check(label.isFinite())
            LabeledObservation(
                rowId = rowId,
                decisionTime = MarketTimestamp(candleStart),
                labelFrom = MarketTimestamp(candleStart),
                labelTo = candle.header.availableAt,
                features = FeatureVector(
                    rowId = rowId,
                    values = mapOf("funding_rate" to fundingEvent.rate.toBigDecimal().toDouble()),
                ),
                label = label,
            )
        }
    }

    private fun candleStart(candle: Candle): Long =
        Math.addExact(
            Math.subtractExact(candle.header.exchangeTime.epochMillis, candle.intervalMillis),
            1L,
        )

    private fun hourlyRange(startInclusive: Instant, endExclusive: Instant): List<Long> {
        val result = mutableListOf<Long>()
        var cursor = startInclusive.toEpochMilli()
        val end = endExclusive.toEpochMilli()
        while (cursor < end) {
            result += cursor
            cursor = Math.addExact(cursor, HOUR)
        }
        return result
    }

    private fun floorHour(epochMillis: Long): Long =
        Math.floorDiv(epochMillis, HOUR) * HOUR

    private const val HOUR = ResearchCliConfig.HOUR_MILLIS
}
