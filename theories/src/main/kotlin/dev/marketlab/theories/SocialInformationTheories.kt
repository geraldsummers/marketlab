package dev.marketlab.theories

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.theory.Aggregation
import dev.marketlab.theory.BinaryOperation
import dev.marketlab.theory.EstimatorSpec
import dev.marketlab.theory.ExpectedSign
import dev.marketlab.theory.FamilywiseCorrection
import dev.marketlab.theory.FoldScheme
import dev.marketlab.theory.ForecastTarget
import dev.marketlab.theory.Horizon
import dev.marketlab.theory.Metric
import dev.marketlab.theory.ReproductionKind
import dev.marketlab.theory.TheoryFamily
import dev.marketlab.theory.TheoryMaturity
import dev.marketlab.theory.ValidationSpec
import dev.marketlab.theory.Window
import dev.marketlab.theory.binary
import dev.marketlab.theory.feature
import dev.marketlab.theory.field
import dev.marketlab.theory.rolling
import dev.marketlab.theory.theory
import dev.marketlab.theory.unary
import dev.marketlab.theory.UnaryOperation

private val BLUESKY = DataSourceId("bluesky-jetstream")
private val NOSTR = DataSourceId("nostr-public-relays")
private val FARCASTER = DataSourceId("farcaster-snapchain")
private val GDELT = DataSourceId("gdelt-gkg-2.1")
private val CRYPTO_RSS = DataSourceId("official-crypto-rss")

private const val SOCIAL_PROGRAM_HISTORY = 270L * DAY

private val SOCIAL_FEATURES =
    requirement(
        key = "social_features",
        observation = ObservationKind.SOCIAL_MESSAGE,
        sources = listOf(BLUESKY, NOSTR, FARCASTER),
        kinds = listOf(InstrumentKind.PERPETUAL),
        sampling = Sampling.FixedDuration(15L * MINUTE),
        fields =
            listOf(
                "attention_rank_24h",
                "attention_shock_1h",
                "disagreement_1h",
                "polarity_15m",
                "source_coverage",
            ),
        history = SOCIAL_PROGRAM_HISTORY,
        maximumAvailabilityLagMillis = 5L * MINUTE,
    )

private val NEWS_FEATURES =
    requirement(
        key = "news_features",
        observation = ObservationKind.NEWS_ITEM,
        sources = listOf(GDELT, CRYPTO_RSS),
        kinds = listOf(InstrumentKind.PERPETUAL),
        sampling = Sampling.FixedDuration(HOUR),
        fields = listOf("negative_intensity_24h", "source_coverage"),
        history = SOCIAL_PROGRAM_HISTORY,
        maximumAvailabilityLagMillis = 30L * MINUTE,
    )

private val FIVE_MINUTE_CANDLES =
    requirement(
        key = "hyperliquid_five_minute_candles",
        observation = ObservationKind.CANDLE,
        sources = listOf(HYPERLIQUID),
        kinds = listOf(InstrumentKind.PERPETUAL),
        sampling = Sampling.FixedDuration(5L * MINUTE),
        fields = listOf("base_volume", "close"),
        history = SOCIAL_PROGRAM_HISTORY,
    )

private val SOCIAL_MARKET_CONTROLS =
    requirement(
        key = "social_market_controls",
        observation = ObservationKind.CANDLE,
        sources = listOf(HYPERLIQUID),
        kinds = listOf(InstrumentKind.PERPETUAL),
        sampling = Sampling.FixedDuration(15L * MINUTE),
        fields = listOf("btc_market_return_15m", "close"),
        history = SOCIAL_PROGRAM_HISTORY,
    )

private fun socialUniverse() =
    pointInTimeUniverse(
        limit = 10,
        trailingWindowMillis = 30L * DAY,
        minimumListingAgeMillis = 90L * DAY,
    )

private fun socialValidation(purge: Long) =
    ValidationSpec(
        scheme = FoldScheme.EXPANDING,
        foldCount = 4,
        minimumTrainingMillis = 120L * DAY,
        testMillis = 14L * DAY,
        purgeMillis = purge,
        embargoMillis = purge,
        sealedFinalHoldoutMillis = 60L * DAY,
        tuneInsideEachFold = false,
        dependenceAwareInference = true,
        familywiseCorrection = FamilywiseCorrection.HOLM,
    )

private fun socialUniverseRequirements(builder: dev.marketlab.theory.ResearchCardBuilder) {
    with(builder) {
        requirePointInTimeUniverseData(history = SOCIAL_PROGRAM_HISTORY)
        requireData(FIVE_MINUTE_CANDLES)
    }
}

