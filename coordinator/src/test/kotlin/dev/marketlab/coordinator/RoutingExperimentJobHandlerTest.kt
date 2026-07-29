package dev.marketlab.coordinator

import dev.marketlab.persistence.JobLease
import dev.marketlab.persistence.JobRow
import dev.marketlab.persistence.JobStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RoutingExperimentJobHandlerTest {
    @Test
    fun `registered BTC momentum id routes only to momentum executor`() =
        runBlocking {
            val controls = RecordingHandler("controls")
            val momentum = RecordingHandler("momentum")
            val harVariance = RecordingHandler("har-variance")
            val hourlyReversal = RecordingHandler("hourly-reversal")
            val periodicity = RecordingHandler("periodicity")
            val router =
                RoutingExperimentJobHandler(
                    controls,
                    momentum,
                    harVariance,
                    hourlyReversal,
                    periodicity,
                )

            val result = router.execute(lease(HYPERLIQUID_BTC_MOMENTUM_THEORY_ID))

            assertEquals("momentum", (result as JobExecutionResult.Succeeded).result["handler"]
                .toString().trim('"'))
            assertEquals(0, controls.calls)
            assertEquals(1, momentum.calls)
            assertEquals(0, harVariance.calls)
            assertEquals(0, hourlyReversal.calls)
            assertEquals(0, periodicity.calls)
        }

    @Test
    fun `registered BTC HAR variance id routes only to HAR executor`() =
        runBlocking {
            val controls = RecordingHandler("controls")
            val momentum = RecordingHandler("momentum")
            val harVariance = RecordingHandler("har-variance")
            val hourlyReversal = RecordingHandler("hourly-reversal")
            val periodicity = RecordingHandler("periodicity")
            val router =
                RoutingExperimentJobHandler(
                    controls,
                    momentum,
                    harVariance,
                    hourlyReversal,
                    periodicity,
                )

            val result =
                router.execute(
                    lease(HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID),
                )

            assertEquals("har-variance", (result as JobExecutionResult.Succeeded).result["handler"]
                .toString().trim('"'))
            assertEquals(0, controls.calls)
            assertEquals(0, momentum.calls)
            assertEquals(1, harVariance.calls)
            assertEquals(0, hourlyReversal.calls)
            assertEquals(0, periodicity.calls)
        }

    @Test
    fun `registered BTC hourly reversal id routes only to reversal executor`() =
        runBlocking {
            val controls = RecordingHandler("controls")
            val momentum = RecordingHandler("momentum")
            val harVariance = RecordingHandler("har-variance")
            val hourlyReversal = RecordingHandler("hourly-reversal")
            val periodicity = RecordingHandler("periodicity")
            val router =
                RoutingExperimentJobHandler(
                    controls,
                    momentum,
                    harVariance,
                    hourlyReversal,
                    periodicity,
                )

            val result =
                router.execute(
                    lease(HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID),
                )

            assertEquals(
                "hourly-reversal",
                (result as JobExecutionResult.Succeeded).result["handler"].toString().trim('"'),
            )
            assertEquals(0, controls.calls)
            assertEquals(0, momentum.calls)
            assertEquals(0, harVariance.calls)
            assertEquals(1, hourlyReversal.calls)
            assertEquals(0, periodicity.calls)
        }

    @Test
    fun `registered ETH hourly volatility periodicity id routes only to periodicity executor`() =
        runBlocking {
            val controls = RecordingHandler("controls")
            val momentum = RecordingHandler("momentum")
            val harVariance = RecordingHandler("har-variance")
            val hourlyReversal = RecordingHandler("hourly-reversal")
            val periodicity = RecordingHandler("periodicity")
            val router =
                RoutingExperimentJobHandler(
                    controls,
                    momentum,
                    harVariance,
                    hourlyReversal,
                    periodicity,
                )

            val result =
                router.execute(
                    lease(HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID),
                )

            assertEquals(
                "periodicity",
                (result as JobExecutionResult.Succeeded).result["handler"].toString().trim('"'),
            )
            assertEquals(0, controls.calls)
            assertEquals(0, momentum.calls)
            assertEquals(0, harVariance.calls)
            assertEquals(0, hourlyReversal.calls)
            assertEquals(1, periodicity.calls)
        }

    @Test
    fun `all other ids retain fail closed control handler fallback`() =
        runBlocking {
            val controls = RecordingHandler("controls")
            val momentum = RecordingHandler("momentum")
            val harVariance = RecordingHandler("har-variance")
            val hourlyReversal = RecordingHandler("hourly-reversal")
            val periodicity = RecordingHandler("periodicity")
            val router =
                RoutingExperimentJobHandler(
                    controls,
                    momentum,
                    harVariance,
                    hourlyReversal,
                    periodicity,
                )

            router.execute(lease("unsupported-theory"))

            assertEquals(1, controls.calls)
            assertEquals(0, momentum.calls)
            assertEquals(0, harVariance.calls)
            assertEquals(0, hourlyReversal.calls)
            assertEquals(0, periodicity.calls)
        }

    private fun lease(theoryId: String): JobLease {
        val now = Instant.parse("2026-07-28T12:00:00Z")
        val runId = UUID.nameUUIDFromBytes(theoryId.toByteArray())
        return JobLease(
            job =
                JobRow(
                    id = UUID.nameUUIDFromBytes("job:$theoryId".toByteArray()),
                    kind = "EXPERIMENT_RUN",
                    resourceType = "run",
                    resourceId = runId.toString(),
                    payload = buildJsonObject { put("theoryId", theoryId) },
                    status = JobStatus.LEASED,
                    priority = 0,
                    availableAt = now,
                    maxAttempts = 3,
                    attemptCount = 1,
                    leaseOwner = "router-test",
                    leaseToken = UUID.nameUUIDFromBytes("lease:$theoryId".toByteArray()),
                    leaseUntil = now.plusSeconds(120),
                    cancellationRequested = false,
                    result = null,
                    lastError = null,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                ),
            attemptId = UUID.nameUUIDFromBytes("attempt:$theoryId".toByteArray()),
        )
    }

    private class RecordingHandler(
        private val name: String,
    ) : ExperimentJobHandler {
        var calls = 0

        override suspend fun execute(lease: JobLease): JobExecutionResult {
            calls++
            return JobExecutionResult.Succeeded(
                buildJsonObject { put("handler", name) },
            )
        }
    }
}
