package dev.marketlab.engine

import kotlin.math.ln
import kotlin.math.sqrt

data class ForecastMetricSet(
    val count: Int,
    val meanAbsoluteError: Double,
    val rootMeanSquaredError: Double,
    val directionalAccuracy: Double,
)

object ForecastMetrics {
    fun returnMetrics(actual: List<Double>, predicted: List<Double>): ForecastMetricSet {
        validate(actual, predicted)
        val errors = actual.zip(predicted) { observed, forecast -> observed - forecast }
        return ForecastMetricSet(
            count = actual.size,
            meanAbsoluteError = errors.sumOf { kotlin.math.abs(it) } / errors.size,
            rootMeanSquaredError = sqrt(errors.sumOf { it * it } / errors.size),
            directionalAccuracy = actual.zip(predicted).count { (observed, forecast) ->
                sign(observed) == sign(forecast)
            }.toDouble() / actual.size,
        )
    }

    fun qlike(realizedVariance: List<Double>, predictedVariance: List<Double>): Double {
        return qlikeLosses(realizedVariance, predictedVariance).average()
    }

    /**
     * Observation-level Gaussian QLIKE loss, up to an actual-only constant.
     *
     * Keeping the losses is necessary for dependence-aware comparisons. The
     * baseline-minus-candidate loss differential is identical to the common
     * ratio form of QLIKE for strictly positive realized variance, while this
     * form also remains defined when realized variance is exactly zero.
     */
    fun qlikeLosses(
        realizedVariance: List<Double>,
        predictedVariance: List<Double>,
    ): List<Double> {
        validate(realizedVariance, predictedVariance)
        require(realizedVariance.all { it >= 0.0 }) { "realized variance cannot be negative" }
        require(predictedVariance.all { it > 0.0 }) { "predicted variance must be positive" }
        return realizedVariance.zip(predictedVariance) { observed, forecast ->
            observed / forecast + ln(forecast)
        }.also { losses ->
            require(losses.all(Double::isFinite)) { "QLIKE losses must be finite" }
        }
    }

    /**
     * Newey-West standard error for a sample mean using Bartlett weights.
     */
    fun hacMeanStandardError(values: List<Double>, lag: Int): Double {
        require(values.size > 1) { "at least two observations are required" }
        require(values.all(Double::isFinite)) { "values must be finite" }
        require(lag in 0 until values.size) { "invalid HAC lag" }
        val mean = values.average()
        val centered = values.map { it - mean }
        var longRunVariance = centered.sumOf { it * it } / values.size
        for (distance in 1..lag) {
            val covariance = (distance until values.size)
                .sumOf { index -> centered[index] * centered[index - distance] } / values.size
            val weight = 1.0 - distance.toDouble() / (lag + 1.0)
            longRunVariance += 2.0 * weight * covariance
        }
        return sqrt(longRunVariance.coerceAtLeast(0.0) / values.size)
    }

    private fun validate(actual: List<Double>, predicted: List<Double>) {
        require(actual.isNotEmpty()) { "metric inputs cannot be empty" }
        require(actual.size == predicted.size) { "metric input lengths differ" }
        require(actual.all(Double::isFinite) && predicted.all(Double::isFinite)) {
            "metric inputs must be finite"
        }
    }

    private fun sign(value: Double): Int = when {
        value > 0.0 -> 1
        value < 0.0 -> -1
        else -> 0
    }
}
