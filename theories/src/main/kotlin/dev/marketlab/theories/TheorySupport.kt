package dev.marketlab.theories

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.theory.BinaryOperation
import dev.marketlab.theory.Comparison
import dev.marketlab.theory.ExecutionMode
import dev.marketlab.theory.ExecutionProfile
import dev.marketlab.theory.FamilywiseCorrection
import dev.marketlab.theory.FoldScheme
import dev.marketlab.theory.Metric
import dev.marketlab.theory.MetricCriterion
import dev.marketlab.theory.NumericExpression
import dev.marketlab.theory.PromotionGate
import dev.marketlab.theory.PointInTimeUniverse
import dev.marketlab.theory.TheoryDescriptor
import dev.marketlab.theory.TheoryFamily
import dev.marketlab.theory.TheoryMaturity
import dev.marketlab.theory.ResearchCardBuilder
import dev.marketlab.theory.UnaryOperation
import dev.marketlab.theory.ValidationSpec
import dev.marketlab.theory.Window
import dev.marketlab.theory.UniverseSelection
import dev.marketlab.theory.binary
import dev.marketlab.theory.lag
import dev.marketlab.theory.unary

internal const val SECOND: Long = 1_000L
internal const val MINUTE: Long = 60L * SECOND
internal const val HOUR: Long = 60L * MINUTE
internal const val DAY: Long = 24L * HOUR

internal val HYPERLIQUID = DataSourceId("hyperliquid-mainnet")
internal val BINANCE = DataSourceId("binance-mainnet")
internal val KRAKEN = DataSourceId("kraken-mainnet")

internal fun descriptor(
    id: String,
    name: String,
    summary: String,
    family: TheoryFamily,
    maturity: TheoryMaturity,
    vararg tags: String,
): TheoryDescriptor = TheoryDescriptor(
    id = TheoryId(id),
    version = "1.0.0",
    name = name,
    summary = summary,
    family = family,
    maturity = maturity,
    tags = tags.distinct().sorted(),
)

internal fun requirement(
    key: String,
    observation: ObservationKind,
    sources: List<DataSourceId>,
    kinds: List<InstrumentKind>,
    sampling: Sampling,
    fields: List<String>,
    history: Long,
    maximumAvailabilityLagMillis: Long? = null,
): DataRequirement = DataRequirement(
    key = key,
    observation = observation,
    sourcePreference = sources,
    instrumentKinds = kinds,
    sampling = sampling,
    requiredFields = fields.distinct().sorted(),
    minimumHistoryMillis = history,
    maximumAvailabilityLagMillis = maximumAvailabilityLagMillis,
)

internal fun logReturn(price: NumericExpression, lagWindow: Window): NumericExpression = binary(
    BinaryOperation.SUBTRACT,
    unary(UnaryOperation.LOG, price),
    unary(UnaryOperation.LOG, lag(price, lagWindow)),
)

internal fun standardValidation(
    minimumTraining: Long,
    test: Long,
    holdout: Long,
    purge: Long,
    foldCount: Int = 5,
): ValidationSpec = ValidationSpec(
    scheme = FoldScheme.EXPANDING,
    foldCount = foldCount,
    minimumTrainingMillis = minimumTraining,
    testMillis = test,
    purgeMillis = purge,
    embargoMillis = purge,
    sealedFinalHoldoutMillis = holdout,
    familywiseCorrection = FamilywiseCorrection.SPA_AND_FDR,
)

internal fun forecastOnlyExecution(): ExecutionProfile = ExecutionProfile(
    mode = ExecutionMode.FORECAST_ONLY,
    targetVenue = HYPERLIQUID,
    decisionLatencyMillis = 0,
    requireObservedQuotes = false,
    requireObservedDepth = false,
    includeFees = false,
    includeFunding = false,
)

internal fun takerReplayExecution(latencyMillis: Long): ExecutionProfile = ExecutionProfile(
    mode = ExecutionMode.TAKER_BOOK_REPLAY,
    targetVenue = HYPERLIQUID,
    decisionLatencyMillis = latencyMillis,
    requireObservedQuotes = true,
    requireObservedDepth = true,
    includeFees = true,
    includeFunding = true,
)

