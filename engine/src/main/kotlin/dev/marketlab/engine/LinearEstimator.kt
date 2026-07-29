package dev.marketlab.engine

import org.hipparchus.stat.regression.OLSMultipleLinearRegression

/**
 * Deterministic ordinary least-squares benchmark. Feature order is explicit so
 * a map's iteration order can never change a fitted model.
 */
class LinearEstimator(
    featureNames: List<String>,
    private val intercept: Boolean = true,
) : Estimator {
    private val featureNames = featureNames.toList()

    init {
        require(this.featureNames.isNotEmpty()) { "linear regression requires at least one feature" }
        require(this.featureNames == this.featureNames.distinct()) { "feature names must be unique" }
        require(this.featureNames.none(String::isBlank)) { "feature names cannot be blank" }
    }

    override fun fit(rows: List<LabeledObservation>): FittedEstimator {
        require(rows.size > featureNames.size + if (intercept) 1 else 0) {
            "linear regression has insufficient observations"
        }
        val x = rows.map { row ->
            DoubleArray(featureNames.size) { index ->
                requireNotNull(row.features.values[featureNames[index]]) {
                    "feature '${featureNames[index]}' is missing from row ${row.rowId}"
                }
            }
        }.toTypedArray()
        val y = rows.map(LabeledObservation::label).toDoubleArray()
        val regression = OLSMultipleLinearRegression().apply {
            isNoIntercept = !intercept
            newSampleData(y, x)
        }
        val coefficients = regression.estimateRegressionParameters()
        val expected = featureNames.size + if (intercept) 1 else 0
        require(coefficients.size == expected && coefficients.all(Double::isFinite)) {
            "linear regression returned invalid coefficients"
        }
        return LinearFittedEstimator(featureNames, coefficients, intercept)
    }
}

class LinearFittedEstimator internal constructor(
    val featureNames: List<String>,
    val coefficients: DoubleArray,
    val intercept: Boolean,
) : FittedEstimator {
    override fun predict(features: FeatureVector): Double {
        var prediction = if (intercept) coefficients[0] else 0.0
        featureNames.forEachIndexed { index, feature ->
            val value = requireNotNull(features.values[feature]) {
                "feature '$feature' is missing from row ${features.rowId}"
            }
            prediction += coefficients[index + if (intercept) 1 else 0] * value
        }
        require(prediction.isFinite()) { "linear regression emitted a non-finite prediction" }
        return prediction
    }

    override fun equals(other: Any?): Boolean =
        other is LinearFittedEstimator &&
            featureNames == other.featureNames &&
            coefficients.contentEquals(other.coefficients) &&
            intercept == other.intercept

    override fun hashCode(): Int {
        var result = featureNames.hashCode()
        result = 31 * result + coefficients.contentHashCode()
        result = 31 * result + intercept.hashCode()
        return result
    }
}
