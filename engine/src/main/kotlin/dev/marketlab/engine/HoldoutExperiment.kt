package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import org.hipparchus.special.Erf
import kotlin.math.abs
import kotlin.math.sqrt

data class HoldoutBaseline(
    val name: String,
    val estimator: Estimator,
) {
    init {
        require(name.isNotBlank())
    }
}

data class HoldoutForecastObservation(
    val rowId: String,
    val decisionTime: MarketTimestamp,
    val actual: Double,
    val candidate: Double,
    val baselines: Map<String, Double>,
) {
    init {
        require(rowId.isNotBlank())
        require(actual.isFinite() && candidate.isFinite())
        require(baselines.isNotEmpty() && baselines.keys.none(String::isBlank))
        require(baselines.values.all(Double::isFinite))
    }
}

data class HoldoutBaselineEvaluation(
    val name: String,
    val metrics: ForecastMetricSet,
    val comparison: PredictiveComparison,
)

data class HoldoutExperimentResult(
    val observations: List<HoldoutForecastObservation>,
    val candidateMetrics: ForecastMetricSet,
    val baselines: List<HoldoutBaselineEvaluation>,
) {
    init {
        require(observations.isNotEmpty())
        require(baselines.isNotEmpty())
    }
}

/**
 * Fits one fixed candidate and every registered control on the frozen
 * development set. It produces all holdout forecasts before opening any
 * holdout label; there is no tuning, refit, or branching on holdout outcomes.
 */
class HoldoutExperiment(
    private val candidate: Estimator,
    baselines: List<HoldoutBaseline>,
    private val hacLag: Int,
) {
    private val baselines = baselines.toList()

    init {
        require(this.baselines.isNotEmpty())
        require(this.baselines.map(HoldoutBaseline::name).distinct().size == this.baselines.size) {
            "holdout baseline names must be unique"
        }
        require(hacLag >= 0)
    }

    fun run(
        training: List<LabeledObservation>,
        holdout: List<LabeledObservation>,
    ): HoldoutExperimentResult {
        require(training.isNotEmpty()) { "holdout training rows cannot be empty" }
        require(holdout.isNotEmpty()) { "sealed holdout rows cannot be empty" }
        require((training + holdout).map(LabeledObservation::rowId).distinct().size ==
            training.size + holdout.size) {
            "development and holdout row ids must be disjoint"
        }
        val firstHoldoutDecision = holdout.minOf(LabeledObservation::decisionTime)
        require(training.all { it.labelTo <= firstHoldoutDecision }) {
            "development labels overlap the sealed holdout boundary"
        }
        val orderedHoldout =
            holdout.sortedWith(compareBy(LabeledObservation::decisionTime, LabeledObservation::rowId))

        val candidateModel = candidate.fit(training)
        val baselineModels = baselines.associate { it.name to it.estimator.fit(training) }
        val candidateForecasts = orderedHoldout.map { candidateModel.predict(it.features) }
        val baselineForecasts =
            baselineModels.mapValues { (_, model) ->
                orderedHoldout.map { model.predict(it.features) }
            }
        require(candidateForecasts.all(Double::isFinite))
        require(baselineForecasts.values.flatten().all(Double::isFinite))

        /*
         * Labels are deliberately first read only after every complete forecast
         * vector exists.
         */
        val observations =
            orderedHoldout.indices.map { index ->
                val row = orderedHoldout[index]
                HoldoutForecastObservation(
                    rowId = row.rowId,
                    decisionTime = row.decisionTime,
                    actual = row.label,
                    candidate = candidateForecasts[index],
                    baselines =
                        baselineForecasts.mapValues { (_, forecasts) ->
                            forecasts[index]
                        },
                )
            }
        val actual = observations.map(HoldoutForecastObservation::actual)
        val baselineEvaluations =
            baselines.map { baseline ->
                val forecasts =
                    observations.map {
                        it.baselines.getValue(baseline.name)
                    }
                HoldoutBaselineEvaluation(
                    name = baseline.name,
                    metrics = ForecastMetrics.returnMetrics(actual, forecasts),
                    comparison =
                        compareLosses(
                            actual = actual,
                            candidate = candidateForecasts,
                            baseline = forecasts,
                        ),
                )
            }
        return HoldoutExperimentResult(
            observations = observations,
            candidateMetrics = ForecastMetrics.returnMetrics(actual, candidateForecasts),
            baselines = baselineEvaluations,
        )
    }

    private fun compareLosses(
        actual: List<Double>,
        candidate: List<Double>,
        baseline: List<Double>,
    ): PredictiveComparison {
        val differential =
            actual.indices.map { index ->
                val baselineError = actual[index] - baseline[index]
                val candidateError = actual[index] - candidate[index]
                baselineError * baselineError - candidateError * candidateError
            }
        val effectiveLag = hacLag.coerceAtMost(differential.lastIndex)
        val standardError =
            if (differential.size > 1) {
                ForecastMetrics.hacMeanStandardError(differential, effectiveLag)
            } else {
                0.0
            }
        val improvement = differential.average()
        val z =
            when {
                standardError > 0.0 -> improvement / standardError
                improvement > 0.0 -> Double.POSITIVE_INFINITY
                improvement < 0.0 -> Double.NEGATIVE_INFINITY
                else -> 0.0
            }
        return PredictiveComparison(
            meanSquaredErrorImprovement = improvement,
            hacStandardError = standardError,
            zStatistic = z,
            twoSidedPValue = holdoutTwoSidedNormalPValue(z),
            hacLag = effectiveLag,
        )
    }
}

private fun holdoutTwoSidedNormalPValue(z: Double): Double {
    if (z.isNaN()) return 1.0
    if (z.isInfinite()) return 0.0
    val cdf = 0.5 * (1.0 + Erf.erf(abs(z) / sqrt(2.0)))
    return (2.0 * (1.0 - cdf)).coerceIn(0.0, 1.0)
}
