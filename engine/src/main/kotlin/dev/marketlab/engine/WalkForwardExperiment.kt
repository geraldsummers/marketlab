package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import org.hipparchus.special.Erf
import kotlin.math.abs
import kotlin.math.sqrt

data class ForecastObservation(
    val fold: Int,
    val rowId: String,
    val decisionTime: MarketTimestamp,
    val actual: Double,
    val candidate: Double,
    val baseline: Double,
) {
    init {
        require(fold >= 0)
        require(rowId.isNotBlank())
        require(actual.isFinite() && candidate.isFinite() && baseline.isFinite())
    }
}

data class PredictiveComparison(
    val meanSquaredErrorImprovement: Double,
    val hacStandardError: Double,
    val zStatistic: Double,
    val twoSidedPValue: Double,
    val hacLag: Int,
)

data class WalkForwardExperimentResult(
    val observations: List<ForecastObservation>,
    val candidateMetrics: ForecastMetricSet,
    val baselineMetrics: ForecastMetricSet,
    val comparison: PredictiveComparison,
) {
    init {
        require(observations.isNotEmpty())
    }
}

/**
 * Fits candidate and baseline afresh within every chronological fold. Test
 * labels remain inside the engine and are opened only after both forecasts for
 * that fold have been produced.
 */
class WalkForwardExperiment(
    private val splitPlanner: SplitPlanner,
    private val candidate: Estimator,
    private val baseline: Estimator,
    private val hacLag: Int,
) {
    init {
        require(hacLag >= 0)
    }

    fun run(rows: List<LabeledObservation>): WalkForwardExperimentResult {
        val rowsById = rows.associateBy(LabeledObservation::rowId)
        require(rowsById.size == rows.size) { "row ids must be unique" }
        val observations = splitPlanner.plan(rows).flatMap { fold ->
            val candidateModel = candidate.fit(fold.training)
            val baselineModel = baseline.fit(fold.training)
            val candidatePredictions = fold.testFeatures.map(candidateModel::predict)
            val baselinePredictions = fold.testFeatures.map(baselineModel::predict)
            candidatePredictions.forEach { require(it.isFinite()) }
            baselinePredictions.forEach { require(it.isFinite()) }
            fold.testRowIds.indices.map { index ->
                val rowId = fold.testRowIds[index]
                val source = requireNotNull(rowsById[rowId])
                ForecastObservation(
                    fold = fold.index,
                    rowId = rowId,
                    decisionTime = source.decisionTime,
                    actual = fold.sealedTestLabels[index],
                    candidate = candidatePredictions[index],
                    baseline = baselinePredictions[index],
                )
            }
        }
        require(observations.isNotEmpty()) { "walk-forward evaluation emitted no forecasts" }
        val actual = observations.map(ForecastObservation::actual)
        val candidateForecast = observations.map(ForecastObservation::candidate)
        val baselineForecast = observations.map(ForecastObservation::baseline)
        val differential = observations.map {
            val baselineError = it.actual - it.baseline
            val candidateError = it.actual - it.candidate
            baselineError * baselineError - candidateError * candidateError
        }
        val effectiveLag = hacLag.coerceAtMost(differential.lastIndex)
        val standardError = if (differential.size > 1) {
            ForecastMetrics.hacMeanStandardError(differential, effectiveLag)
        } else {
            0.0
        }
        val improvement = differential.average()
        val z = when {
            standardError > 0.0 -> improvement / standardError
            improvement > 0.0 -> Double.POSITIVE_INFINITY
            improvement < 0.0 -> Double.NEGATIVE_INFINITY
            else -> 0.0
        }
        return WalkForwardExperimentResult(
            observations = observations,
            candidateMetrics = ForecastMetrics.returnMetrics(actual, candidateForecast),
            baselineMetrics = ForecastMetrics.returnMetrics(actual, baselineForecast),
            comparison = PredictiveComparison(
                meanSquaredErrorImprovement = improvement,
                hacStandardError = standardError,
                zStatistic = z,
                twoSidedPValue = twoSidedNormalPValue(z),
                hacLag = effectiveLag,
            ),
        )
    }
}

object MultipleTesting {
    /**
     * Benjamini-Hochberg adjusted p-values, returned in original hypothesis
     * order. This is monotone even when p-values contain ties.
     */
    fun benjaminiHochberg(pValues: List<Double>): List<Double> {
        require(pValues.all { it.isFinite() && it in 0.0..1.0 }) {
            "p-values must be finite and in [0, 1]"
        }
        if (pValues.isEmpty()) return emptyList()
        val ranked = pValues.withIndex().sortedWith(compareBy({ it.value }, { it.index }))
        val adjusted = DoubleArray(pValues.size)
        var next = 1.0
        for (rankIndex in ranked.indices.reversed()) {
            val rank = rankIndex + 1
            val raw = ranked[rankIndex].value * pValues.size / rank.toDouble()
            next = minOf(next, raw, 1.0)
            adjusted[ranked[rankIndex].index] = next
        }
        return adjusted.toList()
    }
}

private fun twoSidedNormalPValue(z: Double): Double {
    if (z.isNaN()) return 1.0
    if (z.isInfinite()) return 0.0
    val cdf = 0.5 * (1.0 + Erf.erf(abs(z) / sqrt(2.0)))
    return (2.0 * (1.0 - cdf)).coerceIn(0.0, 1.0)
}
