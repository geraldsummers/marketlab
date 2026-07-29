package dev.marketlab.engine

import dev.marketlab.theory.Comparison
import dev.marketlab.theory.Metric
import dev.marketlab.theory.PromotionGate

enum class PromotionDecision {
    PASSED,
    FAILED,
    BLOCKED,
}

data class PromotionEvaluation(
    val decision: PromotionDecision,
    val reasons: List<String>,
) {
    init {
        require(reasons.isNotEmpty() || decision == PromotionDecision.PASSED)
    }
}

data class PromotionEvidence(
    val metrics: Map<Metric, Double>,
    val dataQualityPassed: Boolean,
    val hyperliquidHoldoutPassed: Boolean,
    val doubledCostScenarioPassed: Boolean,
) {
    init {
        require(metrics.values.all(Double::isFinite)) { "promotion metrics must be finite" }
    }
}

/**
 * Central promotion gate used before a run can be marked paper-eligible. A
 * missing metric blocks rather than silently failing or defaulting a value.
 */
object PromotionEvaluator {
    fun evaluate(gate: PromotionGate, evidence: PromotionEvidence): PromotionEvaluation {
        val blockers = buildList {
            if (!gate.eligibleForPaper) add("the registered theory is not eligible for paper trading")
            if (gate.requireAllDataQualityChecks && !evidence.dataQualityPassed) {
                add("the immutable data snapshot did not pass every quality check")
            }
            if (gate.requireHyperliquidHoldout && !evidence.hyperliquidHoldoutPassed) {
                add("the registered Hyperliquid holdout is absent or failed")
            }
            if (gate.requireDoubledCostSurvival && !evidence.doubledCostScenarioPassed) {
                add("the result did not survive doubled execution costs")
            }
            gate.criteria.forEach { criterion ->
                if (criterion.metric !in evidence.metrics) {
                    add("required metric ${criterion.metric} is missing")
                }
            }
        }
        if (blockers.isNotEmpty()) return PromotionEvaluation(PromotionDecision.BLOCKED, blockers)

        val failures = gate.criteria.mapNotNull { criterion ->
            val actual = evidence.metrics.getValue(criterion.metric)
            val passed = when (criterion.comparison) {
                Comparison.LESS_THAN -> actual < criterion.threshold.value
                Comparison.LESS_THAN_OR_EQUAL -> actual <= criterion.threshold.value
                Comparison.GREATER_THAN -> actual > criterion.threshold.value
                Comparison.GREATER_THAN_OR_EQUAL -> actual >= criterion.threshold.value
            }
            criterion.description.takeUnless { passed }
        }
        return if (failures.isEmpty()) {
            PromotionEvaluation(PromotionDecision.PASSED, emptyList())
        } else {
            PromotionEvaluation(PromotionDecision.FAILED, failures)
        }
    }
}
