package dev.marketlab.theory

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.worker.WorkerRuntime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TheoryFamily {
    NULL_BASELINE,
    VOLATILITY,
    CARRY,
    MOMENTUM,
    MICROSTRUCTURE,
    LIQUIDITY,
    PRICE_DISCOVERY,
    INFORMATION,
}

@Serializable
enum class TheoryMaturity {
    CONTROL,
    TIER_1,
    TIER_2,
    TIER_3,
}

@Serializable
data class TheoryDescriptor(
    val id: TheoryId,
    val version: String,
    val name: String,
    val summary: String,
    val family: TheoryFamily,
    val maturity: TheoryMaturity,
    val tags: List<String>,
) {
    init {
        require(version.isNotBlank()) { "theory version cannot be blank" }
        require(name.isNotBlank()) { "theory name cannot be blank" }
        require(summary.isNotBlank()) { "theory summary cannot be blank" }
        require(tags == tags.distinct().sorted()) { "theory tags must be unique and sorted" }
    }
}

@Serializable
enum class EvidenceRole {
    SUPPORTING,
    CONTRADICTORY,
    METHODOLOGY,
}

@Serializable
enum class PublicationKind {
    PEER_REVIEWED_ARTICLE,
    WORKING_PAPER,
    BOOK,
}

@Serializable
data class Citation(
    val key: String,
    val authors: List<String>,
    val year: Int,
    val title: String,
    val venue: String,
    val locator: String,
    val kind: PublicationKind,
    val role: EvidenceRole,
    val relevance: String,
) {
    init {
        require(key.isNotBlank()) { "citation key cannot be blank" }
        require(authors.isNotEmpty() && authors.none(String::isBlank)) { "citation authors cannot be empty" }
        require(year in 1900..2100) { "citation year is implausible" }
        require(title.isNotBlank()) { "citation title cannot be blank" }
        require(venue.isNotBlank()) { "citation venue cannot be blank" }
        require(locator.startsWith("https://")) { "citation locator must be an HTTPS URL" }
        require(relevance.isNotBlank()) { "citation relevance cannot be blank" }
    }
}

@Serializable
enum class ExpectedSign {
    POSITIVE,
    NEGATIVE,
    NON_NEGATIVE,
    NON_POSITIVE,
    TWO_SIDED,
    LOWER_ERROR_THAN_BASELINE,
}

@Serializable
sealed interface Horizon {
    @Serializable
    @SerialName("duration")
    data class Duration(val millis: Long) : Horizon {
        init {
            require(millis > 0) { "horizon duration must be positive" }
        }
    }

    @Serializable
    @SerialName("calendar_days")
    data class CalendarDays(val days: Int) : Horizon {
        init {
            require(days > 0) { "calendar-day horizon must be positive" }
        }
    }

    @Serializable
    @SerialName("bars")
    data class Bars(val count: Int, val barMillis: Long) : Horizon {
        init {
            require(count > 0) { "bar count must be positive" }
            require(barMillis > 0) { "bar duration must be positive" }
        }
    }

    @Serializable
    @SerialName("events")
    data class Events(val count: Int) : Horizon {
        init {
            require(count > 0) { "event count must be positive" }
        }
    }
}

@Serializable
enum class ReproductionKind {
    EXACT_REPRODUCTION,
    METHOD_REPRODUCTION,
    REGISTERED_ADAPTATION,
    CONTROL,
}

@Serializable
data class ResearchCard(
    val hypothesis: String,
    val mechanism: String,
    val falsifiableClaim: String,
    val expectedSign: ExpectedSign,
    val horizon: Horizon,
    val reproductionKind: ReproductionKind,
    val confirmationPeriodPolicy: String,
    val citations: List<Citation>,
    val dataRequirements: List<DataRequirement>,
) {
    init {
        require(hypothesis.isNotBlank()) { "hypothesis cannot be blank" }
        require(mechanism.isNotBlank()) { "mechanism cannot be blank" }
        require(falsifiableClaim.isNotBlank()) { "falsifiable claim cannot be blank" }
        require(confirmationPeriodPolicy.isNotBlank()) { "confirmation-period policy cannot be blank" }
        require(citations.isNotEmpty()) { "research card must cite primary literature" }
        require(citations.map(Citation::key) == citations.map(Citation::key).distinct().sorted()) {
            "citations must have unique, sorted keys"
        }
        require(dataRequirements.isNotEmpty()) { "research card must declare real-data requirements" }
        require(
            dataRequirements.map(DataRequirement::key) ==
                dataRequirements.map(DataRequirement::key).distinct().sorted(),
        ) {
            "data requirements must have unique, sorted keys"
        }
    }
}

