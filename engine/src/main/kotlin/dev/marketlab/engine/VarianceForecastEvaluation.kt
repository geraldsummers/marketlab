package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import kotlin.math.abs
import kotlin.math.sqrt
import org.hipparchus.special.Erf

data class VarianceForecastMetricSet(
    val count: Int,
    val meanQlikeLoss: Double,
    val meanAbsoluteError: Double,
    val rootMeanSquaredError: Double,
) {
    init {
        require(count > 0)
        require(meanQlikeLoss.isFinite())
        require(meanAbsoluteError.isFinite() && meanAbsoluteError >= 0.0)
        require(rootMeanSquaredError.isFinite() && rootMeanSquaredError >= 0.0)
    }
}

data class VariancePredictiveComparison(
    val meanQlikeImprovement: Double,
    val hacStandardError: Double,
    val zStatistic: Double,
    val twoSidedPValue: Double,
    val hacLag: Int,
) {
    init {
        require(meanQlikeImprovement.isFinite())
        require(hacStandardError.isFinite() && hacStandardError >= 0.0)
        require(!zStatistic.isNaN())
        require(twoSidedPValue.isFinite() && twoSidedPValue in 0.0..1.0)
        require(hacLag >= 0)
    }
}

object VarianceForecastMetrics {
    fun evaluate(
        actual: List<Double>,
        predicted: List<Double>,
    ): VarianceForecastMetricSet {
        val qlikeLosses = ForecastMetrics.qlikeLosses(actual, predicted)
        val errors = actual.zip(predicted) { observed, forecast -> observed - forecast }
        val meanAbsoluteError = errors.sumOf(::abs) / errors.size
        val meanSquaredError = errors.sumOf { it * it } / errors.size
        require(meanAbsoluteError.isFinite() && meanSquaredError.isFinite()) {
            "variance forecast errors must be finite"
        }
        return VarianceForecastMetricSet(
            count = actual.size,
            meanQlikeLoss = qlikeLosses.average(),
            meanAbsoluteError = meanAbsoluteError,
            rootMeanSquaredError = sqrt(meanSquaredError),
        )
    }

    /**
     * Positive values mean the candidate has lower average QLIKE loss.
     */
    fun compare(
        actual: List<Double>,
        candidate: List<Double>,
        baseline: List<Double>,
        hacLag: Int,
    ): VariancePredictiveComparison {
        require(hacLag >= 0) { "HAC lag cannot be negative" }
        val candidateLosses = ForecastMetrics.qlikeLosses(actual, candidate)
        val baselineLosses = ForecastMetrics.qlikeLosses(actual, baseline)
        val differential =
            baselineLosses.zip(candidateLosses) { baselineLoss, candidateLoss ->
                baselineLoss - candidateLoss
            }.also { values ->
                require(values.all(Double::isFinite)) {
                    "QLIKE loss differentials must be finite"
                }
            }
        val effectiveLag = hacLag.coerceAtMost(differential.lastIndex)
        val standardError =
            if (differential.size > 1) {
                ForecastMetrics.hacMeanStandardError(differential, effectiveLag)
            } else {
                0.0
            }
        require(standardError.isFinite()) { "QLIKE HAC standard error must be finite" }
        val improvement = differential.average()
        val z =
            when {
                standardError > 0.0 -> improvement / standardError
                improvement > 0.0 -> Double.POSITIVE_INFINITY
                improvement < 0.0 -> Double.NEGATIVE_INFINITY
                else -> 0.0
            }
        return VariancePredictiveComparison(
            meanQlikeImprovement = improvement,
            hacStandardError = standardError,
            zStatistic = z,
            twoSidedPValue = varianceTwoSidedNormalPValue(z),
            hacLag = effectiveLag,
        )
    }
}

data class NamedVarianceControl(
    val name: String,
    val estimator: Estimator,
) {
    init {
        require(name.isNotBlank()) { "variance control name cannot be blank" }
    }
}

data class VarianceControlEvaluation(
    val name: String,
    val metrics: VarianceForecastMetricSet,
    val comparison: VariancePredictiveComparison,
    val benjaminiHochbergPValue: Double,
) {
    init {
        require(name.isNotBlank())
        require(benjaminiHochbergPValue.isFinite() && benjaminiHochbergPValue in 0.0..1.0)
    }
}

