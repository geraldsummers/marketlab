package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp

data class LabeledObservation(
    val rowId: String,
    val decisionTime: MarketTimestamp,
    val labelFrom: MarketTimestamp,
    val labelTo: MarketTimestamp,
    val features: FeatureVector,
    val label: Double,
) {
    init {
        require(rowId.isNotBlank()) { "row id cannot be blank" }
        require(labelFrom >= decisionTime) { "label cannot begin before the decision" }
        require(labelTo > labelFrom) { "label interval must be non-empty" }
        require(label.isFinite()) { "label must be finite" }
    }
}

data class WalkForwardFold(
    val index: Int,
    val training: List<LabeledObservation>,
    val testFeatures: List<FeatureVector>,
    internal val sealedTestLabels: List<Double>,
    val testRowIds: List<String>,
) {
    init {
        require(training.isNotEmpty()) { "fold training set cannot be empty" }
        require(testFeatures.isNotEmpty()) { "fold test set cannot be empty" }
        require(testFeatures.size == sealedTestLabels.size) { "test feature and label counts differ" }
        require(testFeatures.map(FeatureVector::rowId) == testRowIds) { "test row ids are misaligned" }
    }

    fun evaluate(predictions: List<Double>): ForecastMetricSet {
        require(predictions.size == sealedTestLabels.size) { "prediction count does not match sealed labels" }
        return ForecastMetrics.returnMetrics(sealedTestLabels, predictions)
    }
}

data class WalkForwardConfig(
    val minimumTrainingRows: Int,
    val testRows: Int,
    val stepRows: Int = testRows,
    val purgeMillis: Long = 0,
    val embargoMillis: Long = 0,
    val maximumTrainingRows: Int? = null,
) {
    init {
        require(minimumTrainingRows > 1) { "at least two training rows are required" }
        require(testRows > 0) { "test rows must be positive" }
        require(stepRows > 0) { "step rows must be positive" }
        require(purgeMillis >= 0 && embargoMillis >= 0) { "purge and embargo cannot be negative" }
        require(maximumTrainingRows == null || maximumTrainingRows >= minimumTrainingRows) {
            "maximum training rows cannot be below the minimum"
        }
    }
}

class WalkForwardSplitPlanner(
    private val config: WalkForwardConfig,
) : SplitPlanner {
    override fun plan(rows: List<LabeledObservation>): List<WalkForwardFold> {
        val ordered = rows.sortedWith(compareBy(LabeledObservation::decisionTime, LabeledObservation::rowId))
        require(ordered.map(LabeledObservation::rowId).distinct().size == ordered.size) {
            "row ids must be unique"
        }
        require(ordered.size >= config.minimumTrainingRows + config.testRows) {
            "not enough observations for one walk-forward fold"
        }

        fun trainingAt(testStartIndex: Int): List<LabeledObservation> {
            val firstTestDecision = ordered[testStartIndex].decisionTime.epochMillis
            val latestEligibleLabelEnd = firstTestDecision - config.purgeMillis
            val eligible =
                ordered
                    .subList(0, testStartIndex)
                    .asSequence()
                    .filter { it.labelTo.epochMillis <= latestEligibleLabelEnd }
                    .filter {
                        it.labelTo.epochMillis + config.embargoMillis <= firstTestDecision
                    }
                    .toList()
            return config.maximumTrainingRows?.let(eligible::takeLast) ?: eligible
        }

        /*
         * minimumTrainingRows is an effective post-purge minimum. Starting the
         * first test mechanically at that raw row offset makes every positive
         * purge or embargo fail fold zero, even when later history is ample.
         */
        var testStartIndex = config.minimumTrainingRows
        while (
            testStartIndex + config.testRows <= ordered.size &&
            trainingAt(testStartIndex).size < config.minimumTrainingRows
        ) {
            testStartIndex++
        }
        require(testStartIndex + config.testRows <= ordered.size) {
            "not enough observations for one walk-forward fold after purging/embargo"
        }

        val folds = mutableListOf<WalkForwardFold>()
        while (testStartIndex + config.testRows <= ordered.size) {
            val test = ordered.subList(testStartIndex, testStartIndex + config.testRows)
            val training = trainingAt(testStartIndex)

            require(training.size >= config.minimumTrainingRows) {
                "purging/embargo leaves too few training observations for fold ${folds.size}"
            }
            require(training.none { train ->
                test.any { candidate ->
                    intervalsOverlap(train.labelFrom, train.labelTo, candidate.labelFrom, candidate.labelTo)
                }
            }) {
                "training and test labels overlap after purging"
            }

            folds += WalkForwardFold(
                index = folds.size,
                training = training,
                testFeatures = test.map(LabeledObservation::features),
                sealedTestLabels = test.map(LabeledObservation::label),
                testRowIds = test.map(LabeledObservation::rowId),
            )
            testStartIndex += config.stepRows
        }
        return folds
    }
}

private fun intervalsOverlap(
    leftFrom: MarketTimestamp,
    leftTo: MarketTimestamp,
    rightFrom: MarketTimestamp,
    rightTo: MarketTimestamp,
): Boolean = leftFrom < rightTo && rightFrom < leftTo