@Serializable
data class TheoryLineage(
    val parentTheoryId: TheoryId? = null,
    val parentPlanHash: String? = null,
    val adaptationNotes: String? = null,
) {
    init {
        require((parentTheoryId == null) == (adaptationNotes == null)) {
            "an adaptation must identify both its parent and adaptation notes"
        }
        require(parentPlanHash == null || Regex("[0-9a-f]{64}").matches(parentPlanHash)) {
            "parent plan hash must be a lowercase SHA-256 digest"
        }
    }
}

@Serializable
sealed interface UniverseSelection {
    @Serializable
    @SerialName("explicit")
    data class Explicit(val instruments: List<InstrumentId>) : UniverseSelection {
        init {
            require(instruments.isNotEmpty()) { "explicit universe cannot be empty" }
            require(instruments == instruments.distinct().sortedBy(InstrumentId::value)) {
                "explicit universe instruments must be unique and sorted"
            }
        }
    }

    @Serializable
    @SerialName("top_by_trailing_notional")
    data class TopByTrailingNotional(
        val limit: Int,
        val trailingWindowMillis: Long,
    ) : UniverseSelection {
        init {
            require(limit > 0) { "universe limit must be positive" }
            require(trailingWindowMillis > 0) { "universe lookback must be positive" }
        }
    }
}

@Serializable
data class PointInTimeUniverse(
    val selection: UniverseSelection,
    val venues: List<DataSourceId>,
    val instrumentKinds: List<dev.marketlab.contracts.market.InstrumentKind>,
    val minimumListingAgeMillis: Long,
    val minimumTrailingNotional: DecimalValue? = null,
    val requireActiveAtDecisionTime: Boolean = true,
) {
    init {
        require(venues.isNotEmpty()) { "universe must contain at least one venue" }
        require(venues == venues.distinct().sortedBy(DataSourceId::value)) {
            "universe venues must be unique and sorted"
        }
        require(instrumentKinds.isNotEmpty()) { "universe must contain at least one instrument kind" }
        require(instrumentKinds == instrumentKinds.distinct().sortedBy { it.name }) {
            "universe instrument kinds must be unique and sorted"
        }
        require(minimumListingAgeMillis >= 0) { "minimum listing age cannot be negative" }
        require(minimumTrailingNotional == null || minimumTrailingNotional > DecimalValue.ZERO) {
            "minimum trailing notional must be positive"
        }
    }
}

@Serializable
sealed interface ForecastTarget {
    val horizon: Horizon

    @Serializable
    @SerialName("return")
    data class Return(
        val price: NumericExpression,
        override val horizon: Horizon,
        val logarithmic: Boolean = true,
    ) : ForecastTarget

    @Serializable
    @SerialName("realized_variance")
    data class RealizedVariance(
        val returnExpression: NumericExpression,
        override val horizon: Horizon,
    ) : ForecastTarget

    /**
     * A positive realized-variance target modeled on the log scale.
     *
     * Executors fit the declared estimator to log realized variance and
     * exponentiate its forecast before evaluating variance-scale losses such
     * as QLIKE. Keeping this as a distinct target preserves the canonical
     * semantics and hashes of existing raw-variance plans.
     */
    @Serializable
    @SerialName("log_realized_variance")
    data class LogRealizedVariance(
        val returnExpression: NumericExpression,
        override val horizon: Horizon,
    ) : ForecastTarget

    @Serializable
    @SerialName("direction")
    data class Direction(
        val returnExpression: NumericExpression,
        override val horizon: Horizon,
        val threshold: FiniteDouble = FiniteDouble(0.0),
    ) : ForecastTarget

    @Serializable
    @SerialName("carry_return")
    data class CarryReturn(
        val perpetualPrice: NumericExpression,
        val referencePrice: NumericExpression,
        val fundingRate: NumericExpression,
        override val horizon: Horizon,
    ) : ForecastTarget
}

@Serializable
sealed interface EstimatorSpec {
    @Serializable
    @SerialName("random_walk")
    data object RandomWalk : EstimatorSpec

    @Serializable
    @SerialName("historical_mean")
    data object HistoricalMean : EstimatorSpec

    @Serializable
    @SerialName("persistence")
    data class Persistence(val feature: String) : EstimatorSpec {
        init {
            require(feature.isNotBlank()) { "persistence feature cannot be blank" }
        }
    }

    @Serializable
    @SerialName("constant")
    data class Constant(val value: FiniteDouble) : EstimatorSpec