data class VarianceHoldoutForecastObservation(
    val rowId: String,
    val decisionTime: MarketTimestamp,
    val actual: Double,
    val candidate: Double,
    val controls: Map<String, Double>,
) {
    init {
        validateVarianceObservation(rowId, actual, candidate, controls)
    }
}

data class VarianceHoldoutExperimentResult(
    val observations: List<VarianceHoldoutForecastObservation>,
    val candidateMetrics: VarianceForecastMetricSet,
    val controls: List<VarianceControlEvaluation>,
) {
    init {
        require(observations.isNotEmpty())
        require(controls.isNotEmpty())
    }
}

/**
 * Fits every model once on development data, creates every complete holdout
 * forecast vector, and only then opens the sealed variance labels.
 */
class VarianceHoldoutExperiment(
    private val candidate: Estimator,
    controls: List<NamedVarianceControl>,
    private val hacLag: Int,
) {
    private val controls = controls.toList()

    init {
        validateVarianceControls(this.controls)
        require(hacLag >= 0) { "HAC lag cannot be negative" }
    }

    fun run(
        training: List<LabeledObservation>,
        holdout: List<LabeledObservation>,
    ): VarianceHoldoutExperimentResult {
        require(training.isNotEmpty()) { "variance holdout training rows cannot be empty" }
        require(holdout.isNotEmpty()) { "sealed variance holdout rows cannot be empty" }
        require((training + holdout).map(LabeledObservation::rowId).distinct().size ==
            training.size + holdout.size) {
            "variance development and holdout row ids must be disjoint"
        }
        val orderedHoldout =
            holdout.sortedWith(compareBy(LabeledObservation::decisionTime, LabeledObservation::rowId))
        val firstHoldoutDecision = orderedHoldout.first().decisionTime
        require(training.all { it.labelTo <= firstHoldoutDecision }) {
            "variance development labels overlap the sealed holdout boundary"
        }
        requireNonNegativeRealizedVariances(training.map(LabeledObservation::label))

        /*
         * All candidate and control models are fitted before the first holdout
         * forecast. Only development labels are available to these calls.
         */
        val candidateModel = candidate.fit(training)
        val controlModels = controls.associate { control ->
            control.name to control.estimator.fit(training)
        }
        val candidateForecasts =
            orderedHoldout.map { row -> candidateModel.predict(row.features) }
                .also(::requirePositiveVarianceForecasts)
        val controlForecasts =
            controlModels.mapValues { (_, model) ->
                orderedHoldout.map { row -> model.predict(row.features) }
                    .also(::requirePositiveVarianceForecasts)
            }

        /*
         * This is the first read of a holdout label. Every complete forecast
         * vector already exists, so labels cannot affect fitting or prediction.
         */
        val actual = orderedHoldout.map(LabeledObservation::label)
            .also(::requireNonNegativeRealizedVariances)
        val observations =
            orderedHoldout.indices.map { index ->
                val row = orderedHoldout[index]
                VarianceHoldoutForecastObservation(
                    rowId = row.rowId,
                    decisionTime = row.decisionTime,
                    actual = actual[index],
                    candidate = candidateForecasts[index],
                    controls =
                        controlForecasts.mapValues { (_, forecasts) ->
                            forecasts[index]
                        },
                )
            }
        return VarianceHoldoutExperimentResult(
            observations = observations,
            candidateMetrics = VarianceForecastMetrics.evaluate(actual, candidateForecasts),
            controls =
                evaluateVarianceControls(
                    controls = controls,
                    actual = actual,
                    candidateForecasts = candidateForecasts,
                    controlForecasts = controlForecasts,
                    hacLag = hacLag,
                ),
        )
    }
}

data class VarianceWalkForwardForecastObservation(
    val fold: Int,
    val rowId: String,
    val decisionTime: MarketTimestamp,
    val actual: Double,
    val candidate: Double,
    val controls: Map<String, Double>,
) {
    init {
        require(fold >= 0)
        validateVarianceObservation(rowId, actual, candidate, controls)
    }
}

