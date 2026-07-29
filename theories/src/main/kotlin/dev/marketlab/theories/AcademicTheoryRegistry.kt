package dev.marketlab.theories

import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.theory.Aggregation
import dev.marketlab.theory.BinaryOperation
import dev.marketlab.theory.EstimatorSpec
import dev.marketlab.theory.ExpectedSign
import dev.marketlab.theory.FamilywiseCorrection
import dev.marketlab.theory.ForecastTarget
import dev.marketlab.theory.FoldScheme
import dev.marketlab.theory.Horizon
import dev.marketlab.theory.Metric
import dev.marketlab.theory.ParameterSpace
import dev.marketlab.theory.ParameterSpec
import dev.marketlab.theory.ParameterValues
import dev.marketlab.theory.ReproductionKind
import dev.marketlab.theory.TheoryFamily
import dev.marketlab.theory.TheoryMaturity
import dev.marketlab.theory.TheoryPlan
import dev.marketlab.theory.TheoryLineage
import dev.marketlab.theory.TheoryPlanHasher
import dev.marketlab.theory.TheoryProvider
import dev.marketlab.theory.UniverseSelection
import dev.marketlab.theory.UnaryOperation
import dev.marketlab.theory.ValidationSpec
import dev.marketlab.theory.Window
import dev.marketlab.theory.binary
import dev.marketlab.theory.constant
import dev.marketlab.theory.feature
import dev.marketlab.theory.field
import dev.marketlab.theory.lag
import dev.marketlab.theory.rolling
import dev.marketlab.theory.theory
import dev.marketlab.theory.unary
import dev.marketlab.theory.utcHourIndicator

internal abstract class FixedTheoryProvider : TheoryProvider {
    final override fun compile(parameters: ParameterValues): TheoryPlan {
        require(parameters.values.isEmpty()) {
            "${descriptor.id.value} has no compile-time parameters; estimator trials are declared in ParameterSpace"
        }
        return buildPlan()
    }

    protected abstract fun buildPlan(): TheoryPlan
}

private val CONTROL_CANDLES = requirement(
    key = "hyperliquid_candles",
    observation = ObservationKind.CANDLE,
    sources = listOf(HYPERLIQUID),
    kinds = listOf(InstrumentKind.PERPETUAL),
    sampling = Sampling.FixedDuration(HOUR),
    fields = listOf("close"),
    history = 365L * DAY,
)

internal abstract class ReturnControlProvider(
    final override val descriptor: dev.marketlab.theory.TheoryDescriptor,
    private val estimatorSpec: EstimatorSpec,
) : FixedTheoryProvider() {
    final override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "This control makes no claim of exploitable conditional return predictability."
            mechanism = "It is a mandatory reference forecast or passive allocation against which theories are judged."
            falsifiableClaim = "A promoted theory must improve on this control in the sealed holdout after all costs."
            expectedSign = ExpectedSign.LOWER_ERROR_THAN_BASELINE
            horizon = Horizon.Duration(DAY)
            reproductionKind = ReproductionKind.CONTROL
            confirmationPeriodPolicy = "The same chronological folds and sealed Hyperliquid holdout used by candidate theories."
            cite(AcademicReferences.FAMA_EFFICIENT_MARKETS)
            cite(AcademicReferences.WELCH_GOYAL_FORECAST_BASELINES)
            requireData(CONTROL_CANDLES)
            requirePointInTimeUniverseData(history = 365L * DAY)
        }
        universe(pointInTimeUniverse())
        feature(
            name = "last_return",
            description = "Most recent completed one-day log return.",
            expression = logReturn(field("hyperliquid_candles", "close"), Window.Duration(DAY)),
        )
        target(
            ForecastTarget.Return(
                price = field("hyperliquid_candles", "close"),
                horizon = Horizon.Duration(DAY),
            ),
        )
        estimator(estimatorSpec)
        parameters()
        validation(standardValidation(180L * DAY, 30L * DAY, 60L * DAY, DAY))
        execution(forecastOnlyExecution())
        metrics(*RETURN_FORECAST_METRICS)
        failWhen("Control data are incomplete, non-production, or fail the snapshot quality gate.")
        promotion(controlGate())
    }
}

internal object RandomWalkControl : ReturnControlProvider(
    descriptor = descriptor(
        id = "control-random-walk",
        name = "Random-walk control",
        summary = "No-change price forecast / zero expected return.",
        family = TheoryFamily.NULL_BASELINE,
        maturity = TheoryMaturity.CONTROL,
        "baseline",
        "random-walk",
    ),
    estimatorSpec = EstimatorSpec.RandomWalk,
)

internal object HistoricalMeanControl : ReturnControlProvider(
    descriptor = descriptor(
        id = "control-historical-mean",
        name = "Historical-mean control",
        summary = "Expanding-window unconditional mean return forecast.",
        family = TheoryFamily.NULL_BASELINE,
        maturity = TheoryMaturity.CONTROL,
        "baseline",
        "historical-mean",
    ),
    estimatorSpec = EstimatorSpec.HistoricalMean,
)

internal object PersistenceControl : ReturnControlProvider(
    descriptor = descriptor(
        id = "control-persistence",
        name = "Persistence control",
        summary = "Forecast the next return with the latest completed return.",
        family = TheoryFamily.NULL_BASELINE,
        maturity = TheoryMaturity.CONTROL,
        "baseline",
        "persistence",
    ),
    estimatorSpec = EstimatorSpec.Persistence("last_return"),
)

internal object FlatSignalControl : ReturnControlProvider(
    descriptor = descriptor(
        id = "control-flat",
        name = "Flat-position control",
        summary = "Always forecast no position and no trading return.",
        family = TheoryFamily.NULL_BASELINE,
        maturity = TheoryMaturity.CONTROL,
        "baseline",
        "flat",
    ),
    estimatorSpec = EstimatorSpec.FlatSignal,
)

