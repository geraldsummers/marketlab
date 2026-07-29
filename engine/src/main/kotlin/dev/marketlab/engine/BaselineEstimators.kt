package dev.marketlab.engine

class ZeroReturnEstimator : Estimator {
    override fun fit(rows: List<LabeledObservation>): FittedEstimator {
        require(rows.isNotEmpty()) { "training rows cannot be empty" }
        return ConstantEstimator(0.0)
    }
}

class HistoricalMeanEstimator : Estimator {
    override fun fit(rows: List<LabeledObservation>): FittedEstimator {
        require(rows.isNotEmpty()) { "training rows cannot be empty" }
        return ConstantEstimator(rows.map(LabeledObservation::label).average())
    }
}

class PersistenceEstimator(
    private val featureName: String,
) : Estimator {
    init {
        require(featureName.isNotBlank()) { "persistence feature name cannot be blank" }
    }

    override fun fit(rows: List<LabeledObservation>): FittedEstimator {
        require(rows.isNotEmpty()) { "training rows cannot be empty" }
        require(rows.all { featureName in it.features.values }) {
            "persistence feature is missing from training data"
        }
        return object : FittedEstimator {
            override fun predict(features: FeatureVector): Double =
                requireNotNull(features.values[featureName]) { "persistence feature is missing" }
        }
    }
}

private data class ConstantEstimator(
    val value: Double,
) : FittedEstimator {
    override fun predict(features: FeatureVector): Double = value
}

