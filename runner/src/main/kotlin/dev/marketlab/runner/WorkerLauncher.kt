package dev.marketlab.runner

import dev.marketlab.contracts.worker.WorkerInvocationManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

class WorkerLauncher(
    private val config: RunnerConfig,
    private val scope: CoroutineScope,
) {
    private data class Running(
        val request: LaunchWorkerRequest,
        val process: Process,
        val monitor: Job,
    )

    private val states = ConcurrentHashMap<String, RunnerJobResponse>()
    private val running = ConcurrentHashMap<String, Running>()
    private val json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    fun launch(request: LaunchWorkerRequest): RunnerJobResponse {
        validate(request)
        val key = key(request.jobId, request.attemptId)
        states[key]?.let { return it }

        val profile = config.profiles.getValue(request.capabilityId)
        val attemptRoot = resolveAttemptRoot(request)
        val inputDir = attemptRoot.resolve("input")
        val outputDir = attemptRoot.resolve("output")
        val manifest = inputDir.resolve("manifest.json")
        require(manifest.exists() && manifest.isRegularFile()) {
            "The immutable input manifest does not exist"
        }
        require(manifest.toRealPath().startsWith(inputDir.toRealPath())) {
            "The input manifest escaped its attempt directory"
        }
        require(!Files.exists(outputDir) || !Files.isSymbolicLink(outputDir)) {
            "The worker output directory cannot be a symbolic link"
        }
        val invocation = json.decodeFromString<WorkerInvocationManifest>(Files.readString(manifest))
        validateManifest(request, profile, invocation, Instant.now())
        outputDir.createDirectories()

        val command = command(request, profile, invocation, inputDir, outputDir)
        val process = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()

        val accepted = RunnerJobResponse(request.jobId, request.attemptId, "RUNNING")
        val prior = states.putIfAbsent(key, accepted)
        if (prior != null) {
            process.destroyForcibly()
            return prior
        }

        val monitor = scope.launch(Dispatchers.IO) {
            captureBounded(process.inputStream, outputDir.resolve("stdout.log"))
        }
        val stderrMonitor = scope.launch(Dispatchers.IO) {
            captureBounded(process.errorStream, outputDir.resolve("stderr.log"))
        }
        val completion = scope.launch(Dispatchers.IO) {
            val deadline = Instant.ofEpochMilli(invocation.deadline.epochMillis)
            val deadlineSeconds = java.time.Duration.between(Instant.now(), deadline)
                .seconds
                .coerceAtLeast(1)
            val limit = deadlineSeconds.coerceAtMost(profile.maxRuntimeSeconds)
            val finished = process.waitFor(limit, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
            monitor.join()
            stderrMonitor.join()
            val exitCode = if (process.isAlive) null else process.exitValue()
            val status = when {
                !finished -> "TIMED_OUT"
                exitCode == 0 -> "SUCCEEDED"
                else -> "FAILED"
            }
            states[key] = RunnerJobResponse(
                jobId = request.jobId,
                attemptId = request.attemptId,
                status = status,
                exitCode = exitCode,
                diagnostic = if (finished) null else "Worker exceeded its declared deadline",
            )
            running.remove(key)
            writeCompletion(outputDir, states.getValue(key))
        }
        running[key] = Running(request, process, completion)
        return accepted
    }

    fun status(jobId: String, attemptId: String): RunnerJobResponse? =
        states[key(jobId, attemptId)]

    suspend fun cancel(jobId: String, attemptId: String): RunnerJobResponse? {
        val key = key(jobId, attemptId)
        val active = running[key] ?: return states[key]
        active.process.destroy()
        var checksRemaining = 20
        while (active.process.isAlive && checksRemaining > 0) {
            delay(250)
            checksRemaining -= 1
        }
        if (active.process.isAlive) active.process.destroyForcibly()
        val cancelled = RunnerJobResponse(jobId, attemptId, "CANCELLED")
        states[key] = cancelled
        running.remove(key)
        return cancelled
    }

    private fun validate(request: LaunchWorkerRequest) {
        require(SAFE_ID.matches(request.jobId)) { "Invalid jobId" }
        require(SAFE_ID.matches(request.attemptId)) { "Invalid attemptId" }
        require(request.capabilityId in config.profiles) { "Unknown capabilityId" }
    }

    private fun resolveAttemptRoot(request: LaunchWorkerRequest): Path {
        val realJobsRoot = config.jobsRoot.toRealPath()
        val resolved = config.jobsRoot.resolve(request.jobId).resolve(request.attemptId)
            .toAbsolutePath()
            .normalize()
        require(resolved.startsWith(config.jobsRoot)) { "Attempt path escaped jobs root" }
        val realAttemptRoot = resolved.toRealPath()
        require(realAttemptRoot.startsWith(realJobsRoot)) { "Attempt symlink escaped jobs root" }
        return realAttemptRoot
    }

    private fun command(
        request: LaunchWorkerRequest,
        profile: WorkerProfile,
        manifest: WorkerInvocationManifest,
        inputDir: Path,
        outputDir: Path,
    ): List<String> = buildList {
        add(config.podmanBinary)
        add("run")
        add("--rm")
        add("--name=marketlab-${request.jobId}-${request.attemptId}")
        add("--network=none")
        add("--read-only")
        add("--cap-drop=all")
        add("--security-opt=no-new-privileges")
        add("--userns=keep-id:uid=10001,gid=10001")
        add("--pids-limit=${profile.pidsLimit}")
        add("--cpus=${manifest.resourceClass.cpuCount}")
        add("--memory=${manifest.resourceClass.memoryMiB}m")
        add("--memory-swap=${manifest.resourceClass.memoryMiB}m")
        add("--ulimit=nofile=4096:4096")
        add("--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=${profile.tmpfsBytes}")
        add("--mount=type=bind,src=$inputDir,dst=/work/input,ro=true,relabel=private")
        add("--mount=type=bind,src=$outputDir,dst=/work/output,rw=true,relabel=private")
        if (manifest.resourceClass.gpuCount == 1) add("--device=nvidia.com/gpu=0")
        add(profile.image)
        add("worker")
        add("--manifest")
        add("/work/input/manifest.json")
        add("--output")
        add("/work/output")
    }

    private fun captureBounded(input: java.io.InputStream, destination: Path) {
        val maximum = 8L * 1024 * 1024
        Files.newOutputStream(
            destination,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            val buffer = ByteArray(8192)
            var written = 0L
            while (written < maximum) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maximum - written).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
            }
        }
        input.close()
    }

    private fun writeCompletion(outputDir: Path, response: RunnerJobResponse) {
        val payload = buildString {
            append("completedAt=")
            append(Instant.now())
            append('\n')
            append("status=")
            append(response.status)
            append('\n')
            append("exitCode=")
            append(response.exitCode ?: "")
            append('\n')
        }
        Files.writeString(
            outputDir.resolve("runner-completion.properties"),
            payload,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }

    private fun key(jobId: String, attemptId: String): String = "$jobId/$attemptId"
}

