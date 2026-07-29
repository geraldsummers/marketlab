package dev.marketlab.collector

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollectorConfigTest {
    @Test
    fun `environment defaults remain bounded and BTC scoped`() {
        val config =
            CollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_COLLECTOR_RAW_ROOT" to "/mnt/media/marketlab/raw/hyperliquid-stream",
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                ),
            )

        assertEquals("BTC", config.coin)
        assertEquals(CollectorConfig.DEFAULT_QUEUE_CAPACITY, config.queueCapacity)
        assertEquals(
            CollectorConfig.DEFAULT_MAXIMUM_SEGMENT_BYTES,
            config.maximumSegmentBytes,
        )
        assertEquals(
            CollectorConfig.DEFAULT_MAXIMUM_FRAME_BYTES,
            config.maximumFrameBytes,
        )
    }

    @Test
    fun `relative raw roots and unversioned production builds are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_COLLECTOR_RAW_ROOT" to "relative/raw",
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_COLLECTOR_RAW_ROOT" to "/mnt/media/marketlab/raw/capture",
                    "MARKETLAB_SOURCE_REVISION" to "unknown",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorConfig(
                rawRoot = Path.of("/"),
                coin = "BTC",
                sourceRevision = "a".repeat(64),
                maximumSegmentBytes = CollectorConfig.DEFAULT_MAXIMUM_SEGMENT_BYTES,
                maximumSegmentDuration = CollectorConfig.DEFAULT_MAXIMUM_SEGMENT_DURATION,
                queueCapacity = CollectorConfig.DEFAULT_QUEUE_CAPACITY,
                maximumFrameBytes = CollectorConfig.DEFAULT_MAXIMUM_FRAME_BYTES,
            )
        }
    }
}
