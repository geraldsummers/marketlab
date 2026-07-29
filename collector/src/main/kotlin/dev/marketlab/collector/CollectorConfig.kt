package dev.marketlab.collector

import java.nio.file.Path
import java.time.Duration

data class CollectorConfig(
    val rawRoot: Path,
    val coin: String,
    val sourceRevision: String,
    val maximumSegmentBytes: Long,
    val maximumSegmentDuration: Duration,
    val queueCapacity: Int,
    val maximumFrameBytes: Long,
    val universeMode: UniverseMode = UniverseMode.STATIC,
    val universeRoot: Path? = null,
) {
    init {
        require(rawRoot.isAbsolute) { "collector raw root must be absolute" }
        require(rawRoot.normalize().nameCount >= 2) { "collector raw root is too broad" }
        require(SYMBOL_PATTERN.matches(coin)) { "invalid Hyperliquid symbol" }
        require(coin == coin.uppercase()) { "Hyperliquid symbol must be uppercase" }
        require(SOURCE_REVISION_PATTERN.matches(sourceRevision)) {
            "source revision must be a 64-character lowercase SHA-256"
        }
        require(maximumSegmentBytes in MINIMUM_SEGMENT_BYTES..MAXIMUM_SEGMENT_BYTES) {
            "maximum segment bytes must be between $MINIMUM_SEGMENT_BYTES and $MAXIMUM_SEGMENT_BYTES"
        }
        require(maximumSegmentDuration in MINIMUM_SEGMENT_DURATION..MAXIMUM_SEGMENT_DURATION) {
            "maximum segment duration must be between $MINIMUM_SEGMENT_DURATION and $MAXIMUM_SEGMENT_DURATION"
        }
        require(queueCapacity in 1..MAXIMUM_QUEUE_CAPACITY) {
            "collector queue capacity must be between 1 and $MAXIMUM_QUEUE_CAPACITY"
        }
        require(maximumFrameBytes in MINIMUM_FRAME_BYTES..MAXIMUM_FRAME_BYTES) {
            "maximum WebSocket frame bytes must be between $MINIMUM_FRAME_BYTES and $MAXIMUM_FRAME_BYTES"
        }
        require(
            maximumSegmentBytes >=
                Math.addExact(Math.multiplyExact(maximumFrameBytes, 2L), RECORD_HEADROOM_BYTES),
        ) {
            "segment byte limit must leave room for one base64-encoded maximum-size frame"
        }
        require(universeMode == UniverseMode.STATIC || universeRoot != null) {
            "dynamic universe mode requires a universe root"
        }
        universeRoot?.let {
            require(it.isAbsolute && it.normalize().nameCount >= 2) {
                "collector universe root is too broad"
            }
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_SEGMENT_BYTES = 128L * 1024L * 1024L
        val DEFAULT_MAXIMUM_SEGMENT_DURATION: Duration = Duration.ofMinutes(15)
        const val DEFAULT_QUEUE_CAPACITY = 64
        const val DEFAULT_MAXIMUM_FRAME_BYTES = 2L * 1024L * 1024L

        private const val MINIMUM_SEGMENT_BYTES = 4L * 1024L * 1024L
        private const val MAXIMUM_SEGMENT_BYTES = 1024L * 1024L * 1024L
        private val MINIMUM_SEGMENT_DURATION: Duration = Duration.ofMinutes(1)
        private val MAXIMUM_SEGMENT_DURATION: Duration = Duration.ofHours(1)
        private const val MAXIMUM_QUEUE_CAPACITY = 1024
        private const val MINIMUM_FRAME_BYTES = 64L * 1024L
        private const val MAXIMUM_FRAME_BYTES = 16L * 1024L * 1024L
        private const val RECORD_HEADROOM_BYTES = 64L * 1024L
        private val SYMBOL_PATTERN = Regex("[A-Z0-9][A-Z0-9._:-]{0,63}")
        private val SOURCE_REVISION_PATTERN = Regex("[0-9a-f]{64}")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CollectorConfig {
            val rawRoot =
                required(environment, "MARKETLAB_COLLECTOR_RAW_ROOT")
                    .let(Path::of)
                    .normalize()
            return CollectorConfig(
                rawRoot = rawRoot,
                coin = environment["MARKETLAB_COLLECTOR_COIN"]?.trim().orEmpty().ifEmpty { "BTC" },
                sourceRevision = required(environment, "MARKETLAB_SOURCE_REVISION"),
                maximumSegmentBytes =
                    positiveLong(
                        environment,
                        "MARKETLAB_COLLECTOR_MAX_SEGMENT_BYTES",
                        DEFAULT_MAXIMUM_SEGMENT_BYTES,
                    ),
                maximumSegmentDuration =
                    Duration.ofMillis(
                        positiveLong(
                            environment,
                            "MARKETLAB_COLLECTOR_MAX_SEGMENT_MILLIS",
                            DEFAULT_MAXIMUM_SEGMENT_DURATION.toMillis(),
                        ),
                    ),
                queueCapacity =
                    positiveInt(
                        environment,
                        "MARKETLAB_COLLECTOR_QUEUE_CAPACITY",
                        DEFAULT_QUEUE_CAPACITY,
                    ),
                maximumFrameBytes =
                    positiveLong(
                        environment,
                        "MARKETLAB_COLLECTOR_MAX_FRAME_BYTES",
                        DEFAULT_MAXIMUM_FRAME_BYTES,
                    ),
                universeMode =
                    environment["MARKETLAB_COLLECTOR_UNIVERSE_MODE"]
                        ?.trim()
                        ?.uppercase()
                        ?.let(UniverseMode::valueOf)
                        ?: UniverseMode.STATIC,
                universeRoot =
                    environment["MARKETLAB_SOCIAL_UNIVERSE_ROOT"]
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(Path::of)
                        ?.normalize(),
            )
        }

        private fun required(environment: Map<String, String>, key: String): String =
            requireNotNull(environment[key]?.trim()?.takeIf(String::isNotEmpty)) {
                "$key is required"
            }

        private fun positiveLong(
            environment: Map<String, String>,
            key: String,
            default: Long,
        ): Long {
            val raw = environment[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
            return raw.toLongOrNull()?.takeIf { it > 0L }
                ?: throw IllegalArgumentException("$key must be a positive integer")
        }

        private fun positiveInt(
            environment: Map<String, String>,
            key: String,
            default: Int,
        ): Int {
            val raw = environment[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
            return raw.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("$key must be a positive integer")
        }
    }
}

enum class UniverseMode {
    STATIC,
    TOP_NOTIONAL_30D,
}