internal object BuyAndHoldControl : ReturnControlProvider(
    descriptor = descriptor(
        id = "control-buy-and-hold",
        name = "Buy-and-hold control",
        summary = "Passive fully invested long allocation over each evaluation window.",
        family = TheoryFamily.NULL_BASELINE,
        maturity = TheoryMaturity.CONTROL,
        "baseline",
        "buy-and-hold",
    ),
    estimatorSpec = EstimatorSpec.BuyAndHold,
)

internal object HarRealizedVolatilityTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "har-realized-volatility",
        name = "HAR realized-volatility cascade",
        summary = "Daily variance forecast from daily, weekly, and monthly realized-variance components.",
        family = TheoryFamily.VOLATILITY,
        maturity = TheoryMaturity.TIER_1,
        "har-rv",
        "realized-volatility",
        "volatility",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "Completed intraday returns contain persistent daily, weekly, and monthly variance components."
            mechanism = "Heterogeneous trading horizons create an additive volatility cascade that approximates long memory."
            falsifiableClaim =
                "HAR-RV must reduce sealed-holdout QLIKE versus both historical-mean and persistence variance forecasts."
            expectedSign = ExpectedSign.LOWER_ERROR_THAN_BASELINE
            horizon = Horizon.Duration(DAY)
            reproductionKind = ReproductionKind.METHOD_REPRODUCTION
            confirmationPeriodPolicy = "Lock five-minute sampling and 1/7/30-day components before opening the final 90 days."
            cite(AcademicReferences.CORSI_HAR_RV)
            cite(AcademicReferences.MARTENS_HAR_LIMITATION)
            requirePointInTimeUniverseData(history = 730L * DAY)
            requireData(
                requirement(
                    key = "hyperliquid_five_minute_candles",
                    observation = ObservationKind.CANDLE,
                    sources = listOf(HYPERLIQUID),
                    kinds = listOf(InstrumentKind.PERPETUAL),
                    sampling = Sampling.FixedDuration(5L * MINUTE),
                    fields = listOf("close"),
                    history = 730L * DAY,
                ),
            )
        }
        universe(pointInTimeUniverse())
        feature(
            "intraday_return",
            "Five-minute log return using completed candles only.",
            logReturn(field("hyperliquid_five_minute_candles", "close"), Window.Bars(1)),
        )
        feature(
            "rv_daily",
            "Trailing one-day realized variance.",
            rolling(
                unary(UnaryOperation.SQUARE, feature("intraday_return")),
                Window.Duration(DAY),
                Aggregation.SUM,
                minimumObservations = 240,
            ),
        )
        feature(
            "rv_monthly",
            "Mean realized variance over the trailing 30 days.",
            rolling(feature("rv_daily"), Window.Duration(30L * DAY), Aggregation.MEAN),
        )
        feature(
            "rv_weekly",
            "Mean realized variance over the trailing seven days.",
            rolling(feature("rv_daily"), Window.Duration(7L * DAY), Aggregation.MEAN),
        )
        target(
            ForecastTarget.RealizedVariance(
                returnExpression = feature("intraday_return"),
                horizon = Horizon.Duration(DAY),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true, l2PenaltyParameter = "l2_penalty"))
        parameters(
            ParameterSpec.DecimalChoices(
                "l2_penalty",
                listOf(FiniteDouble(0.0), FiniteDouble(1.0e-6), FiniteDouble(1.0e-4)),
            ),
        )
        validation(standardValidation(365L * DAY, 30L * DAY, 90L * DAY, DAY))
        execution(forecastOnlyExecution())
        metrics(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE)
        failWhen("QLIKE does not improve on both registered variance controls in the sealed holdout.")
        failWhen("The result is not stable across liquid instruments or under plausible sampling changes.")
        promotion(forecastEvidenceGate())
    }
}