internal fun validateManifest(
    request: LaunchWorkerRequest,
    profile: WorkerProfile,
    manifest: WorkerInvocationManifest,
    now: Instant,
) {
    require(manifest.jobId.value == request.jobId) { "manifest jobId differs from launch request" }
    require(manifest.attemptId.value == request.attemptId) { "manifest attemptId differs from launch request" }
    require(manifest.capabilityId.value == request.capabilityId) {
        "manifest capability differs from launch request"
    }
    val configuredDigest = profile.image.substringAfterLast("@sha256:", missingDelimiterValue = "")
    require(configuredDigest.length == 64 && manifest.expectedImageDigest.hex == configuredDigest) {
        "manifest image digest differs from the configured capability"
    }
    require(Instant.ofEpochMilli(manifest.deadline.epochMillis).isAfter(now)) {
        "manifest deadline has expired"
    }
    require(manifest.resourceClass.cpuCount <= profile.cpuCount) {
        "manifest CPU request exceeds the capability limit"
    }
    require(manifest.resourceClass.memoryMiB <= profile.memoryMiB) {
        "manifest memory request exceeds the capability limit"
    }
    require(manifest.resourceClass.gpuCount <= if (profile.gpu) 1 else 0) {
        "manifest GPU request exceeds the capability limit"
    }
    require(manifest.outputConstraints.outputDirectory == "/work/output") {
        "manifest output must target the isolated output mount"
    }
}
