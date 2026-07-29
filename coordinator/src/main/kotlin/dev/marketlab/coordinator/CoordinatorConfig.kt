package dev.marketlab.coordinator

import java.nio.file.Path
import java.time.Duration

data class CoordinatorConfig(
    val workerId: String,
    val sourceRevision: String,
    val rawDataRoot: Path,
    val artifactRoot: Path,
    val leaseDuration: Duration,
    val heartbeatInterval: Duration,
    val idlePollInterval: Duration,
    val reapInterval: Duration,
    val retryDelay: Duration,
    val defaultCandleInterval: String,
    val maximumRange: Duration,
) {
    init {
        require(WORKER_ID.matches(workerId)) {
            "coordinator worker id must contain only portable worker-id characters"
        }
        require(SHA256.matches(sourceRevision)) {
            "coordinator source revision must be a lowercase SHA-256 digest"
        }
        require(rawDataRoot.isAbsolute) { "raw-data root must be absolute" }
        require(rawDataRoot.normalize() != rawDataRoot.root) {
            "raw-data root cannot be a filesystem root"
        }
        require(artifactRoot.isAbsolute) { "artifact root must be absolute" }
        require(artifactRoot.normalize() != artifactRoot.root) {
            "artifact root cannot be a filesystem root"
        }
        require(leaseDuration in Duration.ofSeconds(10)..Duration.ofHours(24)) {
            "lease duration must be between 10 seconds and 24 hours"
        }
        require(heartbeatInterval >= Duration.ofSeconds(1)) {
            "heartbeat interval must be at least one second"
        }
        require(heartbeatInterval.multipliedBy(3) < leaseDuration) {
            "heartbeat interval must be less than one third of the lease duration"
        }
        require(idlePollInterval in Duration.ofMillis(50)..Duration.ofMinutes(1)) {
            "idle poll interval must be between 50 ms and one minute"
        }
        require(reapInterval in Duration.ofSeconds(1)..Duration.ofHours(1)) {
            "reap interval must be between one second and one hour"
        }
        require(!retryDelay.isNegative && retryDelay <= Duration.ofDays(7)) {
            "retry delay must be between zero and seven days"
        }
        require(defaultCandleInterval in IngestionJobParser.SUPPORTED_CANDLE_INTERVALS) {
            "unsupported default candle interval"
        }
        require(maximumRange in Duration.ofMinutes(1)..Duration.ofDays(3_650)) {
            "maximum ingestion range must be between one minute and ten years"
        }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            processId: Long = ProcessHandle.current().pid(),
        ): CoordinatorConfig =
            CoordinatorConfig(
                workerId =
                    environment["MARKETLAB_COORDINATOR_ID"]
                        ?: "coordinator-$processId",
                sourceRevision =
                    environment["MARKETLAB_SOURCE_REVISION"]
                        ?: throw IllegalArgumentException(
                            "MARKETLAB_SOURCE_REVISION is required",
                        ),
                rawDataRoot =
                    Path.of(
                        environment["MARKETLAB_RAW_DATA_ROOT"]
                            ?: "/mnt/media/marketlab/raw",
                    ).normalize(),
                artifactRoot =
                    Path.of(
                        environment["MARKETLAB_ACTIVE_ARTIFACT_ROOT"]
                            ?: "/mnt/stack/marketlab/active-artifacts",
                    ).normalize(),
                leaseDuration =
                    durationMillis(
                        environment,
                        "MARKETLAB_COORDINATOR_LEASE_MS",
                        120_000,
                    ),
                heartbeatInterval =
                    durationMillis(
                        environment,
                        "MARKETLAB_COORDINATOR_HEARTBEAT_MS",
                        30_000,
                    ),
                idlePollInterval =
                    durationMillis(
                        environment,
                        "MARKETLAB_COORDINATOR_POLL_MS",
                        1_000,
                    ),
                reapInterval =
                    durationMillis(
                        environment,
                        "MARKETLAB_COORDINATOR_REAP_MS",
                        30_000,
                    ),
                retryDelay =
                    durationMillis(
                        environment,
                        "MARKETLAB_COORDINATOR_RETRY_MS",
                        15_000,
                    ),
                defaultCandleInterval =
                    environment["MARKETLAB_DEFAULT_CANDLE_INTERVAL"] ?: "1h",
                maximumRange =
                    Duration.ofDays(
                        environment["MARKETLAB_MAX_INGESTION_DAYS"]?.toLongStrict(
                            "MARKETLAB_MAX_INGESTION_DAYS",
                        ) ?: 3_650,
                    ),
            )

        private fun durationMillis(
            environment: Map<String, String>,
            name: String,
            defaultMillis: Long,
        ): Duration =
            Duration.ofMillis(
                environment[name]?.toLongStrict(name) ?: defaultMillis,
            )

        private fun String.toLongStrict(name: String): Long =
            toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer")

        private val WORKER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