internal object HyperliquidBtcFourHourLogHarVarianceTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "hyperliquid-btc-four-hour-log-har-variance",
        name = "Hyperliquid BTC four-hour log-HAR variance",
        summary = "BTC perpetual log-HAR adaptation using daily variance from completed four-hour returns.",
        family = TheoryFamily.VOLATILITY,
        maturity = TheoryMaturity.TIER_1,
        "btc",
        "har-rv",
        "hyperliquid",
        "realized-variance",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis =
                "Completed four-hour BTC perpetual returns contain persistent daily, weekly, and monthly variance components."
            mechanism =
                "Heterogeneous trading horizons create an additive volatility cascade that is approximated on the log-variance scale."
            falsifiableClaim =
                "The fixed BTC log-HAR forecast must reduce sealed-holdout QLIKE versus both expanding historical-mean and persistence variance forecasts."
            expectedSign = ExpectedSign.LOWER_ERROR_THAN_BASELINE
            horizon = Horizon.Duration(DAY)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze UTC daily origins, four-hour sampling, 1/7/30-day components, and the final 90 outcomes before fitting."
            cite(AcademicReferences.BRAUNEIS_SAHINER_CRYPTO_HAR)
            cite(AcademicReferences.CORSI_HAR_RV)
            cite(AcademicReferences.PATTON_VOLATILITY_PROXY_LOSS)
            requireData(
                requirement(
                    key = "hyperliquid_btc_four_hour_candles",
                    observation = ObservationKind.CANDLE,
                    sources = listOf(HYPERLIQUID),
                    kinds = listOf(InstrumentKind.PERPETUAL),
                    sampling = Sampling.FixedDuration(4L * HOUR),
                    fields = listOf("close"),
                    history = 700L * DAY,
                ),
            )
        }
        lineage(
            TheoryLineage(
                parentTheoryId = TheoryId("har-realized-volatility"),
                parentPlanHash = TheoryPlanHasher.hash(HarRealizedVolatilityTheory.compile()).hex,
                adaptationNotes =
                    "Feasibility adaptation for Hyperliquid's 5,000-candle history limit: fixes the universe to " +
                        "the BTC perpetual, replaces unavailable five-minute returns with completed four-hour " +
                        "returns, models realized variance on the log scale to guarantee positive variance " +
                        "forecasts, and fixes intercept OLS without a penalty grid. It preserves the parent's " +
                        "one-day horizon, 1/7/30-day cascade, five-fold validation, and 90-day holdout. This is " +
                        "not a reproduction of the published five-minute HAR-RV measurement design.",
            ),
        )
        universe(
            dev.marketlab.theory.PointInTimeUniverse(
                selection = UniverseSelection.Explicit(
                    listOf(InstrumentId("hyperliquid:perpetual:BTC")),
                ),
                venues = listOf(HYPERLIQUID),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                minimumListingAgeMillis = 0,
                requireActiveAtDecisionTime = false,
            ),
        )
        feature(
            "log_rv_daily",
            "Log of the sum of six squared completed four-hour returns over the trailing UTC day.",
            logAverageDailyVariance(days = 1),
        )
        feature(
            "log_rv_weekly",
            "Log of average daily realized variance across the trailing seven UTC days.",
            logAverageDailyVariance(days = 7),
        )
        feature(
            "log_rv_monthly",
            "Log of average daily realized variance across the trailing 30 UTC days.",
            logAverageDailyVariance(days = 30),
        )
        target(
            ForecastTarget.LogRealizedVariance(
                returnExpression = fourHourLogReturn(),
                horizon = Horizon.Duration(DAY),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(
            ValidationSpec(
                scheme = FoldScheme.EXPANDING,
                foldCount = 5,
                minimumTrainingMillis = 365L * DAY,
                testMillis = 30L * DAY,
                purgeMillis = DAY,
                embargoMillis = DAY,
                sealedFinalHoldoutMillis = 90L * DAY,
                tuneInsideEachFold = false,
                dependenceAwareInference = true,
                familywiseCorrection = FamilywiseCorrection.FDR,
            ),
        )
        execution(forecastOnlyExecution())
        metrics(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE)
        failWhen("Any required candle is missing, unfinished, non-production, or yields non-positive realized variance.")
        failWhen("Sealed-holdout QLIKE is not lower than both expanding historical-mean and persistence forecasts.")
        failWhen("Either of the two holdout loss improvements fails the preregistered dependence-aware FDR screen.")
        failWhen("The single-instrument result cannot establish cross-asset or finer-sampling stability.")
        promotion(forecastEvidenceGate())
    }
}

internal object HyperliquidEthHourlyVolatilityPeriodicityTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "hyperliquid-eth-hourly-volatility-periodicity",
        name = "Hyperliquid ETH hourly volatility periodicity",
        summary = "Forecast-only test of incremental UTC hour-of-day structure in ETH hourly variance.",
        family = TheoryFamily.VOLATILITY,
        maturity = TheoryMaturity.TIER_1,
        "eth",
        "hour-of-day",
        "hyperliquid",
        "periodicity",
        "volatility",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis =
                "The UTC hour of the next completed Hyperliquid ETH perpetual candle contains " +
                    "incremental information about its variance beyond trailing hourly variance."
            mechanism =
                "Recurring global trading sessions, scheduled market activity, and algorithmic clocks " +
                    "can concentrate cryptocurrency price variation at stable hours of the day."
            falsifiableClaim =
                "A fixed 24-hour categorical log-variance model must reduce sealed-holdout QLIKE " +
                    "versus both a clock-free dynamic log-variance model and an expanding mean-variance forecast."
            expectedSign = ExpectedSign.LOWER_ERROR_THAN_BASELINE
            horizon = Horizon.Duration(HOUR)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze ETH, UTC clock encoding, one-hour sampling, dynamic controls, the exact historical " +
                    "request, and the final 30 days before retrieving or fitting the snapshot."
            cite(AcademicReferences.HANSEN_KIM_KIMBROUGH_CRYPTO_PERIODICITY)
            cite(AcademicReferences.PATTON_VOLATILITY_PROXY_LOSS)
            requireData(
                requirement(
                    key = "hyperliquid_eth_hourly_candles",
                    observation = ObservationKind.CANDLE,
                    sources = listOf(HYPERLIQUID),
                    kinds = listOf(InstrumentKind.PERPETUAL),
                    sampling = Sampling.FixedDuration(HOUR),
                    fields = listOf("close", "close_time"),
                    history = 200L * DAY,
                ),
            )
        }
        universe(
            dev.marketlab.theory.PointInTimeUniverse(
                selection =
                    UniverseSelection.Explicit(
                        listOf(InstrumentId("hyperliquid:perpetual:ETH")),
                    ),
                venues = listOf(HYPERLIQUID),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                minimumListingAgeMillis = 0,
                requireActiveAtDecisionTime = false,
            ),
        )
        feature(
            "log_rv_1h",
            "Log squared return from the latest completed hourly candle, floored at 1e-12.",
            unary(
                UnaryOperation.LOG,
                binary(
                    BinaryOperation.MAXIMUM,
                    unary(
                        UnaryOperation.SQUARE,
                        logReturn(
                            field("hyperliquid_eth_hourly_candles", "close"),
                            Window.Bars(1),
                        ),
                    ),
                    constant(1.0e-12),
                ),
            ),
        )
        feature(
            "log_rv_24h",
            "Log mean squared hourly return over the latest 24 completed returns, floored at 1e-12.",
            unary(
                UnaryOperation.LOG,
                binary(
                    BinaryOperation.MAXIMUM,
                    rolling(
                        input =
                            unary(
                                UnaryOperation.SQUARE,
                                logReturn(
                                    field("hyperliquid_eth_hourly_candles", "close"),
                                    Window.Bars(1),
                                ),
                            ),
                        window = Window.Bars(24),
                        aggregation = Aggregation.MEAN,
                        minimumObservations = 24,
                    ),
                    constant(1.0e-12),
                ),
            ),
        )
        // Hour 00 UTC is the reference category. These deterministic fields are
        // derived by the executor from each completed candle's close_time.
        (1..23).forEach { hour ->
            val suffix = hour.toString().padStart(2, '0')
            feature(
                "next_hour_${suffix}_utc",
                "One when the forecast interval starts at $suffix:00 UTC, otherwise zero.",
                utcHourIndicator(
                    field("hyperliquid_eth_hourly_candles", "close_time"),
                    hour,
                ),
            )
        }
        target(
            ForecastTarget.LogRealizedVariance(
                returnExpression =
                    logReturn(
                        field("hyperliquid_eth_hourly_candles", "close"),
                        Window.Bars(1),
                    ),
                horizon = Horizon.Duration(HOUR),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(
            standardValidation(
                minimumTraining = 90L * DAY,
                test = 7L * DAY,
                holdout = 30L * DAY,
                purge = HOUR,
                foldCount = 11,
            ),
        )
        execution(forecastOnlyExecution())
        metrics(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE)
        failWhen("Any required candle is missing, unfinished, non-production, or yields invalid variance.")
        failWhen("Sealed-holdout QLIKE is not lower than the clock-free dynamic log-variance model.")
        failWhen("Sealed-holdout QLIKE is not lower than the expanding historical mean-variance forecast.")
        failWhen("Either holdout loss improvement fails the preregistered dependence-aware FDR screen.")
        promotion(forecastEvidenceGate())
    }
}