data class VarianceWalkForwardExperimentResult(
    val observations: List<VarianceWalkForwardForecastObservation>,
    val candidateMetrics: VarianceForecastMetricSet,
    val controls: List<VarianceControlEvaluation>,
) {
    init {
        require(observations.isNotEmpty())
        require(controls.isNotEmpty())
    }
}

/**
 * Expanding walk-forward variance evaluation with one candidate and all named
 * controls evaluated on exactly the same chronological folds.
 */
class VarianceWalkForwardExperiment(
    private val splitPlanner: SplitPlanner,
    private val candidate: Estimator,
    controls: List<NamedVarianceControl>,
    private val hacLag: Int,
) {
    private val controls = controls.toList()

    init {
        validateVarianceControls(this.controls)
        require(hacLag >= 0) { "HAC lag cannot be negative" }
    }

    fun run(rows: List<LabeledObservation>): VarianceWalkForwardExperimentResult {
        val rowsById = rows.associateBy(LabeledObservation::rowId)
        require(rowsById.size == rows.size) { "variance row ids must be unique" }
        val folds = splitPlanner.plan(rows)
        require(folds.isNotEmpty()) { "variance walk-forward evaluation emitted no folds" }
        validateExpandingVarianceFolds(folds, rowsById)

        val observations =
            folds.flatMap { fold ->
                /*
                 * Fit the full model family before forecasting. Test labels
                 * remain sealed inside the fold until every vector is ready.
                 */
                val candidateModel = candidate.fit(fold.training)
                val controlModels = controls.associate { control ->
                    control.name to control.estimator.fit(fold.training)
                }
                val candidateForecasts =
                    fold.testFeatures.map(candidateModel::predict)
                        .also(::requirePositiveVarianceForecasts)
                val controlForecasts =
                    controlModels.mapValues { (_, model) ->
                        fold.testFeatures.map(model::predict)
                            .also(::requirePositiveVarianceForecasts)
                    }

                val actual = fold.sealedTestLabels
                    .also(::requireNonNegativeRealizedVariances)
                fold.testRowIds.indices.map { index ->
                    val rowId = fold.testRowIds[index]
                    val source = requireNotNull(rowsById[rowId])
                    require(actual[index] == source.label) {
                        "variance fold sealed label differs from its source row"
                    }
                    VarianceWalkForwardForecastObservation(
                        fold = fold.index,
                        rowId = rowId,
                        decisionTime = source.decisionTime,
                        actual = actual[index],
                        candidate = candidateForecasts[index],
                        controls =
                            controlForecasts.mapValues { (_, forecasts) ->
                                forecasts[index]
                            },
                    )
                }
            }
        require(observations.isNotEmpty()) {
            "variance walk-forward evaluation emitted no forecasts"
        }
        require(observations.map(VarianceWalkForwardForecastObservation::rowId).distinct().size ==
            observations.size) {
            "variance walk-forward test rows must not repeat across folds"
        }
        val actual = observations.map(VarianceWalkForwardForecastObservation::actual)
        val candidateForecasts =
            observations.map(VarianceWalkForwardForecastObservation::candidate)
        val controlForecasts =
            controls.associate { control ->
                control.name to
                    observations.map { observation ->
                        observation.controls.getValue(control.name)
                    }
            }
        return VarianceWalkForwardExperimentResult(
            observations = observations,
            candidateMetrics = VarianceForecastMetrics.evaluate(actual, candidateForecasts),
            controls =
                evaluateVarianceControls(
                    controls = controls,
                    actual = actual,
                    candidateForecasts = candidateForecasts,
                    controlForecasts = controlForecasts,
                    hacLag = hacLag,
                ),
        )
    }
}