private fun fiveMinuteReturn() =
    logReturn(field("hyperliquid_five_minute_candles", "close"), Window.Bars(1))

private fun logVariance(window: Long, minimumObservations: Int) =
    unary(
        UnaryOperation.LOG,
        binary(
            BinaryOperation.MAXIMUM,
            rolling(
                unary(UnaryOperation.SQUARE, fiveMinuteReturn()),
                Window.Duration(window),
                Aggregation.SUM,
                minimumObservations,
            ),
            dev.marketlab.theory.constant(1.0e-12),
        ),
    )

internal object SocialAttentionHourlyVarianceTheory : FixedTheoryProvider() {
    override val descriptor =
        descriptor(
            id = "hyperliquid-top-ten-social-attention-hourly-variance",
            name = "Hyperliquid top-ten social attention and hourly variance",
            summary = "Tests whether abnormal public-message volume improves next-hour variance forecasts.",
            family = TheoryFamily.INFORMATION,
            maturity = TheoryMaturity.TIER_1,
            "attention",
            "hyperliquid",
            "social",
            "volatility",
        )

    override fun buildPlan() = theory(descriptor) {
        research {
            hypothesis = "Abnormal asset-specific social attention precedes higher next-hour realized variance."
            mechanism =
                "A burst of public attention proxies information arrival and heterogeneous investor reaction."
            falsifiableClaim =
                "The fixed attention model must reduce sealed-holdout QLIKE versus the clock-free dynamic variance control and retain a positive attention coefficient."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(HOUR)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze sources, entity rules, model artifacts, top-ten ranking, and the common final 60 days before day four of prospective capture."
            cite(AcademicReferences.LIU_TSYVINSKI_CRYPTOCURRENCY_RETURNS)
            cite(AcademicReferences.SUARDI_RASEL_LIU_TWEET_SENTIMENT)
            requireData(SOCIAL_FEATURES)
            socialUniverseRequirements(this)
        }
        universe(socialUniverse())
        feature("attention_shock", "Source-standardized abnormal message count over the latest causal hour.", field("social_features", "attention_shock_1h"))
        feature("log_rv_1h", "Log realized variance from the trailing completed hour.", logVariance(HOUR, 12))
        feature("log_rv_24h", "Log realized variance from the trailing completed day.", logVariance(DAY, 288))
        target(ForecastTarget.LogRealizedVariance(fiveMinuteReturn(), Horizon.Duration(HOUR)))
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(socialValidation(HOUR))
        execution(forecastOnlyExecution())
        metrics(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE)
        failWhen("Fewer than two primary social sources are causally available for a decision bucket.")
        failWhen("The attention coefficient is non-positive in any required sealed comparison.")
        failWhen("The global five-theory Holm-adjusted evidence threshold is not met.")
        promotion(forecastEvidenceGate())
    }
}

internal object SocialDisagreementHourlyVarianceTheory : FixedTheoryProvider() {
    override val descriptor =
        descriptor(
            id = "hyperliquid-top-ten-social-disagreement-hourly-variance",
            name = "Hyperliquid top-ten social disagreement and hourly variance",
            summary = "Tests whether dispersion in public sentiment improves next-hour variance forecasts.",
            family = TheoryFamily.INFORMATION,
            maturity = TheoryMaturity.TIER_1,
            "disagreement",
            "hyperliquid",
            "sentiment",
            "volatility",
        )

    override fun buildPlan() = theory(descriptor) {
        research {
            hypothesis = "Greater disagreement in asset-specific public sentiment precedes higher next-hour realized variance."
            mechanism = "Divergent beliefs induce trading and price discovery as investors act on conflicting interpretations."
            falsifiableClaim =
                "The fixed disagreement model must reduce sealed-holdout QLIKE versus the identical dynamic variance control and retain a positive disagreement coefficient."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(HOUR)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Use the same frozen models, sources, universe decisions, and common 60-day holdout as the complete social family."
            cite(AcademicReferences.SUARDI_RASEL_LIU_TWEET_SENTIMENT)
            requireData(SOCIAL_FEATURES)
            socialUniverseRequirements(this)
        }
        universe(socialUniverse())
        feature("disagreement", "Cross-message standard deviation of causal sentiment polarity.", field("social_features", "disagreement_1h"))
        feature("log_rv_1h", "Log realized variance from the trailing completed hour.", logVariance(HOUR, 12))
        feature("log_rv_24h", "Log realized variance from the trailing completed day.", logVariance(DAY, 288))
        target(ForecastTarget.LogRealizedVariance(fiveMinuteReturn(), Horizon.Duration(HOUR)))
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(socialValidation(HOUR))
        execution(forecastOnlyExecution())
        metrics(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE)
        failWhen("Fewer than two primary social sources are causally available for a decision bucket.")
        failWhen("The disagreement coefficient is non-positive.")
        failWhen("The global five-theory Holm-adjusted evidence threshold is not met.")
        promotion(forecastEvidenceGate())
    }
}

