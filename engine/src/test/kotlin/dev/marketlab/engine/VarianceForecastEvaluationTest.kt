package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VarianceForecastEvaluationTest {
    /*
     * Exact Hyperliquid mainnet BTC daily closes from 2026-07-18 through
     * 2026-07-28, reused only as deterministic unit-test inputs.
     */
    private val closes =
        listOf(
            64_827.0,
            64_718.0,
            65_226.0,
            66_527.0,
            66_086.0,
            65_069.0,
            64_123.0,
            64_361.0,
            65_366.0,
            63_736.0,
            63_267.0,
        )

    @Test
    fun `variance metrics and QLIKE improvement use exact QLIKE losses`() {
        val actual = listOf(1.0, 2.0)
        val candidate = listOf(1.0, 2.0)
        val baseline = listOf(2.0, 1.0)

        val metrics = VarianceForecastMetrics.evaluate(actual, candidate)
        val comparison =
            VarianceForecastMetrics.compare(
                actual = actual,
                candidate = candidate,
                baseline = baseline,
                hacLag = 0,
            )

        assertEquals(2, metrics.count)
        assertEquals(1.0 + ln(2.0) / 2.0, metrics.meanQlikeLoss, absoluteTolerance = 1e-12)
        assertEquals(0.0, metrics.meanAbsoluteError, absoluteTolerance = 0.0)
        assertEquals(0.0, metrics.rootMeanSquaredError, absoluteTolerance = 0.0)
        assertEquals(0.25, comparison.meanQlikeImprovement, absoluteTolerance = 1e-12)
        assertEquals(0, comparison.hacLag)
        assertTrue(comparison.twoSidedPValue in 0.0..1.0)
    }

    @Test
    fun `variance metrics reject invalid labels forecasts and overflowing loss`() {
        assertFailsWith<IllegalArgumentException> {
            VarianceForecastMetrics.evaluate(listOf(-1.0), listOf(1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            VarianceForecastMetrics.evaluate(listOf(1.0), listOf(0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            VarianceForecastMetrics.evaluate(listOf(Double.MAX_VALUE), listOf(Double.MIN_VALUE))
        }
        assertFailsWith<IllegalArgumentException> {
            VarianceForecastMetrics.compare(
                actual = listOf(1.0),
                candidate = listOf(1.0),
                baseline = listOf(1.0),
                hacLag = -1,
            )
        }
    }

    @Test
    fun `holdout fits every model before forecasting and applies BH across controls`() {
        val rows = realVarianceRows()
        val barrier = FitBarrier(expectedFits = 3)
        val candidate = barrier.estimator(positivePrediction = rows[4].label)
        val mean = barrier.estimator(positivePrediction = rows.take(4).map { it.label }.average())
        val persistence = barrier.estimator(positivePrediction = rows[3].label)

        val result =
            VarianceHoldoutExperiment(
                candidate = candidate,
                controls =
                    listOf(
                        NamedVarianceControl("historical-mean", mean),
                        NamedVarianceControl("persistence", persistence),
                    ),
                hacLag = 1,
            ).run(
                training = rows.take(4),
                holdout = rows.drop(4).take(2),
            )

        assertEquals(3, barrier.fitCount)
        assertEquals(6, barrier.predictionCount)
        assertEquals(2, result.observations.size)
        assertEquals(
            listOf("historical-mean", "persistence"),
            result.controls.map(VarianceControlEvaluation::name),
        )
        assertTrue(result.observations.all { observation ->
            observation.candidate > 0.0 &&
                observation.controls.values.all { it > 0.0 }
        })
        assertTrue(result.controls.all {
            it.benjaminiHochbergPValue >= it.comparison.twoSidedPValue
        })
    }

    @Test
    fun `all holdout forecasts exist before invalid holdout labels are opened`() {
        val training = realVarianceRows().take(4)
        val holdout =
            realVarianceRows().drop(4).take(2).mapIndexed { index, row ->
                if (index == 1) row.copy(label = -row.label) else row
            }
        val predictions = PredictionCounter()
        val experiment =
            VarianceHoldoutExperiment(
                candidate = predictions.estimator(1.0),
                controls =
                    listOf(
                        NamedVarianceControl("mean", predictions.estimator(1.0)),
                        NamedVarianceControl("persistence", predictions.estimator(1.0)),
                    ),
                hacLag = 0,
            )

        assertFailsWith<IllegalArgumentException> {
            experiment.run(training, holdout)
        }
        assertEquals(6, predictions.count)
    }

    @Test
    fun `holdout rejects overlapping boundary duplicate controls and nonpositive forecasts`() {
        val rows = realVarianceRows()
        val overlappingTraining =
            rows.take(4).mapIndexed { index, row ->
                if (index == 3) {
                    row.copy(
                        labelTo = MarketTimestamp(rows[4].decisionTime.epochMillis + 1L),
                    )
                } else {
                    row
                }
            }
        assertFailsWith<IllegalArgumentException> {
            varianceHoldout(ConstantPositiveEstimator(1.0)).run(
                overlappingTraining,
                rows.drop(4).take(2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VarianceHoldoutExperiment(
                candidate = ConstantPositiveEstimator(1.0),
                controls =
                    listOf(
                        NamedVarianceControl("same", ConstantPositiveEstimator(1.0)),
                        NamedVarianceControl("same", ConstantPositiveEstimator(1.0)),
                    ),
                hacLag = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            varianceHoldout(ConstantPositiveEstimator(0.0)).run(
                rows.take(4),
                rows.drop(4).take(2),
            )
        }
    }

    @Test
    fun `walk forward evaluates log candidate and both controls on expanding folds`() {
        val rows = realVarianceRows()
        val result =
            VarianceWalkForwardExperiment(
                splitPlanner =
                    WalkForwardSplitPlanner(
                        WalkForwardConfig(
                            minimumTrainingRows = 4,
                            testRows = 2,
                            stepRows = 2,
                        ),
                    ),
                candidate =
                    LogTargetEstimator(
                        LinearEstimator(listOf("lag_variance")),
                    ),
                controls =
                    listOf(
                        NamedVarianceControl("historical-mean", HistoricalMeanEstimator()),
                        NamedVarianceControl("persistence", PersistenceEstimator("lag_variance")),
                    ),
                hacLag = 1,
            ).run(rows)

        assertEquals(4, result.observations.size)
        assertEquals(4, result.candidateMetrics.count)
        assertEquals(setOf("historical-mean", "persistence"), result.controls.map { it.name }.toSet())
        assertTrue(result.observations.all { observation ->
            observation.candidate.isFinite() && observation.candidate > 0.0 &&
                observation.controls.values.all { it.isFinite() && it > 0.0 }
        })
        assertTrue(result.controls.all {
            it.comparison.meanQlikeImprovement.isFinite() &&
                it.comparison.twoSidedPValue in 0.0..1.0
        })
    }

    @Test
    fun `walk forward accepts a contiguous last-N expanding-fold suffix`() {
        val rows = realVarianceRows()
        val fullPlanner =
            WalkForwardSplitPlanner(
                WalkForwardConfig(
                    minimumTrainingRows = 4,
                    testRows = 1,
                    stepRows = 1,
                ),
            )
        val lastTwoPlanner =
            object : SplitPlanner {
                override fun plan(rows: List<LabeledObservation>): List<WalkForwardFold> =
                    fullPlanner.plan(rows).takeLast(2)
            }

        val result =
            VarianceWalkForwardExperiment(
                splitPlanner = lastTwoPlanner,
                candidate =
                    LogTargetEstimator(
                        LinearEstimator(listOf("lag_variance")),
                    ),
                controls =
                    listOf(
                        NamedVarianceControl("historical-mean", HistoricalMeanEstimator()),
                        NamedVarianceControl("persistence", PersistenceEstimator("lag_variance")),
                    ),
                hacLag = 1,
            ).run(rows)

        assertEquals(listOf(3, 4), result.observations.map { it.fold })
        assertEquals(2, result.observations.size)
    }

    @Test
    fun `walk forward rejects rolling rather than expanding training sets`() {
        val rows = realVarianceRows()
        val rollingPlanner =
            object : SplitPlanner {
                override fun plan(rows: List<LabeledObservation>): List<WalkForwardFold> =
                    listOf(
                        fold(0, rows.subList(0, 4), rows.subList(4, 6)),
                        fold(1, rows.subList(2, 6), rows.subList(6, 8)),
                    )
            }
        val experiment =
            VarianceWalkForwardExperiment(
                splitPlanner = rollingPlanner,
                candidate = ConstantPositiveEstimator(1.0),
                controls =
                    listOf(
                        NamedVarianceControl("mean", ConstantPositiveEstimator(1.0)),
                        NamedVarianceControl("persistence", ConstantPositiveEstimator(1.0)),
                    ),
                hacLag = 0,
            )

        assertFailsWith<IllegalArgumentException> {
            experiment.run(rows)
        }
    }

    private fun varianceHoldout(candidate: Estimator): VarianceHoldoutExperiment =
        VarianceHoldoutExperiment(
            candidate = candidate,
            controls =
                listOf(
                    NamedVarianceControl("mean", ConstantPositiveEstimator(1.0)),
                    NamedVarianceControl("persistence", ConstantPositiveEstimator(1.0)),
                ),
            hacLag = 0,
        )

    private fun realVarianceRows(): List<LabeledObservation> {
        val variances =
            closes.zipWithNext { previous, next ->
                val value = ln(next / previous)
                value * value
            }
        return (1 until variances.size).map { index ->
            val decision = START + index * DAY
            val rowId = "btc-variance-${index - 1}"
            LabeledObservation(
                rowId = rowId,
                decisionTime = MarketTimestamp(decision),
                labelFrom = MarketTimestamp(decision),
                labelTo = MarketTimestamp(decision + DAY),
                features =
                    FeatureVector(
                        rowId = rowId,
                        values = mapOf("lag_variance" to variances[index - 1]),
                    ),
                label = variances[index],
            )
        }
    }

    private fun fold(
        index: Int,
        training: List<LabeledObservation>,
        test: List<LabeledObservation>,
    ): WalkForwardFold =
        WalkForwardFold(
            index = index,
            training = training,
            testFeatures = test.map(LabeledObservation::features),
            sealedTestLabels = test.map(LabeledObservation::label),
            testRowIds = test.map(LabeledObservation::rowId),
        )

    private class FitBarrier(
        private val expectedFits: Int,
    ) {
        var fitCount = 0
        var predictionCount = 0

        fun estimator(positivePrediction: Double): Estimator =
            object : Estimator {
                override fun fit(rows: List<LabeledObservation>): FittedEstimator {
                    fitCount++
                    return object : FittedEstimator {
                        override fun predict(features: FeatureVector): Double {
                            check(fitCount == expectedFits) {
                                "a variance forecast was requested before every model was fit"
                            }
                            predictionCount++
                            return positivePrediction
                        }
                    }
                }
            }
    }

    private class PredictionCounter {
        var count = 0

        fun estimator(value: Double): Estimator =
            object : Estimator {
                override fun fit(rows: List<LabeledObservation>): FittedEstimator =
                    object : FittedEstimator {
                        override fun predict(features: FeatureVector): Double {
                            count++
                            return value
                        }
                    }
            }
    }

    private class ConstantPositiveEstimator(
        private val value: Double,
    ) : Estimator {
        override fun fit(rows: List<LabeledObservation>): FittedEstimator =
            object : FittedEstimator {
                override fun predict(features: FeatureVector): Double = value
            }
    }

    private companion object {
        const val DAY = 86_400_000L
        const val START = 1_784_332_800_000L
    }
}
