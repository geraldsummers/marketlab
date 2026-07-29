package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.SnapshotId
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.contracts.paper.PaperFill
import dev.marketlab.contracts.paper.PaperOrder

interface SnapshotCatalog {
    fun get(snapshotId: SnapshotId): DataSnapshot?
}

interface MarketEventReader {
    fun events(snapshot: DataSnapshot): Sequence<MarketEvent>
}

interface FeatureCompiler {
    fun compile(events: Sequence<MarketEvent>, decisionTime: MarketTimestamp): FeatureVector
}

interface SplitPlanner {
    fun plan(rows: List<LabeledObservation>): List<WalkForwardFold>
}

interface Estimator {
    fun fit(rows: List<LabeledObservation>): FittedEstimator
}

interface FittedEstimator {
    fun predict(features: FeatureVector): Double
}

interface PaperExecutionVenue {
    fun execute(order: PaperOrder, event: MarketEvent): List<PaperFill>
}

data class FeatureVector(
    val rowId: String,
    val values: Map<String, Double>,
) {
    init {
        require(rowId.isNotBlank()) { "row id cannot be blank" }
        require(values.isNotEmpty()) { "feature vector cannot be empty" }
        require(values.keys.none(String::isBlank)) { "feature names cannot be blank" }
        require(values.values.all(Double::isFinite)) { "features must be finite" }
    }
}