    @Serializable
    @SerialName("flat_signal")
    data object FlatSignal : EstimatorSpec

    @Serializable
    @SerialName("buy_and_hold")
    data object BuyAndHold : EstimatorSpec

    @Serializable
    @SerialName("linear")
    data class Linear(
        val intercept: Boolean = true,
        val l1PenaltyParameter: String? = null,
        val l2PenaltyParameter: String? = null,
        val runtime: WorkerRuntime = WorkerRuntime.KOTLIN,
    ) : EstimatorSpec

    @Serializable
    @SerialName("random_forest")
    data class RandomForest(
        val treeCountParameter: String,
        val maximumDepthParameter: String,
        val runtime: WorkerRuntime,
    ) : EstimatorSpec

    @Serializable
    @SerialName("shallow_neural_network")
    data class ShallowNeuralNetwork(
        val hiddenWidthParameter: String,
        val epochsParameter: String,
        val runtime: WorkerRuntime,
    ) : EstimatorSpec
}

@Serializable
sealed interface ParameterSpec {
    val name: String

    @Serializable
    @SerialName("integer")
    data class IntegerRange(
        override val name: String,
        val minimum: Long,
        val maximum: Long,
        val step: Long,
    ) : ParameterSpec {
        init {
            require(name.isNotBlank()) { "parameter name cannot be blank" }
            require(minimum <= maximum) { "integer parameter range is inverted" }
            require(step > 0) { "integer parameter step must be positive" }
        }
    }

    @Serializable
    @SerialName("decimal")
    data class DecimalChoices(
        override val name: String,
        val values: List<FiniteDouble>,
    ) : ParameterSpec {
        init {
            require(name.isNotBlank()) { "parameter name cannot be blank" }
            require(values.isNotEmpty()) { "decimal parameter choices cannot be empty" }
            require(values.distinct().size == values.size) { "decimal parameter choices must be unique" }
        }
    }

    @Serializable
    @SerialName("category")
    data class Category(
        override val name: String,
        val values: List<String>,
    ) : ParameterSpec {
        init {
            require(name.isNotBlank()) { "parameter name cannot be blank" }
            require(values.isNotEmpty() && values.none(String::isBlank)) {
                "category choices cannot be empty or blank"
            }
            require(values == values.distinct().sorted()) { "category choices must be unique and sorted" }
        }
    }
}

@Serializable
data class ParameterSpace(val parameters: List<ParameterSpec>) {
    init {
        require(parameters.map(ParameterSpec::name) == parameters.map(ParameterSpec::name).distinct().sorted()) {
            "parameter definitions must have unique, sorted names"
        }
    }
}

@Serializable
sealed interface ParameterValue {
    @Serializable
    @SerialName("integer")
    data class Integer(val value: Long) : ParameterValue

    @Serializable
    @SerialName("decimal")
    data class Decimal(val value: FiniteDouble) : ParameterValue

    @Serializable
    @SerialName("category")
    data class Category(val value: String) : ParameterValue {
        init {
            require(value.isNotBlank()) { "category value cannot be blank" }
        }
    }
}

@Serializable
data class ParameterValues(val values: Map<String, ParameterValue> = emptyMap())

@Serializable
enum class FoldScheme {
    EXPANDING,
    ROLLING,
}

@Serializable
data class ValidationSpec(
    val scheme: FoldScheme,
    val foldCount: Int,
    val minimumTrainingMillis: Long,
    val testMillis: Long,
    val rollingTrainingMillis: Long? = null,
    val purgeMillis: Long,
    val embargoMillis: Long,
    val sealedFinalHoldoutMillis: Long,
    val tuneInsideEachFold: Boolean = true,
    val dependenceAwareInference: Boolean = true,
    val familywiseCorrection: FamilywiseCorrection = FamilywiseCorrection.SPA_AND_FDR,
) {
    init {
        require(foldCount >= 2) { "at least two validation folds are required" }
        require(minimumTrainingMillis > 0 && testMillis > 0) { "training and test durations must be positive" }
        require(purgeMillis >= 0 && embargoMillis >= 0) { "purge and embargo cannot be negative" }
        require(sealedFinalHoldoutMillis > 0) { "a sealed final holdout is required" }
        require((scheme == FoldScheme.ROLLING) == (rollingTrainingMillis != null)) {
            "rolling validation requires exactly one rolling training duration"
        }
        require(rollingTrainingMillis == null || rollingTrainingMillis >= minimumTrainingMillis) {
            "rolling training duration cannot be shorter than minimum training duration"
        }
    }
}

