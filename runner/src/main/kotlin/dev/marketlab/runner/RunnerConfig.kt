package dev.marketlab.runner

import java.nio.file.Path
import kotlin.io.path.Path

data class WorkerProfile(
    val capabilityId: String,
    val image: String,
    val cpuCount: Int,
    val memoryMiB: Int,
    val pidsLimit: Int,
    val tmpfsBytes: Long,
    val maxRuntimeSeconds: Long,
    val gpu: Boolean = false,
)

data class RunnerConfig(
    val host: String,
    val port: Int,
    val bearerToken: String,
    val jobsRoot: Path,
    val podmanBinary: String,
    val profiles: Map<String, WorkerProfile>,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RunnerConfig {
            val token = environment.required("MARKETLAB_RUNNER_TOKEN")
            require(token.length >= 32) { "MARKETLAB_RUNNER_TOKEN must contain at least 32 characters" }

            fun profile(
                id: String,
                imageVariable: String,
                cpuCount: Int,
                memoryMiB: Int,
                pids: Int,
                tmpfs: Long,
                runtime: Long,
                gpu: Boolean = false,
            ): WorkerProfile? = environment[imageVariable]
                ?.takeIf(String::isNotBlank)
                ?.let { WorkerProfile(id, it, cpuCount, memoryMiB, pids, tmpfs, runtime, gpu) }

            val profiles = listOfNotNull(
                profile("kotlin-backtest-v1", "MARKETLAB_IMAGE_KOTLIN", 20, 49_152, 512, 8L shl 30, 86_400),
                profile("python-sklearn-cpu-v1", "MARKETLAB_IMAGE_PYTHON", 20, 49_152, 512, 8L shl 30, 86_400),
                profile(
                    "python-torch-gpu-v1",
                    "MARKETLAB_IMAGE_PYTHON_GPU",
                    20,
                    49_152,
                    1024,
                    12L shl 30,
                    86_400,
                    gpu = true,
                ),
                profile("r-forecast-cpu-v1", "MARKETLAB_IMAGE_R", 20, 49_152, 512, 8L shl 30, 86_400),
            ).associateBy(WorkerProfile::capabilityId)

            require(profiles.isNotEmpty()) { "At least one pinned worker image must be configured" }
            profiles.values.forEach {
                require(it.image.contains("@sha256:")) {
                    "Worker image ${it.capabilityId} must be pinned by sha256 digest"
                }
            }

            return RunnerConfig(
                host = environment["MARKETLAB_RUNNER_HOST"] ?: "127.0.0.1",
                port = environment["MARKETLAB_RUNNER_PORT"]?.toIntOrNull() ?: 8081,
                bearerToken = token,
                jobsRoot = Path(environment["MARKETLAB_JOBS_ROOT"] ?: "/mnt/stack/marketlab/jobs")
                    .toAbsolutePath()
                    .normalize(),
                podmanBinary = environment["MARKETLAB_PODMAN"] ?: "/usr/bin/podman",
                profiles = profiles,
            )
        }
    }
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank) ?: error("Missing required environment variable $name")
