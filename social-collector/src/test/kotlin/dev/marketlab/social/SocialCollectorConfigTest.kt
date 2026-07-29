package dev.marketlab.social

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SocialCollectorConfigTest {
    @Test
    fun `credential-free defaults pin reviewed public sources`() {
        val config =
            SocialCollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOCIAL_RAW_ROOT" to "/mnt/media/marketlab/raw/public-information",
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                ),
            )

        assertTrue(config.blueskyUris.all { it.scheme == "wss" })
        assertEquals(4, config.nostrUris.size)
        assertEquals(5, config.rssUris.size)
        assertTrue(config.rssUris.all { it.scheme == "https" })
        assertEquals(false, config.farcasterEnabled)
    }

    @Test
    fun `broad roots insecure endpoints and unversioned builds fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            SocialCollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOCIAL_RAW_ROOT" to "/",
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SocialCollectorConfig(
                rawRoot = Path.of("/mnt/media/social"),
                sourceRevision = "not-a-revision",
                maximumSegmentBytes = SocialCollectorConfig.DEFAULT_MAXIMUM_SEGMENT_BYTES,
                maximumSegmentDuration = SocialCollectorConfig.DEFAULT_MAXIMUM_SEGMENT_DURATION,
                queueCapacity = SocialCollectorConfig.DEFAULT_QUEUE_CAPACITY,
                maximumFrameBytes = SocialCollectorConfig.DEFAULT_MAXIMUM_FRAME_BYTES,
                blueskyUris = listOf(java.net.URI.create("ws://unsafe.example/")),
                nostrUris =
                    listOf(
                        java.net.URI.create("wss://one.example"),
                        java.net.URI.create("wss://two.example"),
                    ),
                gdeltLastUpdateUri = java.net.URI.create("https://example.test/update"),
                rssUris = listOf(java.net.URI.create("https://example.test/feed")),
                mastodonUris = emptyList(),
                farcasterEnabled = true,
                farcasterEventsUri = java.net.URI.create("http://snapchain.test:3381/v1/events"),
                sourcePollInterval = SocialCollectorConfig.DEFAULT_SOURCE_POLL_INTERVAL,
                universeRefreshInterval = SocialCollectorConfig.DEFAULT_UNIVERSE_REFRESH_INTERVAL,
            )
        }
    }

    @Test
    fun `Farcaster is an explicit opt in`() {
        val config =
            SocialCollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOCIAL_RAW_ROOT" to "/mnt/media/marketlab/raw/public-information",
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                    "MARKETLAB_SOCIAL_FARCASTER_ENABLED" to "true",
                ),
            )

        assertTrue(config.farcasterEnabled)
        assertFailsWith<IllegalArgumentException> {
            SocialCollectorConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_SOCIAL_RAW_ROOT" to "/mnt/media/marketlab/raw/public-information",
                    "MARKETLAB_SOURCE_REVISION" to "a".repeat(64),
                    "MARKETLAB_SOCIAL_FARCASTER_ENABLED" to "sometimes",
                ),
            )
        }
    }
}