internal object SocialPolarityFifteenMinuteReturnTheory : FixedTheoryProvider() {
    override val descriptor =
        descriptor(
            id = "hyperliquid-top-ten-social-polarity-fifteen-minute-return",
            name = "Hyperliquid top-ten social polarity and fifteen-minute returns",
            summary = "Tests whether causal public sentiment predicts executable short-horizon returns.",
            family = TheoryFamily.INFORMATION,
            maturity = TheoryMaturity.TIER_1,
            "hyperliquid",
            "return",
            "sentiment",
            "social",
        )

    override fun buildPlan() = theory(descriptor) {
        research {
            hypothesis = "More positive asset-specific social sentiment predicts a higher subsequent fifteen-minute return."
            mechanism = "Public information diffuses into fragmented crypto prices with a short delay."
            falsifiableClaim =
                "The fixed polarity forecast must beat asset mean, zero, latest-return, and BTC-market controls and remain profitable in observed-book replay."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(15L * MINUTE)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze the fifteen-minute horizon, positive sign, 500 ms latency, costs, and common 60-day holdout before prospective capture."
            cite(AcademicReferences.GUEGAN_RENAULT_SOCIAL_SENTIMENT)
            requireData(SOCIAL_FEATURES)
            requireData(SOCIAL_MARKET_CONTROLS)
            requireData(socialExecutionBbo())
            requireData(socialExecutionBook())
            socialUniverseRequirements(this)
        }
        universe(socialUniverse())
        feature("btc_market_return", "Completed BTC market return over the causal fifteen-minute feature window.", field("social_market_controls", "btc_market_return_15m"))
        feature("latest_return", "Latest completed asset return over fifteen minutes.", logReturn(field("social_market_controls", "close"), Window.Bars(1)))
        feature("polarity", "Equal-source causal social polarity over the latest fifteen minutes.", field("social_features", "polarity_15m"))
        target(ForecastTarget.Return(field("social_market_controls", "close"), Horizon.Duration(15L * MINUTE)))
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(socialValidation(15L * MINUTE))
        execution(takerReplayExecution(latencyMillis = 500))
        metrics(*RETURN_FORECAST_METRICS, *TRADING_METRICS)
        failWhen("The polarity coefficient is non-positive.")
        failWhen("Any mandatory forecast control has lower sealed-holdout squared error.")
        failWhen("Net return is non-positive at normal or doubled observed costs.")
        failWhen("The global five-theory Holm-adjusted evidence threshold is not met.")
        promotion(researchPromotionGate())
    }
}

internal object NewsNegativityDailyVarianceTheory : FixedTheoryProvider() {
    override val descriptor =
        descriptor(
            id = "hyperliquid-top-ten-news-negativity-daily-variance",
            name = "Hyperliquid top-ten news negativity and daily variance",
            summary = "Tests whether negative public news improves daily HAR-style variance forecasts.",
            family = TheoryFamily.INFORMATION,
            maturity = TheoryMaturity.TIER_1,
            "har",
            "hyperliquid",
            "news",
            "sentiment",
            "volatility",
        )

    override fun buildPlan() = theory(descriptor) {
        research {
            hypothesis = "Greater asset-specific news negativity precedes higher next-day realized variance."
            mechanism = "Adverse narratives increase uncertainty, belief dispersion, and risk-sensitive repositioning."
            falsifiableClaim =
                "Adding the fixed negative-news intensity must reduce sealed-holdout QLIKE versus the same 1/7/30-day log-HAR control and retain a positive coefficient."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(DAY)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze GDELT/RSS transformations, FinBERT identity, UTC daily origin, and common 60-day holdout before day four."
            cite(AcademicReferences.BRAUNEIS_SAHINER_CRYPTO_HAR)
            requireData(NEWS_FEATURES)
            socialUniverseRequirements(this)
        }
        universe(socialUniverse())
        feature("log_rv_1d", "Log realized variance over the trailing completed day.", logVariance(DAY, 288))
        feature("log_rv_7d", "Log realized variance over the trailing seven completed days.", logVariance(7L * DAY, 2016))
        feature("log_rv_30d", "Log realized variance over the trailing thirty completed days.", logVariance(30L * DAY, 8640))
        feature("news_negativity", "Source-standardized negative-news intensity over the latest causal day.", field("news_features", "negative_intensity_24h"))
        target(ForecastTarget.LogRealizedVariance(fiveMinuteReturn(), Horizon.Duration(DAY)))
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(socialValidation(DAY))
        execution(forecastOnlyExecution())
        metrics(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE)
        failWhen("The negativity coefficient is non-positive.")
        failWhen("QLIKE is not below the identical clock-free log-HAR control.")
        failWhen("The global five-theory Holm-adjusted evidence threshold is not met.")
        promotion(forecastEvidenceGate())
    }
}

