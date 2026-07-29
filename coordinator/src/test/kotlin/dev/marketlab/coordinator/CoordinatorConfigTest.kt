package dev.marketlab.coordinator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.Path
import java.time.Duration

class CoordinatorConfigTest {
    @Test
    fun `defaults are production-bounded and deterministic`() {
        val config =
            CoordinatorConfig.fromEnvironment(
                mapOf("MARKETLAB_SOURCE_REVISION" to "a".repeat(64)),
                processId = 42,
            )

        assertEquals("coordinator-42", config.workerId)
        assertEquals("a".repeat(64), config.sourceRevision)
        assertEquals(Path.of("/mnt/media/marketlab/raw"), config.rawDataRoot)
        assertEquals(
            Path.of("/mnt/stack/marketlab/active-artifacts"),
            config.artifactRoot,
        )
        assertEquals(Duration.ofMinutes(2), config.leaseDuration)
        assertEquals(Duration.ofSeconds(30), config.heartbeatInterval)
        assertEquals("1h", config.defaultCandleInterval)
    }

    @Test
    fun `relative raw roots and unsafe heartbeat timing are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                    "MARKETLAB_RAW_DATA_ROOT" to "relative/raw",
                ),
                processId = 42,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CoordinatorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                    "MARKETLAB_ACTIVE_ARTIFACT_ROOT" to "relative/artifacts",
                ),
                processId = 42,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CoordinatorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                    "MARKETLAB_COORDINATOR_LEASE_MS" to "10000",
                    "MARKETLAB_COORDINATOR_HEARTBEAT_MS" to "4000",
                ),
                processId = 42,
            )
        }
    }

    @Test
    fun `malformed numeric environment values fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                    "MARKETLAB_COORDINATOR_POLL_MS" to "soon",
                ),
                processId = 42,
            )
        }
    }

    @Test
    fun `source revision is mandatory and immutable`() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatorConfig.fromEnvironment(emptyMap(), processId = 42)
        }
        assertFailsWith<IllegalArgumentException> {
            CoordinatorConfig.fromEnvironment(
                mapOf("MARKETLAB_SOURCE_REVISION" to "main"),
                processId = 42,
            )
        }
    }
}
