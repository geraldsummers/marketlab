package dev.marketlab.theory

import dev.marketlab.contracts.data.ObservationKind

data class TheoryValidationIssue(
    val path: String,
    val message: String,
)

class InvalidTheoryPlanException(
    val issues: List<TheoryValidationIssue>,
) : IllegalArgumentException(
    issues.joinToString(prefix = "invalid theory plan: ", separator = "; ") {
        "${it.path}: ${it.message}"
    },
)

object TheoryPlanValidator {
    private const val MAXIMUM_TRIALS = 10_000L

    fun validate(plan: TheoryPlan): List<TheoryValidationIssue> = buildList {
        validateEvidence(plan)
        validateExpressions(plan)
        validateEstimator(plan)
        validateParameterSpace(plan)
        validateMetrics(plan)
        validateExecution(plan)
        validateUniverse(plan)
    }

    fun requireValid(plan: TheoryPlan): TheoryPlan {
        val issues = validate(plan)
        if (issues.isNotEmpty()) {
            throw InvalidTheoryPlanException(issues)
        }
        return plan
    }

    private fun MutableList<TheoryValidationIssue>.validateEvidence(plan: TheoryPlan) {
        val citations = plan.researchCard.citations
        if (citations.none { it.role == EvidenceRole.SUPPORTING } &&
            plan.descriptor.family != TheoryFamily.NULL_BASELINE
        ) {
            add(TheoryValidationIssue("researchCard.citations", "non-control theory needs supporting evidence"))
        }
        if (plan.descriptor.family == TheoryFamily.MOMENTUM &&
            citations.none { it.role == EvidenceRole.CONTRADICTORY }
        ) {
            add(TheoryValidationIssue("researchCard.citations", "momentum claims must register critical evidence"))
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateExpressions(plan: TheoryPlan) {
        val requirements = plan.researchCard.dataRequirements.associateBy { it.key }
        val features = plan.featureGraph.features.associateBy { it.name }
        val dependencies = features.mapValues { (_, definition) ->
            mutableSetOf<String>().also { featureDependencies(definition.expression, it) }
        }

        features.forEach { (name, definition) ->
            validateExpression(
                expression = definition.expression,
                path = "featureGraph.$name",
                requirements = requirements,
                features = features,
            )
        }

        when (val target = plan.target) {
            is ForecastTarget.Return -> validateExpression(
                target.price,
                "target.price",
                requirements,
                features,
            )

            is ForecastTarget.RealizedVariance -> validateExpression(
                target.returnExpression,
                "target.returnExpression",
                requirements,
                features,
            )

            is ForecastTarget.LogRealizedVariance -> validateExpression(
                target.returnExpression,
                "target.returnExpression",
                requirements,
                features,
            )

            is ForecastTarget.Direction -> validateExpression(
                target.returnExpression,
                "target.returnExpression",
                requirements,
                features,
            )

            is ForecastTarget.CarryReturn -> {
                validateExpression(target.perpetualPrice, "target.perpetualPrice", requirements, features)
                validateExpression(target.referencePrice, "target.referencePrice", requirements, features)
                validateExpression(target.fundingRate, "target.fundingRate", requirements, features)
            }
        }

        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in visited) return
            if (!visiting.add(name)) {
                add(TheoryValidationIssue("featureGraph.$name", "feature dependency cycle detected"))
                return
            }
            dependencies[name].orEmpty().filter { it in features }.forEach(::visit)
            visiting.remove(name)
            visited.add(name)
        }
        features.keys.forEach(::visit)
    }

