package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkForwardExperimentTest {
    /*
     * Exact Hyperliquid mainnet BTC daily closes from 2026-07-18 through
     * 2026-07-28, retrieved from /info candleSnapshot on 2026-07-28.
     */
    private val closes = listOf(
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
    private val openTimes = (0 until closes.size).map { 1_784_332_800_000L + it * DAY }

    @Test
    fun `fits models only on each causal training fold`() {
        val rows = observations()
        val experiment = WalkForwardExperiment(
            splitPlanner = WalkForwardSplitPlanner(
                WalkForwardConfig(
                    minimumTrainingRows = 4,
                    testRows = 2,
                    stepRows = 2,
                    purgeMillis = 0,
                    embargoMillis = 0,
                ),
            ),
            candidate = LinearEstimator(listOf("last_return")),
            baseline = HistoricalMeanEstimator(),
            hacLag = 1,
        )

        val result = experiment.run(rows)

        assertEquals(4, result.observations.size)
        assertEquals(listOf("btc-day-4", "btc-day-5", "btc-day-6", "btc-day-7"), result.observations.map { it.rowId })
        assertEquals(4, result.candidateMetrics.count)
        assertTrue(result.comparison.twoSidedPValue in 0.0..1.0)
        assertTrue(result.observations.all { it.candidate.isFinite() && it.baseline.isFinite() })
    }

    @Test
    fun `benjamini hochberg retains input ordering and monotonicity`() {
        val adjusted = MultipleTesting.benjaminiHochberg(listOf(0.04, 0.001, 0.03))

        assertEquals(0.04, adjusted[0], absoluteTolerance = 1e-12)
        assertEquals(0.003, adjusted[1], absoluteTolerance = 1e-12)
        assertEquals(0.04, adjusted[2], absoluteTolerance = 1e-12)
    }

    private fun observations(): List<LabeledObservation> {
        val returns = closes.zipWithNext { previous, next -> ln(next / previous) }
        return (1 until returns.size).map { index ->
            val decision = openTimes[index]
            LabeledObservation(
                rowId = "btc-day-${index - 1}",
                decisionTime = MarketTimestamp(decision),
                labelFrom = MarketTimestamp(decision),
                labelTo = MarketTimestamp(openTimes[index + 1]),
                features = FeatureVector(
                    rowId = "btc-day-${index - 1}",
                    values = mapOf("last_return" to returns[index - 1]),
                ),
                label = returns[index],
            )
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