private fun evaluateVarianceControls(
    controls: List<NamedVarianceControl>,
    actual: List<Double>,
    candidateForecasts: List<Double>,
    controlForecasts: Map<String, List<Double>>,
    hacLag: Int,
): List<VarianceControlEvaluation> {
    val unadjusted =
        controls.map { control ->
            val forecasts = requireNotNull(controlForecasts[control.name])
            control to
                VarianceForecastMetrics.compare(
                    actual = actual,
                    candidate = candidateForecasts,
                    baseline = forecasts,
                    hacLag = hacLag,
                )
        }
    val adjusted =
        MultipleTesting.benjaminiHochberg(
            unadjusted.map { (_, comparison) -> comparison.twoSidedPValue },
        )
    return unadjusted.mapIndexed { index, (control, comparison) ->
        val forecasts = requireNotNull(controlForecasts[control.name])
        VarianceControlEvaluation(
            name = control.name,
            metrics = VarianceForecastMetrics.evaluate(actual, forecasts),
            comparison = comparison,
            benjaminiHochbergPValue = adjusted[index],
        )
    }
}

private fun validateVarianceControls(controls: List<NamedVarianceControl>) {
    require(controls.isNotEmpty()) { "at least one variance control is required" }
    require(controls.map(NamedVarianceControl::name).distinct().size == controls.size) {
        "variance control names must be unique"
    }
}

private fun validateExpandingVarianceFolds(
    folds: List<WalkForwardFold>,
    rowsById: Map<String, LabeledObservation>,
) {
    val firstFoldIndex = folds.first().index
    require(firstFoldIndex >= 0) { "variance fold indexes cannot be negative" }
    var priorTrainingIds = emptySet<String>()
    var priorFirstTestDecision: MarketTimestamp? = null
    val emittedTestIds = mutableSetOf<String>()
    folds.forEachIndexed { position, fold ->
        require(fold.index == Math.addExact(firstFoldIndex, position)) {
            "variance fold indexes must be contiguous"
        }
        val trainingIds = fold.training.map(LabeledObservation::rowId).toSet()
        require(fold.training.all { trainingRow ->
            rowsById[trainingRow.rowId] == trainingRow
        }) {
            "variance fold training rows differ from their source rows"
        }
        requireNonNegativeRealizedVariances(fold.training.map(LabeledObservation::label))
        require(priorTrainingIds.all(trainingIds::contains)) {
            "variance walk-forward training sets must expand"
        }
        require(fold.testRowIds.all(rowsById::containsKey)) {
            "variance fold references an unknown test row"
        }
        require(fold.testFeatures.all { features ->
            rowsById[features.rowId]?.features == features
        }) {
            "variance fold test features differ from their source rows"
        }
        require(trainingIds.intersect(fold.testRowIds.toSet()).isEmpty()) {
            "variance fold training and test rows must be disjoint"
        }
        require(fold.testRowIds.all(emittedTestIds::add)) {
            "variance test rows repeat across folds"
        }
        val firstTestDecision =
            fold.testRowIds.minOf { rowId ->
                requireNotNull(rowsById[rowId]).decisionTime
            }
        require(fold.training.all { it.labelTo <= firstTestDecision }) {
            "variance training labels overlap a test boundary"
        }
        priorFirstTestDecision?.let { prior ->
            require(firstTestDecision > prior) {
                "variance test folds must advance chronologically"
            }
        }
        priorTrainingIds = trainingIds
        priorFirstTestDecision = firstTestDecision
    }
}

private fun requirePositiveVarianceForecasts(forecasts: List<Double>) {
    require(forecasts.all { it.isFinite() && it > 0.0 }) {
        "variance forecasts must be positive and finite"
    }
}

private fun requireNonNegativeRealizedVariances(actual: List<Double>) {
    require(actual.all { it.isFinite() && it >= 0.0 }) {
        "realized variances must be non-negative and finite"
    }
}

private fun validateVarianceObservation(
    rowId: String,
    actual: Double,
    candidate: Double,
    controls: Map<String, Double>,
) {
    require(rowId.isNotBlank())
    require(actual.isFinite() && actual >= 0.0)
    require(candidate.isFinite() && candidate > 0.0)
    require(controls.isNotEmpty() && controls.keys.none(String::isBlank))
    require(controls.values.all { it.isFinite() && it > 0.0 })
}

private fun varianceTwoSidedNormalPValue(z: Double): Double {
    if (z.isNaN()) return 1.0
    if (z.isInfinite()) return 0.0
    val cdf = 0.5 * (1.0 + Erf.erf(abs(z) / sqrt(2.0)))
    return (2.0 * (1.0 - cdf)).coerceIn(0.0, 1.0)
}
