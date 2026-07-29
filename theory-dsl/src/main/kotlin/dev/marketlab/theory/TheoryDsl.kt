package dev.marketlab.theory

import dev.marketlab.contracts.data.DataRequirement

@DslMarker
annotation class TheoryDsl

@TheoryDsl
class ResearchCardBuilder internal constructor() {
    lateinit var hypothesis: String
    lateinit var mechanism: String
    lateinit var falsifiableClaim: String
    lateinit var expectedSign: ExpectedSign
    lateinit var horizon: Horizon
    var reproductionKind: ReproductionKind = ReproductionKind.REGISTERED_ADAPTATION
    lateinit var confirmationPeriodPolicy: String

    private val citations = mutableListOf<Citation>()
    private val requirements = mutableListOf<DataRequirement>()

    fun cite(citation: Citation) {
        citations += citation
    }

    fun requireData(requirement: DataRequirement) {
        requirements += requirement
    }

    internal fun build(): ResearchCard = ResearchCard(
        hypothesis = hypothesis,
        mechanism = mechanism,
        falsifiableClaim = falsifiableClaim,
        expectedSign = expectedSign,
        horizon = horizon,
        reproductionKind = reproductionKind,
        confirmationPeriodPolicy = confirmationPeriodPolicy,
        citations = citations.sortedBy(Citation::key),
        dataRequirements = requirements.sortedBy(DataRequirement::key),
    )
}

@TheoryDsl
class TheoryPlanBuilder internal constructor(
    private val descriptor: TheoryDescriptor,
) {
    private var card: ResearchCard? = null
    private var lineage: TheoryLineage = TheoryLineage()
    private var universe: PointInTimeUniverse? = null
    private val features = mutableListOf<FeatureDefinition>()
    private var target: ForecastTarget? = null
    private var estimator: EstimatorSpec? = null
    private var parameterSpace: ParameterSpace = ParameterSpace(emptyList())
    private var validation: ValidationSpec? = null
    private var execution: ExecutionProfile? = null
    private val metrics = mutableListOf<Metric>()
    private val failureConditions = mutableListOf<FailureCondition>()
    private var promotionGate: PromotionGate? = null
    var randomSeed: Long = 0x4D41524B45544C

    fun research(block: ResearchCardBuilder.() -> Unit) {
        check(card == null) { "research card has already been configured" }
        card = ResearchCardBuilder().apply(block).build()
    }

    fun lineage(value: TheoryLineage) {
        lineage = value
    }

    fun universe(value: PointInTimeUniverse) {
        check(universe == null) { "point-in-time universe has already been configured" }
        universe = value
    }

    fun feature(name: String, description: String, expression: NumericExpression) {
        features += FeatureDefinition(name, expression, description)
    }

    fun target(value: ForecastTarget) {
        check(target == null) { "target has already been configured" }
        target = value
    }

    fun estimator(value: EstimatorSpec) {
        check(estimator == null) { "estimator has already been configured" }
        estimator = value
    }

    fun parameters(vararg values: ParameterSpec) {
        parameterSpace = ParameterSpace(values.sortedBy(ParameterSpec::name))
    }

    fun validation(value: ValidationSpec) {
        check(validation == null) { "validation has already been configured" }
        validation = value
    }

    fun execution(value: ExecutionProfile) {
        check(execution == null) { "execution profile has already been configured" }
        execution = value
    }

    fun metrics(vararg values: Metric) {
        metrics += values
    }

    fun failWhen(condition: String, criterion: MetricCriterion? = null) {
        failureConditions += FailureCondition(criterion, condition)
    }

    fun promotion(value: PromotionGate) {
        check(promotionGate == null) { "promotion gate has already been configured" }
        promotionGate = value
    }

    internal fun build(): TheoryPlan {
        val plan = TheoryPlan(
            descriptor = descriptor,
            researchCard = requireNotNull(card) { "research card is required" },
            lineage = lineage,
            universe = requireNotNull(universe) { "point-in-time universe is required" },
            featureGraph = FeatureGraph(features.sortedBy(FeatureDefinition::name)),
            target = requireNotNull(target) { "forecast target is required" },
            estimator = requireNotNull(estimator) { "estimator is required" },
            parameterSpace = parameterSpace,
            validation = requireNotNull(validation) { "validation plan is required" },
            execution = requireNotNull(execution) { "execution profile is required" },
            metrics = metrics.distinct().sortedBy(Metric::name),
            failureConditions = failureConditions.sortedBy(FailureCondition::condition),
            promotionGate = requireNotNull(promotionGate) { "promotion gate is required" },
            randomSeed = randomSeed,
        )
        return TheoryPlanValidator.requireValid(plan)
    }
}

fun theory(descriptor: TheoryDescriptor, block: TheoryPlanBuilder.() -> Unit): TheoryPlan =
    TheoryPlanBuilder(descriptor).apply(block).build()

fun ParameterValues.integer(name: String, default: Long): Long =
    when (val value = values[name]) {
        null -> default
        is ParameterValue.Integer -> value.value
        else -> throw IllegalArgumentException("parameter '$name' must be an integer")
    }

fun ParameterValues.decimal(name: String, default: Double): Double =
    when (val value = values[name]) {
        null -> default
        is ParameterValue.Decimal -> value.value.value
        else -> throw IllegalArgumentException("parameter '$name' must be a decimal")
    }

fun ParameterValues.category(name: String, default: String): String =
    when (val value = values[name]) {
        null -> default
        is ParameterValue.Category -> value.value
        else -> throw IllegalArgumentException("parameter '$name' must be a category")
    }
