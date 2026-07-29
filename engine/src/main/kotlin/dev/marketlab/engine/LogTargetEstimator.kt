package dev.marketlab.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * Fits an estimator in log-target space and maps its forecasts back to the
 * positive level domain.
 *
 * Features and all causal timestamps are preserved byte-for-byte; only the
 * training label supplied to the delegated estimator is transformed.
 */
class LogTargetEstimator(
    private val delegate: Estimator,
) : Estimator {
    override fun fit(rows: List<LabeledObservation>): FittedEstimator {
        require(rows.isNotEmpty()) { "log-target training rows cannot be empty" }
        require(rows.all { it.label > 0.0 }) {
            "log-target training labels must be positive"
        }
        val fitted =
            delegate.fit(
                rows.map { row ->
                    row.copy(label = ln(row.label))
                },
            )
        return object : FittedEstimator {
            override fun predict(features: FeatureVector): Double {
                val logPrediction = fitted.predict(features)
                require(logPrediction.isFinite()) {
                    "log-target delegate emitted a non-finite prediction"
                }
                val prediction = exp(logPrediction)
                require(prediction.isFinite() && prediction > 0.0) {
                    "log-target exponentiation emitted a non-positive or non-finite prediction"
                }
                return prediction
            }
        }
    }
}