@Serializable
enum class FamilywiseCorrection {
    SPA_AND_FDR,
    FDR,
    HOLM,
    NONE,
}

@Serializable
enum class ExecutionMode {
    FORECAST_ONLY,
    NEXT_OBSERVATION_SENSITIVITY,
    TAKER_BOOK_REPLAY,
}

@Serializable
data class ExecutionProfile(
    val mode: ExecutionMode,
    val targetVenue: DataSourceId,
    val decisionLatencyMillis: Long,
    val requireObservedQuotes: Boolean,
    val requireObservedDepth: Boolean,
    val includeFees: Boolean,
    val includeFunding: Boolean,
    val allowMakerFills: Boolean = false,
    val costStressMultipliers: List<FiniteDouble> = listOf(FiniteDouble(1.0), FiniteDouble(2.0)),
    val notionalTiers: List<DecimalValue> = listOf(
        DecimalValue.of("10000"),
        DecimalValue.of("100000"),
        DecimalValue.of("1000000"),
    ),
    val maximumDepthParticipation: FiniteDouble = FiniteDouble(0.10),
) {
    init {
        require(decisionLatencyMillis >= 0) { "decision latency cannot be negative" }
        require(!allowMakerFills) { "maker/queue fill claims are not supported in v1" }
        require(costStressMultipliers.isNotEmpty()) { "at least one cost scenario is required" }
        require(costStressMultipliers.all { it.value >= 1.0 }) { "cost stress cannot improve costs" }
        require(costStressMultipliers.map(FiniteDouble::value).distinct().size == costStressMultipliers.size) {
            "cost stress multipliers must be unique"
        }
        require(notionalTiers.isNotEmpty() && notionalTiers.all { it > DecimalValue.ZERO }) {
            "notional tiers must be positive"
        }
        require(notionalTiers == notionalTiers.distinct().sorted()) {
            "notional tiers must be unique and ascending"
        }
        require(maximumDepthParticipation.value > 0.0 && maximumDepthParticipation.value <= 1.0) {
            "maximum depth participation must be in (0, 1]"
        }
        require(mode != ExecutionMode.TAKER_BOOK_REPLAY || requireObservedQuotes && requireObservedDepth) {
            "promotion-grade replay requires observed quotes and depth"
        }
    }
}

@Serializable
enum class Metric {
    MAE,
    RMSE,
    DIRECTIONAL_ACCURACY,
    CALIBRATION,
    QLIKE,
    NET_RETURN,
    SHARPE,
    MAX_DRAWDOWN,
    TURNOVER,
    FUNDING,
    FEES,
    SLIPPAGE,
    CAPACITY,
    SPA_P_VALUE,
    DEFLATED_SHARPE,
    PROBABILITY_OF_BACKTEST_OVERFITTING,
}

@Serializable
enum class Comparison {
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
}

@Serializable
data class MetricCriterion(
    val metric: Metric,
    val comparison: Comparison,
    val threshold: FiniteDouble,
    val description: String,
) {
    init {
        require(description.isNotBlank()) { "criterion description cannot be blank" }
    }
}

@Serializable
data class FailureCondition(
    val criterion: MetricCriterion? = null,
    val condition: String,
) {
    init {
        require(condition.isNotBlank()) { "failure condition cannot be blank" }
    }
}

@Serializable
data class PromotionGate(
    val criteria: List<MetricCriterion>,
    val eligibleForPaper: Boolean = true,
    val requireAllDataQualityChecks: Boolean = true,
    val requireHyperliquidHoldout: Boolean = true,
    val requireDoubledCostSurvival: Boolean = true,
) {
    init {
        require(!eligibleForPaper || criteria.isNotEmpty()) {
            "paper-eligible plans must contain quantitative promotion criteria"
        }
    }
}

@Serializable
data class TheoryPlan(
    val descriptor: TheoryDescriptor,
    val researchCard: ResearchCard,
    val lineage: TheoryLineage,
    val universe: PointInTimeUniverse,
    val featureGraph: FeatureGraph,
    val target: ForecastTarget,
    val estimator: EstimatorSpec,
    val parameterSpace: ParameterSpace,
    val validation: ValidationSpec,
    val execution: ExecutionProfile,
    val metrics: List<Metric>,
    val failureConditions: List<FailureCondition>,
    val promotionGate: PromotionGate,
    val randomSeed: Long,
)

interface TheoryProvider {
    val descriptor: TheoryDescriptor

    fun compile(parameters: ParameterValues = ParameterValues()): TheoryPlan
}
