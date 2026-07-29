package dev.marketlab.engine

import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.theory.Comparison
import dev.marketlab.theory.Metric
import dev.marketlab.theory.MetricCriterion
import dev.marketlab.theory.PromotionGate
import kotlin.test.Test
import kotlin.test.assertEquals

class PromotionEvaluatorTest {
    @Test
    fun `missing execution evidence blocks paper promotion`() {
        val evaluation = PromotionEvaluator.evaluate(
            gate = gate,
            evidence = PromotionEvidence(
                metrics = mapOf(Metric.NET_RETURN to 0.03),
                dataQualityPassed = true,
                hyperliquidHoldoutPassed = false,
                doubledCostScenarioPassed = false,
            ),
        )

        assertEquals(PromotionDecision.BLOCKED, evaluation.decision)
    }

    @Test
    fun `complete evidence must satisfy every registered criterion`() {
        val evaluation = PromotionEvaluator.evaluate(
            gate = gate,
            evidence = PromotionEvidence(
                metrics = mapOf(Metric.NET_RETURN to -0.01),
                dataQualityPassed = true,
                hyperliquidHoldoutPassed = true,
                doubledCostScenarioPassed = true,
            ),
        )

        assertEquals(PromotionDecision.FAILED, evaluation.decision)
    }

    private val gate = PromotionGate(
        criteria = listOf(
            MetricCriterion(
                metric = Metric.NET_RETURN,
                comparison = Comparison.GREATER_THAN,
                threshold = FiniteDouble(0.0),
                description = "net return must remain positive",
            ),
        ),
    )
}