    private fun MutableList<TheoryValidationIssue>.validateExpression(
        expression: NumericExpression,
        path: String,
        requirements: Map<String, dev.marketlab.contracts.data.DataRequirement>,
        features: Map<String, FeatureDefinition>,
    ) {
        when (expression) {
            is NumericExpression.Field -> {
                val requirement = requirements[expression.requirement]
                if (requirement == null) {
                    add(TheoryValidationIssue(path, "unknown data requirement '${expression.requirement}'"))
                } else if (expression.field !in requirement.requiredFields) {
                    add(
                        TheoryValidationIssue(
                            path,
                            "field '${expression.field}' is not declared by '${expression.requirement}'",
                        ),
                    )
                }
            }

            is NumericExpression.Feature -> if (expression.name !in features) {
                add(TheoryValidationIssue(path, "unknown feature '${expression.name}'"))
            }

            is NumericExpression.Constant -> Unit
            is NumericExpression.Lag -> validateExpression(expression.input, "$path.input", requirements, features)
            is NumericExpression.Rolling -> {
                validateExpression(expression.input, "$path.input", requirements, features)
                val windowCapacity = when (val window = expression.window) {
                    is Window.Bars -> window.count
                    is Window.Events -> window.count
                    is Window.Duration -> null
                }
                if (windowCapacity != null && expression.minimumObservations > windowCapacity) {
                    add(TheoryValidationIssue(path, "minimum observations exceed the window"))
                }
            }

            is NumericExpression.Unary -> validateExpression(
                expression.input,
                "$path.input",
                requirements,
                features,
            )

            is NumericExpression.Binary -> {
                validateExpression(expression.left, "$path.left", requirements, features)
                validateExpression(expression.right, "$path.right", requirements, features)
            }

            is NumericExpression.UtcHourIndicator ->
                validateExpression(expression.timestamp, "$path.timestamp", requirements, features)

            is NumericExpression.Conditional -> {
                validateBoolean(expression.condition, "$path.condition", requirements, features)
                validateExpression(expression.whenTrue, "$path.whenTrue", requirements, features)
                validateExpression(expression.whenFalse, "$path.whenFalse", requirements, features)
            }
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateBoolean(
        expression: BooleanExpression,
        path: String,
        requirements: Map<String, dev.marketlab.contracts.data.DataRequirement>,
        features: Map<String, FeatureDefinition>,
    ) {
        when (expression) {
            is BooleanExpression.Compare -> {
                validateExpression(expression.left, "$path.left", requirements, features)
                validateExpression(expression.right, "$path.right", requirements, features)
            }

            is BooleanExpression.And -> expression.terms.forEachIndexed { index, term ->
                validateBoolean(term, "$path.terms[$index]", requirements, features)
            }

            is BooleanExpression.Or -> expression.terms.forEachIndexed { index, term ->
                validateBoolean(term, "$path.terms[$index]", requirements, features)
            }

            is BooleanExpression.Not -> validateBoolean(expression.input, "$path.input", requirements, features)
        }
    }

    private fun featureDependencies(expression: NumericExpression, output: MutableSet<String>) {
        when (expression) {
            is NumericExpression.Field,
            is NumericExpression.Constant,
            -> Unit

            is NumericExpression.Feature -> output += expression.name
            is NumericExpression.Lag -> featureDependencies(expression.input, output)
            is NumericExpression.Rolling -> featureDependencies(expression.input, output)
            is NumericExpression.Unary -> featureDependencies(expression.input, output)
            is NumericExpression.Binary -> {
                featureDependencies(expression.left, output)
                featureDependencies(expression.right, output)
            }

            is NumericExpression.UtcHourIndicator -> featureDependencies(expression.timestamp, output)

            is NumericExpression.Conditional -> {
                booleanDependencies(expression.condition, output)
                featureDependencies(expression.whenTrue, output)
                featureDependencies(expression.whenFalse, output)
            }
        }
    }

    private fun booleanDependencies(expression: BooleanExpression, output: MutableSet<String>) {
        when (expression) {
            is BooleanExpression.Compare -> {
                featureDependencies(expression.left, output)
                featureDependencies(expression.right, output)
            }

            is BooleanExpression.And -> expression.terms.forEach { booleanDependencies(it, output) }
            is BooleanExpression.Or -> expression.terms.forEach { booleanDependencies(it, output) }
            is BooleanExpression.Not -> booleanDependencies(expression.input, output)
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateEstimator(plan: TheoryPlan) {
        val featureNames = plan.featureGraph.features.map(FeatureDefinition::name).toSet()
        val parameterNames = plan.parameterSpace.parameters.map(ParameterSpec::name).toSet()
        when (val estimator = plan.estimator) {
            is EstimatorSpec.Persistence -> if (estimator.feature !in featureNames) {
                add(TheoryValidationIssue("estimator.feature", "unknown feature '${estimator.feature}'"))
            }

            is EstimatorSpec.Linear -> listOfNotNull(
                estimator.l1PenaltyParameter,
                estimator.l2PenaltyParameter,
            ).filterNot(parameterNames::contains).forEach {
                add(TheoryValidationIssue("estimator", "unknown penalty parameter '$it'"))
            }

            is EstimatorSpec.RandomForest -> listOf(
                estimator.treeCountParameter,
                estimator.maximumDepthParameter,
            ).filterNot(parameterNames::contains).forEach {
                add(TheoryValidationIssue("estimator", "unknown forest parameter '$it'"))
            }

            is EstimatorSpec.ShallowNeuralNetwork -> listOf(
                estimator.hiddenWidthParameter,
                estimator.epochsParameter,
            ).filterNot(parameterNames::contains).forEach {
                add(TheoryValidationIssue("estimator", "unknown neural-network parameter '$it'"))
            }

            EstimatorSpec.HistoricalMean,
            is EstimatorSpec.Constant,
            EstimatorSpec.RandomWalk,
            EstimatorSpec.FlatSignal,
            EstimatorSpec.BuyAndHold,
            -> Unit
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateParameterSpace(plan: TheoryPlan) {
        var trialCount = 1L
        plan.parameterSpace.parameters.forEach { parameter ->
            val choices = when (parameter) {
                is ParameterSpec.IntegerRange -> {
                    val distance = try {
                        Math.subtractExact(parameter.maximum, parameter.minimum)
                    } catch (_: ArithmeticException) {
                        MAXIMUM_TRIALS
                    }
                    if (distance / parameter.step >= MAXIMUM_TRIALS) {
                        MAXIMUM_TRIALS + 1L
                    } else {
                        distance / parameter.step + 1L
                    }
                }

                is ParameterSpec.DecimalChoices -> parameter.values.size.toLong()
                is ParameterSpec.Category -> parameter.values.size.toLong()
            }
            if (choices <= 0 || trialCount > MAXIMUM_TRIALS / choices) {
                add(
                    TheoryValidationIssue(
                        "parameterSpace",
                        "parameter grid exceeds the $MAXIMUM_TRIALS-trial safety bound",
                    ),
                )
                return
            }
            trialCount *= choices
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateMetrics(plan: TheoryPlan) {
        if (plan.metrics.isEmpty()) {
            add(TheoryValidationIssue("metrics", "at least one evaluation metric is required"))
        }
        if (plan.metrics != plan.metrics.distinct().sortedBy(Metric::name)) {
            add(TheoryValidationIssue("metrics", "metrics must be unique and sorted"))
        }
        val targetNeedsQlike =
            plan.target is ForecastTarget.RealizedVariance ||
                plan.target is ForecastTarget.LogRealizedVariance
        if (targetNeedsQlike && Metric.QLIKE !in plan.metrics) {
            add(TheoryValidationIssue("metrics", "realized-variance forecasts must report QLIKE"))
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateExecution(plan: TheoryPlan) {
        val observations = plan.researchCard.dataRequirements.map { it.observation }.toSet()
        if (plan.execution.requireObservedQuotes && ObservationKind.BBO !in observations) {
            add(TheoryValidationIssue("execution", "observed-quote execution requires BBO data"))
        }
        if (plan.execution.requireObservedDepth && ObservationKind.L2_BOOK !in observations) {
            add(TheoryValidationIssue("execution", "book replay requires L2 data"))
        }
    }

    private fun MutableList<TheoryValidationIssue>.validateUniverse(plan: TheoryPlan) {
        val requirements = plan.researchCard.dataRequirements
        val declaredVenues = requirements.flatMap { it.sourcePreference }.toSet()
        val declaredKinds = requirements.flatMap { it.instrumentKinds }.toSet()
        plan.universe.venues.filterNot(declaredVenues::contains).forEach {
            add(TheoryValidationIssue("universe.venues", "venue '${it.value}' has no data requirement"))
        }
        plan.universe.instrumentKinds.filterNot(declaredKinds::contains).forEach {
            add(TheoryValidationIssue("universe.instrumentKinds", "instrument kind '$it' has no data requirement"))
        }
        val metadataAvailable = requirements.any {
            it.observation == ObservationKind.INSTRUMENT_METADATA &&
                "active" in it.requiredFields
        }
        if (plan.universe.requireActiveAtDecisionTime && !metadataAvailable) {
            add(
                TheoryValidationIssue(
                    "universe",
                    "point-in-time selection requires active instrument metadata",
                ),
            )
        }
        if (plan.universe.selection is UniverseSelection.TopByTrailingNotional) {
            val volumeAvailable = requirements.any {
                it.observation == ObservationKind.CANDLE &&
                    "close" in it.requiredFields &&
                    "base_volume" in it.requiredFields
            }
            if (!volumeAvailable) {
                add(
                    TheoryValidationIssue(
                        "universe.selection",
                        "trailing-notional selection requires close and base_volume data",
                    ),
                )
            }
        }
    }
}
