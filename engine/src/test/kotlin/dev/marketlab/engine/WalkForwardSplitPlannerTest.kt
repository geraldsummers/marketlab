package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkForwardSplitPlannerTest {
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
    fun `minimum training count is measured after purge and embargo`() {
        val planner =
            WalkForwardSplitPlanner(
                WalkForwardConfig(
                    minimumTrainingRows = 4,
                    testRows = 2,
                    stepRows = 2,
                    purgeMillis = DAY,
                    embargoMillis = DAY,
                    maximumTrainingRows = 4,
                ),
            )

        val folds = planner.plan(observations())

        assertEquals(2, folds.size)
        assertTrue(folds.all { it.training.size == 4 })
        assertEquals("btc-day-5", folds.first().testRowIds.first())
        assertTrue(
            folds.all { fold ->
                val firstTestDecision =
                    observations().first { it.rowId == fold.testRowIds.first() }.decisionTime
                fold.training.all { it.labelTo.epochMillis + DAY <= firstTestDecision.epochMillis }
            },
        )
    }

    private fun observations(): List<LabeledObservation> {
        val openTimes = (0 until closes.size).map { 1_784_332_800_000L + it * DAY }
        val returns = closes.zipWithNext { previous, next -> ln(next / previous) }
        return (1 until returns.size).map { index ->
            val decision = openTimes[index]
            val rowId = "btc-day-${index - 1}"
            LabeledObservation(
                rowId = rowId,
                decisionTime = MarketTimestamp(decision),
                labelFrom = MarketTimestamp(decision),
                labelTo = MarketTimestamp(openTimes[index + 1]),
                features =
                    FeatureVector(
                        rowId = rowId,
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