private fun fourHourLogReturn() =
    logReturn(
        field("hyperliquid_btc_four_hour_candles", "close"),
        Window.Bars(1),
    )

private fun logAverageDailyVariance(days: Int) =
    unary(
        UnaryOperation.LOG,
        binary(
            BinaryOperation.DIVIDE,
            rolling(
                input = unary(UnaryOperation.SQUARE, fourHourLogReturn()),
                window = Window.Bars(days * 6),
                aggregation = Aggregation.SUM,
                minimumObservations = days * 6,
            ),
            constant(days.toDouble()),
        ),
    )

internal object FundingBasisCarryTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "funding-basis-carry",
        name = "Perpetual funding, basis, and carry",
        summary = "Forecast fully costed perpetual carry from funding and mark/reference basis.",
        family = TheoryFamily.CARRY,
        maturity = TheoryMaturity.TIER_1,
        "basis",
        "carry",
        "funding",
        "perpetuals",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "Extreme funding and mark/reference basis predict subsequent delta-neutral perpetual carry."
            mechanism =
                "Leveraged directional demand and limits to arbitrage allow funding and basis deviations to persist then converge."
            falsifiableClaim =
                "Funding/basis features must improve carry forecasts and remain positive net of taker fees, funding, and book impact."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(DAY)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy = "Lock features and the 24-hour horizon before the final 90-day Hyperliquid holdout."
            cite(AcademicReferences.HE_PERPETUAL_FUTURES)
            cite(AcademicReferences.SCHMELING_CRYPTO_CARRY)
            requirePointInTimeUniverseData(history = 730L * DAY)
            requireData(
                requirement(
                    "funding",
                    ObservationKind.FUNDING,
                    listOf(HYPERLIQUID),
                    listOf(InstrumentKind.PERPETUAL),
                    Sampling.EventTime,
                    listOf("rate"),
                    730L * DAY,
                ),
            )
            requireData(
                requirement(
                    "mark_oracle",
                    ObservationKind.MARK_ORACLE,
                    listOf(HYPERLIQUID),
                    listOf(InstrumentKind.PERPETUAL),
                    Sampling.FixedDuration(MINUTE),
                    listOf("mark_price", "oracle_price"),
                    730L * DAY,
                ),
            )
            requireData(executionBboRequirement(history = 180L * DAY))
            requireData(executionBookRequirement(history = 180L * DAY))
        }
        universe(pointInTimeUniverse())
        feature(
            "basis",
            "Contemporaneous mark-to-oracle relative basis.",
            binary(
                BinaryOperation.DIVIDE,
                binary(
                    BinaryOperation.SUBTRACT,
                    field("mark_oracle", "mark_price"),
                    field("mark_oracle", "oracle_price"),
                ),
                field("mark_oracle", "oracle_price"),
            ),
        )
        feature(
            "funding_mean_24h",
            "Trailing 24-hour mean funding rate using only announced observations.",
            rolling(field("funding", "rate"), Window.Duration(DAY), Aggregation.MEAN),
        )
        feature(
            "funding_basis_interaction",
            "Interaction between prevailing funding and basis.",
            binary(BinaryOperation.MULTIPLY, feature("funding_mean_24h"), feature("basis")),
        )
        target(
            ForecastTarget.CarryReturn(
                perpetualPrice = field("mark_oracle", "mark_price"),
                referencePrice = field("mark_oracle", "oracle_price"),
                fundingRate = field("funding", "rate"),
                horizon = Horizon.Duration(DAY),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true, l2PenaltyParameter = "l2_penalty"))
        parameters(
            ParameterSpec.DecimalChoices(
                "l2_penalty",
                listOf(FiniteDouble(0.0), FiniteDouble(1.0e-5), FiniteDouble(1.0e-3)),
            ),
        )
        validation(standardValidation(365L * DAY, 30L * DAY, 90L * DAY, DAY))
        execution(takerReplayExecution(latencyMillis = 500))
        metrics(*TRADING_METRICS)
        failWhen("Net carry is not positive after actual funding, fees, spread, and observed book walking.")
        failWhen("The effect fails at doubled execution costs or at any registered notional tier.")
        promotion(researchPromotionGate())
    }
}