internal object CrossSectionalSocialAttentionReturnTheory : FixedTheoryProvider() {
    override val descriptor =
        descriptor(
            id = "hyperliquid-top-ten-cross-sectional-social-attention-return",
            name = "Hyperliquid top-ten cross-sectional social attention returns",
            summary = "Tests next-day relative returns following abnormal asset-specific public attention.",
            family = TheoryFamily.INFORMATION,
            maturity = TheoryMaturity.TIER_1,
            "attention",
            "cross-sectional",
            "hyperliquid",
            "return",
        )

    override fun buildPlan() = theory(descriptor) {
        research {
            hypothesis = "Top-ten assets with unusually high public attention outperform low-attention peers over the next day."
            mechanism = "Gradual diffusion of asset-specific retail attention creates temporary cross-sectional demand pressure."
            falsifiableClaim =
                "Attention rank must positively predict next-day returns beyond momentum and liquidity and a top-three minus bottom-three portfolio must survive observed costs."
            expectedSign = ExpectedSign.POSITIVE
            horizon = Horizon.Duration(DAY)
            reproductionKind = ReproductionKind.REGISTERED_ADAPTATION
            confirmationPeriodPolicy =
                "Freeze weekly point-in-time top-ten membership, daily thirds, controls, execution, and common 60-day holdout before collection."
            cite(AcademicReferences.MAITRE_PUGACHYOV_WEIGERT_ATTENTION)
            cite(AcademicReferences.SUARDI_RASEL_LIU_TWEET_SENTIMENT)
            requireData(SOCIAL_FEATURES)
            requireData(SOCIAL_MARKET_CONTROLS)
            requireData(socialExecutionBbo())
            requireData(socialExecutionBook())
            socialUniverseRequirements(this)
        }
        universe(socialUniverse())
        feature("attention_rank", "Cross-sectional rank of abnormal trailing-day attention within the point-in-time top ten.", field("social_features", "attention_rank_24h"))
        feature("momentum_1d", "Latest completed one-day asset return.", logReturn(field("social_market_controls", "close"), Window.Duration(DAY)))
        target(ForecastTarget.Return(field("social_market_controls", "close"), Horizon.Duration(DAY)))
        estimator(EstimatorSpec.Linear(intercept = true))
        parameters()
        validation(socialValidation(DAY))
        execution(takerReplayExecution(latencyMillis = 500))
        metrics(*RETURN_FORECAST_METRICS, *TRADING_METRICS)
        failWhen("Fewer than eight eligible assets or fifty complete holdout dates remain.")
        failWhen("The attention-rank coefficient or top-three minus bottom-three net return is non-positive.")
        failWhen("The result does not survive momentum, liquidity, normal-cost, and doubled-cost controls.")
        failWhen("The global five-theory Holm-adjusted evidence threshold is not met.")
        promotion(researchPromotionGate())
    }
}

private fun socialExecutionBbo() =
    requirement(
        key = "hyperliquid_social_bbo",
        observation = ObservationKind.BBO,
        sources = listOf(HYPERLIQUID),
        kinds = listOf(InstrumentKind.PERPETUAL),
        sampling = Sampling.EventTime,
        fields = listOf("ask_price", "ask_quantity", "bid_price", "bid_quantity", "mid_price", "relative_spread"),
        history = SOCIAL_PROGRAM_HISTORY,
        maximumAvailabilityLagMillis = 2L * SECOND,
    )

private fun socialExecutionBook() =
    requirement(
        key = "hyperliquid_social_l2",
        observation = ObservationKind.L2_BOOK,
        sources = listOf(HYPERLIQUID),
        kinds = listOf(InstrumentKind.PERPETUAL),
        sampling = Sampling.EventTime,
        fields = listOf("ask_quantity", "asks", "bid_quantity", "bids"),
        history = SOCIAL_PROGRAM_HISTORY,
        maximumAvailabilityLagMillis = 2L * SECOND,
    )
