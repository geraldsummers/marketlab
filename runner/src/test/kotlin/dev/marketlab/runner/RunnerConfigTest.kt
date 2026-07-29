package dev.marketlab.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RunnerConfigTest {
    private val digest = "registry.example/marketlab/worker@sha256:" + "a".repeat(64)

    @Test
    fun `requires digest pinned images`() {
        assertFailsWith<IllegalArgumentException> {
            RunnerConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_RUNNER_TOKEN" to "x".repeat(32),
                    "MARKETLAB_IMAGE_KOTLIN" to "marketlab/worker:latest",
                ),
            )
        }
    }

    @Test
    fun `constructs fixed allowlist`() {
        val config = RunnerConfig.fromEnvironment(
            mapOf(
                "MARKETLAB_RUNNER_TOKEN" to "x".repeat(32),
                "MARKETLAB_IMAGE_KOTLIN" to digest,
            ),
        )
        assertEquals(setOf("kotlin-backtest-v1"), config.profiles.keys)
        assertEquals("127.0.0.1", config.host)
    }
}