internal object TimeSeriesMomentumTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "time-series-momentum",
        name = "Crypto time-series momentum",
        summary = "Preregistered own-return continuation test with historical-mean and passive controls.",
        family = TheoryFamily.MOMENTUM,
        maturity = TheoryMaturity.TIER_1,
        "momentum",
        "trend",
        "time-series",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "An instrument's trailing 12-month return has the same sign as its subsequent one-month return."
            mechanism = "Slow information diffusion and investor underreaction can create own-return continuation."
            falsifiableClaim =
                "Asset-level predictability must beat historical-mean, random-walk, and buy-and-hold controls without relying on volatility scaling."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.CalendarDays(30)
            reproductionKind = ReproductionKind.METHOD_REPRODUCTION
            confirmationPeriodPolicy =
                "Use the published 12-month lookback/one-month horizon and seal the final 12 months before fitting."
            cite(AcademicReferences.HUANG_TIME_SERIES_MOMENTUM_CRITIQUE)
            cite(AcademicReferences.MOSKOWITZ_TIME_SERIES_MOMENTUM)
            requirePointInTimeUniverseData(
                venues = listOf(BINANCE, KRAKEN, HYPERLIQUID),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL, InstrumentKind.SPOT),
                history = 5L * 365L * DAY,
            )
            requireData(
                requirement(
                    "daily_crypto_candles",
                    ObservationKind.CANDLE,
                    listOf(BINANCE, KRAKEN, HYPERLIQUID),
                    listOf(InstrumentKind.PERPETUAL, InstrumentKind.SPOT),
                    Sampling.FixedDuration(DAY),
                    listOf("close"),
                    5L * 365L * DAY,
                ),
            )
        }
        universe(
            pointInTimeUniverse(
                venues = listOf(BINANCE, HYPERLIQUID, KRAKEN),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL, InstrumentKind.SPOT),
                minimumListingAgeMillis = 365L * DAY,
            ),
        )
        feature(
            "return_12m",
            "Trailing 365-day log return with no overlap into the forecast horizon.",
            logReturn(field("daily_crypto_candles", "close"), Window.Duration(365L * DAY)),
        )
        feature(
            "volatility_60d",
            "Trailing 60-day standard deviation, reported as a conditioning variable rather than hidden leverage.",
            rolling(
                logReturn(field("daily_crypto_candles", "close"), Window.Bars(1)),
                Window.Duration(60L * DAY),
                Aggregation.STANDARD_DEVIATION,
                minimumObservations = 45,
            ),
        )
        target(
            ForecastTarget.Return(
                field("daily_crypto_candles", "close"),
                Horizon.CalendarDays(30),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(standardValidation(3L * 365L * DAY, 180L * DAY, 365L * DAY, 30L * DAY))
        execution(forecastOnlyExecution())
        metrics(*RETURN_FORECAST_METRICS)
        failWhen("Asset-level coefficients are not significant under dependence-aware inference.")
        failWhen("Performance is indistinguishable from the historical-mean or passive controls.")
        promotion(forecastEvidenceGate())
    }
}

internal object HyperliquidBtcTimeSeriesMomentumTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "hyperliquid-btc-time-series-momentum",
        name = "Hyperliquid BTC time-series momentum",
        summary = "Fixed-universe BTC perpetual adaptation of the 12-month own-return continuation test.",
        family = TheoryFamily.MOMENTUM,
        maturity = TheoryMaturity.TIER_1,
        "btc",
        "hyperliquid",
        "momentum",
        "time-series",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis =
                "The Hyperliquid BTC perpetual's trailing 365-day return has the same sign as its subsequent 30-day return."
            mechanism = "Slow information diffusion and investor underreaction can create own-return continuation."
            falsifiableClaim =
                "BTC perpetual predictability must beat historical-mean and random-walk forecasts in the sealed holdout."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.CalendarDays(30)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze the fixed BTC universe, 365-day lookback, 30-day horizon, and final 90 days before fitting."
            cite(AcademicReferences.HUANG_TIME_SERIES_MOMENTUM_CRITIQUE_CORRECTED)
            cite(AcademicReferences.LIU_TSYVINSKI_CRYPTOCURRENCY_RETURNS)
            cite(AcademicReferences.MOSKOWITZ_TIME_SERIES_MOMENTUM)
            requireData(
                requirement(
                    key = "hyperliquid_btc_daily_candles",
                    observation = ObservationKind.CANDLE,
                    sources = listOf(HYPERLIQUID),
                    kinds = listOf(InstrumentKind.PERPETUAL),
                    sampling = Sampling.FixedDuration(DAY),
                    fields = listOf("close"),
                    history = 3L * 365L * DAY,
                ),
            )
        }
        lineage(
            TheoryLineage(
                parentTheoryId = TheoryId("time-series-momentum"),
                parentPlanHash = TheoryPlanHasher.hash(TimeSeriesMomentumTheory.compile()).hex,
                adaptationNotes =
                    "Feasibility adaptation for Hyperliquid's quality-valid history: fixes the universe to the BTC " +
                        "perpetual and reduces the data requirement from a multi-venue cross-asset panel to three " +
                        "years of daily Hyperliquid closes. It is not a published-strategy reproduction; the " +
                        "published 365-day lookback and 30-day forecast horizon are retained.",
            ),
        )
        universe(
            dev.marketlab.theory.PointInTimeUniverse(
                selection = UniverseSelection.Explicit(
                    listOf(InstrumentId("hyperliquid:perpetual:BTC")),
                ),
                venues = listOf(HYPERLIQUID),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                minimumListingAgeMillis = 0,
                requireActiveAtDecisionTime = false,
            ),
        )
        feature(
            "return_365d",
            "Trailing 365-day BTC perpetual log return using completed daily candles only.",
            logReturn(
                field("hyperliquid_btc_daily_candles", "close"),
                Window.Duration(365L * DAY),
            ),
        )
        feature(
            "volatility_60d",
            "Trailing standard deviation of completed one-day BTC perpetual log returns.",
            rolling(
                logReturn(field("hyperliquid_btc_daily_candles", "close"), Window.Bars(1)),
                Window.Duration(60L * DAY),
                Aggregation.STANDARD_DEVIATION,
                minimumObservations = 45,
            ),
        )
        target(
            ForecastTarget.Return(
                price = field("hyperliquid_btc_daily_candles", "close"),
                horizon = Horizon.CalendarDays(30),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(
            standardValidation(
                minimumTraining = 180L * DAY,
                test = 60L * DAY,
                holdout = 90L * DAY,
                purge = 30L * DAY,
            ),
        )
        execution(forecastOnlyExecution())
        metrics(*RETURN_FORECAST_METRICS)
        failWhen("Sealed-holdout error is not lower than the expanding historical-mean forecast.")
        failWhen("Sealed-holdout error is not lower than the random-walk forecast.")
        promotion(forecastEvidenceGate())
    }
}

internal object OrderFlowQueueImbalanceTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "order-flow-queue-imbalance",
        name = "Order-flow and queue imbalance",
        summary = "Predict the next mid-price direction from causal L2 queue and order-flow imbalance.",
        family = TheoryFamily.MICROSTRUCTURE,
        maturity = TheoryMaturity.TIER_1,
        "l2",
        "order-flow",
        "queue-imbalance",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "Bid-heavy queue/flow imbalance raises the probability of the next mid-price move being upward."
            mechanism = "Asymmetric displayed depth and net book events change the probability that one best queue depletes first."
            falsifiableClaim =
                "Causal imbalance features must improve calibrated next-move predictions and yield positive taker economics after latency."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Events(1)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy = "Lock event definitions and latency before the last 30 capture days."
            cite(AcademicReferences.CONT_ORDER_FLOW_IMBALANCE)
            cite(AcademicReferences.GOULD_QUEUE_IMBALANCE)
            requirePointInTimeUniverseData(history = 90L * DAY)
            requireData(
                requirement(
                    "hyperliquid_l2_features",
                    ObservationKind.L2_BOOK,
                    listOf(HYPERLIQUID),
                    listOf(InstrumentKind.PERPETUAL),
                    Sampling.EventTime,
                    listOf("ask_quantity", "bid_quantity", "mid_price", "signed_ofi"),
                    90L * DAY,
                    maximumAvailabilityLagMillis = 2L * SECOND,
                ),
            )
            requireData(executionBboRequirement(history = 90L * DAY))
        }
        universe(pointInTimeUniverse())
        feature(
            "ofi_100_events",
            "Net order-flow imbalance over the trailing 100 observed book events.",
            rolling(field("hyperliquid_l2_features", "signed_ofi"), Window.Events(100), Aggregation.SUM),
        )
        feature(
            "queue_imbalance",
            "Best bid less best ask quantity, normalized by their sum.",
            binary(
                BinaryOperation.DIVIDE,
                binary(
                    BinaryOperation.SUBTRACT,
                    field("hyperliquid_l2_features", "bid_quantity"),
                    field("hyperliquid_l2_features", "ask_quantity"),
                ),
                binary(
                    BinaryOperation.ADD,
                    field("hyperliquid_l2_features", "bid_quantity"),
                    field("hyperliquid_l2_features", "ask_quantity"),
                ),
            ),
        )
        target(
            ForecastTarget.Direction(
                returnExpression = logReturn(
                    field("hyperliquid_l2_features", "mid_price"),
                    Window.Events(1),
                ),
                horizon = Horizon.Events(1),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true, l2PenaltyParameter = "l2_penalty"))
        parameters(
            ParameterSpec.DecimalChoices(
                "l2_penalty",
                listOf(FiniteDouble(0.0), FiniteDouble(1.0e-4), FiniteDouble(1.0e-2)),
            ),
        )
        validation(standardValidation(30L * DAY, 7L * DAY, 30L * DAY, 5L * SECOND))
        execution(takerReplayExecution(latencyMillis = 250))
        metrics(Metric.CALIBRATION, *RETURN_FORECAST_METRICS, *TRADING_METRICS)
        failWhen("Predictive improvement disappears out of sample or is confined to unavailable event states.")
        failWhen("Observed-book taker replay is unprofitable at the normal fee tier.")
        promotion(researchPromotionGate())
    }
}

internal object LiquidityConditionedReversalTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "liquidity-conditioned-reversal",
        name = "Liquidity-conditioned short-horizon reversal",
        summary = "Test whether price concessions reverse more strongly when liquidity is scarce.",
        family = TheoryFamily.LIQUIDITY,
        maturity = TheoryMaturity.TIER_1,
        "liquidity",
        "mean-reversion",
        "reversal",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "Short-horizon returns reverse, with stronger reversal following wide spreads and shallow depth."
            mechanism = "Temporary price concessions compensate liquidity suppliers for absorbing imbalanced uninformed flow."
            falsifiableClaim =
                "The lagged-return coefficient and its illiquidity interaction must be negative and survive observed taker costs."
            expectedSign = ExpectedSign.NEGATIVE
            horizon = Horizon.Duration(15L * MINUTE)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy = "Lock 15-minute return and liquidity windows before the final 60 capture days."
            cite(AcademicReferences.NAGEL_EVAPORATING_LIQUIDITY)
            requirePointInTimeUniverseData(history = 180L * DAY)
            requireData(executionBboRequirement(history = 180L * DAY))
            requireData(executionBookRequirement(history = 180L * DAY))
        }
        universe(pointInTimeUniverse())
        feature(
            "depth_imbalance",
            "Normalized top-book bid/ask depth imbalance.",
            binary(
                BinaryOperation.DIVIDE,
                binary(
                    BinaryOperation.SUBTRACT,
                    field("hyperliquid_l2", "bid_quantity"),
                    field("hyperliquid_l2", "ask_quantity"),
                ),
                binary(
                    BinaryOperation.ADD,
                    field("hyperliquid_l2", "bid_quantity"),
                    field("hyperliquid_l2", "ask_quantity"),
                ),
            ),
        )
        feature(
            "illiquidity",
            "Relative spread divided by displayed top-book depth.",
            binary(
                BinaryOperation.DIVIDE,
                field("hyperliquid_bbo", "relative_spread"),
                binary(
                    BinaryOperation.ADD,
                    field("hyperliquid_l2", "bid_quantity"),
                    field("hyperliquid_l2", "ask_quantity"),
                ),
            ),
        )
        feature(
            "past_return_15m",
            "Completed trailing 15-minute mid-price return.",
            logReturn(field("hyperliquid_bbo", "mid_price"), Window.Duration(15L * MINUTE)),
        )
        feature(
            "reversal_interaction",
            "Negative past return interacted with current illiquidity.",
            binary(
                BinaryOperation.MULTIPLY,
                unary(UnaryOperation.NEGATE, feature("past_return_15m")),
                feature("illiquidity"),
            ),
        )
        target(
            ForecastTarget.Return(
                field("hyperliquid_bbo", "mid_price"),
                Horizon.Duration(15L * MINUTE),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true, l2PenaltyParameter = "l2_penalty"))
        parameters(
            ParameterSpec.DecimalChoices(
                "l2_penalty",
                listOf(FiniteDouble(0.0), FiniteDouble(1.0e-5), FiniteDouble(1.0e-3)),
            ),
        )
        validation(standardValidation(60L * DAY, 14L * DAY, 60L * DAY, 15L * MINUTE))
        execution(takerReplayExecution(latencyMillis = 500))
        metrics(*RETURN_FORECAST_METRICS, *TRADING_METRICS)
        failWhen("The negative coefficient or liquidity interaction fails dependence-aware inference.")
        failWhen("Reversal profits do not survive observed spread, depth, fees, and doubled costs.")
        promotion(researchPromotionGate())
    }
}

internal object HyperliquidBtcHourlyReturnReversalTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "hyperliquid-btc-hourly-return-reversal",
        name = "Hyperliquid BTC hourly return reversal",
        summary = "Forecast-only test of whether the latest completed BTC hourly return reverses in the next hour.",
        family = TheoryFamily.LIQUIDITY,
        maturity = TheoryMaturity.TIER_1,
        "btc",
        "hourly",
        "hyperliquid",
        "mean-reversion",
        "reversal",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis =
                "The latest completed one-hour Hyperliquid BTC perpetual return has a negative conditional " +
                    "relationship with the subsequent one-hour return."
            mechanism =
                "Short-lived overreaction and temporary price pressure can produce reversal even when the " +
                    "available candle history cannot identify the liquidity channel."
            falsifiableClaim =
                "The lagged-return slope must be negative and its sealed-holdout forecasts must beat the " +
                    "historical mean, zero return, and positive-return persistence controls."
            expectedSign = ExpectedSign.NEGATIVE
            horizon = Horizon.Duration(HOUR)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze the single BTC universe, one-hour feature and target, exact 200-day snapshot, and final " +
                    "30 days before fitting or reading any holdout label."
            cite(AcademicReferences.NAGEL_EVAPORATING_LIQUIDITY)
            cite(AcademicReferences.WEN_CRYPTO_INTRADAY_REVERSAL)
            requireData(
                requirement(
                    key = "hyperliquid_btc_hourly_candles",
                    observation = ObservationKind.CANDLE,
                    sources = listOf(HYPERLIQUID),
                    kinds = listOf(InstrumentKind.PERPETUAL),
                    sampling = Sampling.FixedDuration(HOUR),
                    fields = listOf("close"),
                    history = 200L * DAY,
                ),
            )
        }
        lineage(
            TheoryLineage(
                parentTheoryId = TheoryId("liquidity-conditioned-reversal"),
                parentPlanHash = TheoryPlanHasher.hash(LiquidityConditionedReversalTheory.compile()).hex,
                adaptationNotes =
                    "Feasibility adaptation for the immutable 200-day Hyperliquid BTC hourly-candle snapshot. " +
                        "It tests only the unconditional lagged-return reversal implication, omits liquidity " +
                        "conditioning and historical execution, and is not a reproduction of the parent strategy.",
            ),
        )
        universe(
            dev.marketlab.theory.PointInTimeUniverse(
                selection =
                    UniverseSelection.Explicit(
                        listOf(InstrumentId("hyperliquid:perpetual:BTC")),
                    ),
                venues = listOf(HYPERLIQUID),
                instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                minimumListingAgeMillis = 0,
                requireActiveAtDecisionTime = false,
            ),
        )
        feature(
            "return_1h",
            "Latest completed close-to-close one-hour BTC perpetual log return.",
            logReturn(
                field("hyperliquid_btc_hourly_candles", "close"),
                Window.Bars(1),
            ),
        )
        target(
            ForecastTarget.Return(
                price = field("hyperliquid_btc_hourly_candles", "close"),
                horizon = Horizon.Duration(HOUR),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(
            standardValidation(
                minimumTraining = 90L * DAY,
                test = 7L * DAY,
                holdout = 30L * DAY,
                purge = HOUR,
                foldCount = 11,
            ),
        )
        execution(forecastOnlyExecution())
        metrics(*RETURN_FORECAST_METRICS)
        failWhen("The fitted lagged-return coefficient is not negative in every development fold and holdout fit.")
        failWhen("Sealed-holdout squared error is not lower than the expanding historical-mean forecast.")
        failWhen("Sealed-holdout squared error is not lower than the zero-return forecast.")
        failWhen("Sealed-holdout squared error is not lower than positive-return persistence.")
        promotion(forecastEvidenceGate())
    }
}

