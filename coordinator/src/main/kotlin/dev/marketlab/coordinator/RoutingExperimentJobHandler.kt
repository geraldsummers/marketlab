package dev.marketlab.coordinator

import dev.marketlab.persistence.JobLease
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps theory-specific executors separate while retaining the existing
 * fail-closed control handler as the fallback for malformed and unsupported
 * experiment requests.
 */
internal class RoutingExperimentJobHandler(
    private val controls: ExperimentJobHandler,
    private val momentum: ExperimentJobHandler,
    private val harVariance: ExperimentJobHandler,
    private val hourlyReversal: ExperimentJobHandler,
    private val hourlyVolatilityPeriodicity: ExperimentJobHandler,
) : ExperimentJobHandler {
    override suspend fun execute(lease: JobLease): JobExecutionResult {
        val theoryId =
            (lease.job.payload["theoryId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
        return when (theoryId) {
            HYPERLIQUID_BTC_MOMENTUM_THEORY_ID -> momentum.execute(lease)
            HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID ->
                harVariance.execute(lease)
            HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID ->
                hourlyReversal.execute(lease)
            HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID ->
                hourlyVolatilityPeriodicity.execute(lease)
            else -> controls.execute(lease)
        }
    }
}