internal fun researchPromotionGate(): PromotionGate = PromotionGate(
    criteria = listOf(
        MetricCriterion(
            metric = Metric.DEFLATED_SHARPE,
            comparison = Comparison.GREATER_THAN,
            threshold = FiniteDouble(0.0),
            description = "Deflated Sharpe must remain positive after accounting for the complete trial family.",
        ),
        MetricCriterion(
            metric = Metric.MAX_DRAWDOWN,
            comparison = Comparison.LESS_THAN_OR_EQUAL,
            threshold = FiniteDouble(0.10),
            description = "Maximum drawdown must not exceed 10%.",
        ),
        MetricCriterion(
            metric = Metric.NET_RETURN,
            comparison = Comparison.GREATER_THAN,
            threshold = FiniteDouble(0.0),
            description = "Net return must be positive after all registered execution costs.",
        ),
        MetricCriterion(
            metric = Metric.SPA_P_VALUE,
            comparison = Comparison.LESS_THAN,
            threshold = FiniteDouble(0.05),
            description = "Superior Predictive Ability test must reject the registered null family.",
        ),
    ),
)

internal fun pointInTimeUniverse(
    venues: List<DataSourceId> = listOf(HYPERLIQUID),
    instrumentKinds: List<InstrumentKind> = listOf(InstrumentKind.PERPETUAL),
    limit: Int = 20,
    trailingWindowMillis: Long = 30L * DAY,
    minimumListingAgeMillis: Long = 90L * DAY,
): PointInTimeUniverse = PointInTimeUniverse(
    selection = UniverseSelection.TopByTrailingNotional(
        limit = limit,
        trailingWindowMillis = trailingWindowMillis,
    ),
    venues = venues.distinct().sortedBy(DataSourceId::value),
    instrumentKinds = instrumentKinds.distinct().sortedBy(InstrumentKind::name),
    minimumListingAgeMillis = minimumListingAgeMillis,
)

internal fun ResearchCardBuilder.requirePointInTimeUniverseData(
    venues: List<DataSourceId> = listOf(HYPERLIQUID),
    instrumentKinds: List<InstrumentKind> = listOf(InstrumentKind.PERPETUAL),
    history: Long,
) {
    requireData(
        requirement(
            key = "universe_instrument_metadata",
            observation = ObservationKind.INSTRUMENT_METADATA,
            sources = venues,
            kinds = instrumentKinds,
            sampling = Sampling.EventTime,
            fields = listOf("active", "lot_size", "tick_size"),
            history = history,
        ),
    )
    requireData(
        requirement(
            key = "universe_volume_candles",
            observation = ObservationKind.CANDLE,
            sources = venues,
            kinds = instrumentKinds,
            sampling = Sampling.FixedDuration(DAY),
            fields = listOf("base_volume", "close"),
            history = history,
        ),
    )
}

internal fun controlGate(): PromotionGate = PromotionGate(
    criteria = emptyList(),
    eligibleForPaper = false,
    requireHyperliquidHoldout = true,
)

internal fun forecastEvidenceGate(): PromotionGate = PromotionGate(
    criteria = listOf(
        MetricCriterion(
            metric = Metric.SPA_P_VALUE,
            comparison = Comparison.LESS_THAN,
            threshold = FiniteDouble(0.05),
            description = "Forecast evidence must survive the registered multiple-testing correction.",
        ),
    ),
    eligibleForPaper = false,
    requireHyperliquidHoldout = true,
)

internal val RETURN_FORECAST_METRICS = arrayOf(
    Metric.DIRECTIONAL_ACCURACY,
    Metric.MAE,
    Metric.RMSE,
    Metric.SPA_P_VALUE,
)

internal val TRADING_METRICS = arrayOf(
    Metric.CAPACITY,
    Metric.DEFLATED_SHARPE,
    Metric.FEES,
    Metric.FUNDING,
    Metric.MAX_DRAWDOWN,
    Metric.NET_RETURN,
    Metric.PROBABILITY_OF_BACKTEST_OVERFITTING,
    Metric.SHARPE,
    Metric.SLIPPAGE,
    Metric.SPA_P_VALUE,
    Metric.TURNOVER,
)
