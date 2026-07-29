package dev.marketlab.sentiment

import java.nio.file.Path
import java.time.Duration

internal data class SentimentWorkerConfig(
    val rawRoot: Path,
    val featureRoot: Path,
    val modelsRoot: Path,
    val modelLock: Path,
    val universeRoot: Path,
    val programLock: Path,
    val sourceRevision: String,
    val pollInterval: Duration,
) {
    init {
        listOf(rawRoot, featureRoot, modelsRoot, modelLock, universeRoot, programLock).forEach {
            require(it.isAbsolute) { "sentiment worker paths must be absolute" }
        }
        require(rawRoot.normalize().nameCount >= 2 && featureRoot.normalize().nameCount >= 2) {
            "sentiment worker data roots are too broad"
        }
        require(rawRoot.normalize() != featureRoot.normalize()) {
            "raw and feature roots must be distinct"
        }
        require(pollInterval in Duration.ofSeconds(5)..Duration.ofMinutes(10)) {
            "sentiment poll interval is outside reviewed bounds"
        }
        require(sourceRevision.matches(Regex("[0-9a-f]{64}"))) {
            "source revision must be a lowercase SHA-256 digest"
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()) =
            SentimentWorkerConfig(
                rawRoot = Path.of(required(environment, "MARKETLAB_SOCIAL_RAW_ROOT")).normalize(),
                featureRoot =
                    Path.of(required(environment, "MARKETLAB_SENTIMENT_FEATURE_ROOT")).normalize(),
                modelsRoot =
                    Path.of(required(environment, "MARKETLAB_SENTIMENT_MODELS_ROOT")).normalize(),
                modelLock =
                    Path.of(required(environment, "MARKETLAB_SENTIMENT_MODEL_LOCK")).normalize(),
                universeRoot =
                    Path.of(required(environment, "MARKETLAB_SOCIAL_UNIVERSE_ROOT")).normalize(),
                programLock =
                    Path.of(required(environment, "MARKETLAB_SOCIAL_PROGRAM_LOCK")).normalize(),
                sourceRevision = required(environment, "MARKETLAB_SOURCE_REVISION"),
                pollInterval =
                    Duration.ofMillis(
                        environment["MARKETLAB_SENTIMENT_POLL_MILLIS"]
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                            ?: 30_000L,
                    ),
            )

        private fun required(environment: Map<String, String>, key: String): String =
            requireNotNull(environment[key]?.trim()?.takeIf(String::isNotEmpty)) {
                "$key is required"
            }
    }
}
