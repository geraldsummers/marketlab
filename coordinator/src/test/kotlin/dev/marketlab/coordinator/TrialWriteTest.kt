package dev.marketlab.coordinator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TrialWriteTest {
    @Test
    fun `succeeded trial requires metrics and forbids failure`() {
        assertFailsWith<IllegalArgumentException> {
            trial(status = TrialStatus.SUCCEEDED, metrics = null, failure = null)
        }
        assertFailsWith<IllegalArgumentException> {
            trial(status = TrialStatus.SUCCEEDED, metrics = JsonNull, failure = null)
        }
        assertFailsWith<IllegalArgumentException> {
            trial(
                status = TrialStatus.SUCCEEDED,
                metrics = buildJsonObject { put("rmse", 0.1) },
                failure = buildJsonObject { put("code", "IMPOSSIBLE") },
            )
        }

        assertEquals(
            TrialStatus.SUCCEEDED,
            trial(
                status = TrialStatus.SUCCEEDED,
                metrics = buildJsonObject { put("rmse", 0.1) },
                failure = null,
            ).status,
        )
    }

    @Test
    fun `failed and abandoned trials require failure and forbid metrics`() {
        for (status in listOf(TrialStatus.FAILED, TrialStatus.ABANDONED)) {
            assertFailsWith<IllegalArgumentException> {
                trial(status = status, metrics = null, failure = null)
            }
            assertFailsWith<IllegalArgumentException> {
                trial(status = status, metrics = null, failure = JsonNull)
            }
            assertFailsWith<IllegalArgumentException> {
                trial(
                    status = status,
                    metrics = buildJsonObject { put("partial", true) },
                    failure = buildJsonObject { put("code", "STOPPED") },
                )
            }

            assertEquals(
                status,
                trial(
                    status = status,
                    metrics = null,
                    failure = buildJsonObject { put("code", "STOPPED") },
                ).status,
            )
        }
    }

    @Test
    fun `trial number cannot be negative`() {
        assertFailsWith<IllegalArgumentException> {
            TrialWrite(
                trialNumber = -1,
                parameters = buildJsonObject {},
                status = TrialStatus.SUCCEEDED,
                metrics = buildJsonObject { put("rmse", 0.1) },
                failure = null,
            )
        }
    }

    private fun trial(
        status: TrialStatus,
        metrics: kotlinx.serialization.json.JsonElement?,
        failure: kotlinx.serialization.json.JsonElement?,
    ): TrialWrite =
        TrialWrite(
            trialNumber = 0,
            parameters = buildJsonObject { put("lookbackDays", 30) },
            status = status,
            metrics = metrics,
            failure = failure,
        )
}
