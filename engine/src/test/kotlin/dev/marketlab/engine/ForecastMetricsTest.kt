package dev.marketlab.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForecastMetricsTest {
    // Four consecutive real Hyperliquid BTC hourly closes from 2026-07-28,
    // transformed into log returns. These are factual production observations,
    // not a generated price path.
    private val returns = listOf(
        0.001733009659,
        -0.000076618372,
        0.002769851725,
        -0.000856151346,
    )

    @Test
    fun `historical mean baseline evaluates finite metrics`() {
        val predicted = List(returns.size) { returns.take(2).average() }
        val metrics = ForecastMetrics.returnMetrics(returns, predicted)
        assertEquals(4, metrics.count)
        assertTrue(metrics.rootMeanSquaredError > 0.0)
        assertTrue(metrics.directionalAccuracy in 0.0..1.0)
    }

    @Test
    fun `HAC standard error is finite`() {
        assertTrue(ForecastMetrics.hacMeanStandardError(returns, lag = 1).isFinite())
    }
}