internal object CrossVenueLeadLagTheory : FixedTheoryProvider() {
    override val descriptor = descriptor(
        id = "cross-venue-lead-lag",
        name = "Cross-venue price discovery and lead-lag",
        summary = "Test whether causally observed leader-market moves forecast Hyperliquid at sub-second horizons.",
        family = TheoryFamily.PRICE_DISCOVERY,
        maturity = TheoryMaturity.TIER_1,
        "cross-venue",
        "lead-lag",
        "price-discovery",
    )

    override fun buildPlan(): TheoryPlan = theory(descriptor) {
        research {
            hypothesis = "Price and flow innovations on a liquid leader venue precede corresponding Hyperliquid moves."
            mechanism = "Fragmented venues incorporate information at different speeds because of liquidity, fee, and participant differences."
            falsifiableClaim =
                "Leader returns and relative prices must improve 500 ms Hyperliquid forecasts using only data received before decision time."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(500)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy = "Clock mapping, maximum skew, venue pair, horizon, and latency are frozen before the last 30 days."
            cite(AcademicReferences.ALBERS_BITCOIN_FRAGMENTATION)
            cite(AcademicReferences.BRANDVOLD_BITCOIN_PRICE_DISCOVERY)
            requirePointInTimeUniverseData(history = 90L * DAY)
            requireData(
                requirement(
                    "leader_bbo",
                    ObservationKind.BBO,
                    listOf(BINANCE),
                    listOf(InstrumentKind.PERPETUAL),
                    Sampling.EventTime,
                    listOf("mid_price", "relative_spread"),
                    90L * DAY,
                    maximumAvailabilityLagMillis = SECOND,
                ),
            )
            requireData(executionBboRequirement(history = 90L * DAY))
            requireData(executionBookRequirement(history = 90L * DAY))
        }
        universe(pointInTimeUniverse(limit = 1))
        feature(
            "leader_return_500ms",
            "Leader-venue return over the trailing 500 ms, gated by causal receive time.",
            logReturn(field("leader_bbo", "mid_price"), Window.Duration(500)),
        )
        feature(
            "relative_price",
            "Leader mid-price relative to the latest causally available Hyperliquid mid-price.",
            binary(
                BinaryOperation.SUBTRACT,
                unary(UnaryOperation.LOG, field("leader_bbo", "mid_price")),
                unary(UnaryOperation.LOG, field("hyperliquid_bbo", "mid_price")),
            ),
        )
        target(
            ForecastTarget.Return(
                field("hyperliquid_bbo", "mid_price"),
                Horizon.Duration(500),
            ),
        )
        estimator(EstimatorSpec.Linear(intercept = true, l2PenaltyParameter = "l2_penalty"))
        parameters(
            ParameterSpec.DecimalChoices(
                "l2_penalty",
                listOf(FiniteDouble(0.0), FiniteDouble(1.0e-5), FiniteDouble(1.0e-3)),
            ),
        )
        validation(standardValidation(30L * DAY, 7L * DAY, 30L * DAY, 2L * SECOND))
        execution(takerReplayExecution(latencyMillis = 250))
        metrics(*RETURN_FORECAST_METRICS, *TRADING_METRICS)
        failWhen("The effect disappears under receive-time ordering or plausible inter-venue clock-skew bounds.")
        failWhen("Taker economics are non-positive at the normal fee tier or doubled costs.")
        promotion(researchPromotionGate())
    }
}

private fun executionBboRequirement(history: Long) = requirement(
    key = "hyperliquid_bbo",
    observation = ObservationKind.BBO,
    sources = listOf(HYPERLIQUID),
    kinds = listOf(InstrumentKind.PERPETUAL),
    sampling = Sampling.EventTime,
    fields = listOf("ask_price", "ask_quantity", "bid_price", "bid_quantity", "mid_price", "relative_spread"),
    history = history,
    maximumAvailabilityLagMillis = 2L * SECOND,
)

private fun executionBookRequirement(history: Long) = requirement(
    key = "hyperliquid_l2",
    observation = ObservationKind.L2_BOOK,
    sources = listOf(HYPERLIQUID),
    kinds = listOf(InstrumentKind.PERPETUAL),
    sampling = Sampling.EventTime,
    fields = listOf("ask_quantity", "asks", "bid_quantity", "bids"),
    history = history,
    maximumAvailabilityLagMillis = 2L * SECOND,
)

object AcademicTheoryRegistry {
    val providers: List<TheoryProvider> = listOf(
        BuyAndHoldControl,
        FlatSignalControl,
        HistoricalMeanControl,
        PersistenceControl,
        RandomWalkControl,
        HarRealizedVolatilityTheory,
        HyperliquidBtcFourHourLogHarVarianceTheory,
        HyperliquidEthHourlyVolatilityPeriodicityTheory,
        FundingBasisCarryTheory,
        HyperliquidBtcTimeSeriesMomentumTheory,
        TimeSeriesMomentumTheory,
        OrderFlowQueueImbalanceTheory,
        LiquidityConditionedReversalTheory,
        HyperliquidBtcHourlyReturnReversalTheory,
        CrossVenueLeadLagTheory,
        SocialAttentionHourlyVarianceTheory,
        SocialDisagreementHourlyVarianceTheory,
        SocialPolarityFifteenMinuteReturnTheory,
        NewsNegativityDailyVarianceTheory,
        CrossSectionalSocialAttentionReturnTheory,
    ).sortedBy { it.descriptor.id.value }

    init {
        require(providers.map { it.descriptor.id }.distinct().size == providers.size) {
            "academic theory ids must be unique"
        }
    }

    fun find(id: TheoryId): TheoryProvider? = providers.firstOrNull { it.descriptor.id == id }

    fun compileAll(): List<TheoryPlan> = providers.map { it.compile() }

    fun parameterSpaces(): Map<TheoryId, ParameterSpace> =
        compileAll().associate { it.descriptor.id to it.parameterSpace }
}
