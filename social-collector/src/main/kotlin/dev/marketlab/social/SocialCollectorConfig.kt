package dev.marketlab.social

import java.net.URI
import java.nio.file.Path
import java.time.Duration

internal data class SocialCollectorConfig(
    val rawRoot: Path,
    val sourceRevision: String,
    val maximumSegmentBytes: Long,
    val maximumSegmentDuration: Duration,
    val queueCapacity: Int,
    val maximumFrameBytes: Long,
    val blueskyUris: List<URI>,
    val nostrUris: List<URI>,
    val gdeltLastUpdateUri: URI,
    val rssUris: List<URI>,
    val mastodonUris: List<URI>,
    val farcasterEnabled: Boolean,
    val farcasterEventsUri: URI,
    val sourcePollInterval: Duration,
    val universeRefreshInterval: Duration,
) {
    init {
        require(rawRoot.isAbsolute && rawRoot.normalize().nameCount >= 2) {
            "social collector raw root must be a narrow absolute path"
        }
        require(REVISION.matches(sourceRevision)) {
            "source revision must be a lowercase SHA-256"
        }
        require(maximumSegmentBytes in 4L * MIB..1024L * MIB) {
            "segment byte limit is outside reviewed bounds"
        }
        require(maximumSegmentDuration in Duration.ofMinutes(1)..Duration.ofHours(1)) {
            "segment duration is outside reviewed bounds"
        }
        require(queueCapacity in 1..4096) { "queue capacity is outside reviewed bounds" }
        require(maximumFrameBytes in 64L * 1024L..16L * MIB) {
            "frame byte limit is outside reviewed bounds"
        }
        require(blueskyUris.isNotEmpty() && blueskyUris.all { it.scheme == "wss" }) {
            "at least one TLS Bluesky Jetstream endpoint is required"
        }
        require(nostrUris.size >= 2 && nostrUris.all { it.scheme == "wss" }) {
            "at least two TLS Nostr relays are required"
        }
        require(gdeltLastUpdateUri.scheme in setOf("http", "https")) {
            "GDELT update URI must use HTTP(S)"
        }
        require(rssUris.isNotEmpty() && rssUris.all { it.scheme == "https" }) {
            "official RSS sources must use HTTPS"
        }
        require(mastodonUris.all { it.scheme == "https" }) {
            "Mastodon sources must use HTTPS"
        }
        require(!farcasterEnabled || farcasterEventsUri.scheme in setOf("http", "https")) {
            "Farcaster Snapchain events URI must use HTTP(S)"
        }
        require(sourcePollInterval in Duration.ofSeconds(30)..Duration.ofMinutes(30)) {
            "source poll interval is outside reviewed bounds"
        }
        require(universeRefreshInterval in Duration.ofMinutes(15)..Duration.ofHours(24)) {
            "universe refresh interval is outside reviewed bounds"
        }
    }

    companion object {
        private const val MIB = 1024L * 1024L
        const val DEFAULT_MAXIMUM_SEGMENT_BYTES = 64L * MIB
        val DEFAULT_MAXIMUM_SEGMENT_DURATION: Duration = Duration.ofMinutes(15)
        const val DEFAULT_QUEUE_CAPACITY = 1024
        const val DEFAULT_MAXIMUM_FRAME_BYTES = 4L * MIB
        val DEFAULT_SOURCE_POLL_INTERVAL: Duration = Duration.ofMinutes(5)
        val DEFAULT_UNIVERSE_REFRESH_INTERVAL: Duration = Duration.ofHours(1)

        private val REVISION = Regex("[0-9a-f]{64}")

        private val DEFAULT_BLUESKY =
            listOf("wss://jetstream2.us-east.bsky.network/subscribe")
        private val DEFAULT_NOSTR =
            listOf(
                "wss://relay.damus.io",
                "wss://nos.lol",
                "wss://relay.primal.net",
                "wss://relay.nostr.band",
            )
        private const val DEFAULT_GDELT =
            "http://data.gdeltproject.org/gdeltv2/lastupdate.txt"
        private val DEFAULT_RSS =
            listOf(
                "https://www.coindesk.com/arc/outboundfeeds/rss/",
                "https://cointelegraph.com/rss",
                "https://decrypt.co/feed",
                "https://bitcoinmagazine.com/.rss/full/",
                "https://blockworks.co/feed",
            )
        private val DEFAULT_MASTODON =
            listOf(
                "https://mastodon.social/api/v1/timelines/tag/bitcoin?limit=40",
                "https://mastodon.social/api/v1/timelines/tag/ethereum?limit=40",
                "https://mastodon.social/api/v1/timelines/tag/crypto?limit=40",
            )
        private const val DEFAULT_FARCASTER_EVENTS =
            "http://marketlab-snapchain:3381/v1/events"

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): SocialCollectorConfig =
            SocialCollectorConfig(
                rawRoot =
                    Path.of(required(environment, "MARKETLAB_SOCIAL_RAW_ROOT")).normalize(),
                sourceRevision = required(environment, "MARKETLAB_SOURCE_REVISION"),
                maximumSegmentBytes =
                    positiveLong(
                        environment,
                        "MARKETLAB_SOCIAL_MAX_SEGMENT_BYTES",
                        DEFAULT_MAXIMUM_SEGMENT_BYTES,
                    ),
                maximumSegmentDuration =
                    Duration.ofMillis(
                        positiveLong(
                            environment,
                            "MARKETLAB_SOCIAL_MAX_SEGMENT_MILLIS",
                            DEFAULT_MAXIMUM_SEGMENT_DURATION.toMillis(),
                        ),
                    ),
                queueCapacity =
                    positiveInt(
                        environment,
                        "MARKETLAB_SOCIAL_QUEUE_CAPACITY",
                        DEFAULT_QUEUE_CAPACITY,
                    ),
                maximumFrameBytes =
                    positiveLong(
                        environment,
                        "MARKETLAB_SOCIAL_MAX_FRAME_BYTES",
                        DEFAULT_MAXIMUM_FRAME_BYTES,
                    ),
                blueskyUris = uriList(environment, "MARKETLAB_SOCIAL_BLUESKY_URIS", DEFAULT_BLUESKY),
                nostrUris = uriList(environment, "MARKETLAB_SOCIAL_NOSTR_URIS", DEFAULT_NOSTR),
                gdeltLastUpdateUri =
                    URI.create(
                        environment["MARKETLAB_SOCIAL_GDELT_LAST_UPDATE_URI"]
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: DEFAULT_GDELT,
                    ),
                rssUris = uriList(environment, "MARKETLAB_SOCIAL_RSS_URIS", DEFAULT_RSS),
                mastodonUris =
                    uriList(environment, "MARKETLAB_SOCIAL_MASTODON_URIS", DEFAULT_MASTODON),
                farcasterEnabled =
                    boolean(environment, "MARKETLAB_SOCIAL_FARCASTER_ENABLED", false),
                farcasterEventsUri =
                    URI.create(
                        environment["MARKETLAB_SOCIAL_FARCASTER_EVENTS_URI"]
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: DEFAULT_FARCASTER_EVENTS,
                    ),
                sourcePollInterval =
                    Duration.ofMillis(
                        positiveLong(
                            environment,
                            "MARKETLAB_SOCIAL_POLL_MILLIS",
                            DEFAULT_SOURCE_POLL_INTERVAL.toMillis(),
                        ),
                    ),
                universeRefreshInterval =
                    Duration.ofMillis(
                        positiveLong(
                            environment,
                            "MARKETLAB_SOCIAL_UNIVERSE_REFRESH_MILLIS",
                            DEFAULT_UNIVERSE_REFRESH_INTERVAL.toMillis(),
                        ),
                    ),
            )

        private fun boolean(
            environment: Map<String, String>,
            key: String,
            default: Boolean,
        ): Boolean =
            when (val value = environment[key]?.trim()?.lowercase()) {
                null, "" -> default
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$key must be true or false")
            }

        private fun required(environment: Map<String, String>, key: String): String =
            requireNotNull(environment[key]?.trim()?.takeIf(String::isNotEmpty)) {
                "$key is required"
            }

        private fun uriList(
            environment: Map<String, String>,
            key: String,
            default: List<String>,
        ): List<URI> =
            (environment[key]?.split(',') ?: default)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(URI::create)
                .distinct()

        private fun positiveLong(
            environment: Map<String, String>,
            key: String,
            default: Long,
        ): Long =
            environment[key]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: if (environment[key].isNullOrBlank()) {
                    default
                } else {
                    throw IllegalArgumentException("$key must be a positive integer")
                }

        private fun positiveInt(
            environment: Map<String, String>,
            key: String,
            default: Int,
        ): Int =
            positiveLong(environment, key, default.toLong())
                .also { require(it <= Int.MAX_VALUE) { "$key exceeds Int range" } }
                .toInt()
    }
}
