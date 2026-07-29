package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoldoutExperimentTest {
    /*
     * Exact Hyperliquid mainnet BTC daily closes from 2026-07-18 through
     * 2026-07-28, retrieved from /info candleSnapshot on 2026-07-28.
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
    fun `candidate and controls fit once before all holdout labels are evaluated`() {
        val rows = observations()
        val candidate = CountingEstimator(LinearEstimator(listOf("last_return")))
        val mean = CountingEstimator(HistoricalMeanEstimator())
        val zero = CountingEstimator(ZeroReturnEstimator())
        val result =
            HoldoutExperiment(
                candidate = candidate,
                baselines =
                    listOf(
                        HoldoutBaseline("mean", mean),
                        HoldoutBaseline("zero", zero),
                    ),
                hacLag = 1,
            ).run(
                training = rows.take(5),
                holdout = rows.drop(5).take(2),
            )

        assertEquals(1, candidate.fitCount)
        assertEquals(1, mean.fitCount)
        assertEquals(1, zero.fitCount)
        assertEquals(2, result.observations.size)
        assertEquals(2, result.observations.map { it.rowId }.distinct().size)
        assertEquals(setOf("mean", "zero"), result.baselines.map { it.name }.toSet())
        assertTrue(result.observations.all { it.candidate.isFinite() })
    }

    private fun observations(): List<LabeledObservation> {
        val returns = closes.zipWithNext { previous, next -> ln(next / previous) }
        return (1 until returns.size).map { index ->
            val decision = START + index * DAY
            val rowId = "btc-day-${index - 1}"
            LabeledObservation(
                rowId = rowId,
                decisionTime = MarketTimestamp(decision),
                labelFrom = MarketTimestamp(decision),
                labelTo = MarketTimestamp(decision + DAY),
                features =
                    FeatureVector(
                        rowId = rowId,
                        values = mapOf("last_return" to returns[index - 1]),
                    ),
                label = returns[index],
            )
        }
    }

    private class CountingEstimator(
        private val delegate: Estimator,
    ) : Estimator {
        var fitCount = 0

        override fun fit(rows: List<LabeledObservation>): FittedEstimator {
            fitCount++
            return delegate.fit(rows)
        }
    }

    private companion object {
        const val DAY = 86_400_000L
        const val START = 1_784_332_800_000L
    }
}
