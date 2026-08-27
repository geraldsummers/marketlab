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
    handlers: Map<String, ExperimentJobHandler>,
) : ExperimentJobHandler {
    private val handlers = handlers.toMap()

    init {
        require(this.handlers.keys.none(String::isBlank)) { "theory handler ids must not be blank" }
    }

    constructor(
        controls: ExperimentJobHandler,
        momentum: ExperimentJobHandler,
        harVariance: ExperimentJobHandler,
        hourlyReversal: ExperimentJobHandler,
        hourlyVolatilityPeriodicity: ExperimentJobHandler,
    ) : this(
        controls = controls,
        handlers =
            mapOf(
                HYPERLIQUID_BTC_MOMENTUM_THEORY_ID to momentum,
                HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID to harVariance,
                HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID to hourlyReversal,
                HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID to
                    hourlyVolatilityPeriodicity,
            ),
    )

    override suspend fun execute(lease: JobLease): JobExecutionResult {
        val theoryId =
            (lease.job.payload["theoryId"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
        return handlers[theoryId]?.execute(lease) ?: controls.execute(lease)
    }
}
