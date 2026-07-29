package dev.marketlab.sentiment

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal enum class SocialProgramPhase {
    WAITING_FOR_REQUIRED_SOURCES,
    STABILIZATION,
    WARMUP,
    TRAINING,
    DEVELOPMENT,
    SEALED_HOLDOUT,
    CONFIRMATION_COMPLETE,
}

@Serializable
internal data class SocialProgramState(
    val schemaVersion: String = "marketlab.social-program-state.v1",
    val programId: String,
    val programLockSha256: String,
    val startedAtEpochMillis: Long?,
    val phase: SocialProgramPhase,
    val phaseStartedAtEpochMillis: Long?,
    val nextPhaseAtEpochMillis: Long?,
    val requiredMarkers: Map<String, Boolean>,
    val updatedAtEpochMillis: Long,
)

internal class SocialProgramClock(
    private val config: SentimentWorkerConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lockBytes = Files.readAllBytes(config.programLock)
    private val lockSha256 = sha256(lockBytes)
    private val lock =
        JSON.parseToJsonElement(lockBytes.decodeToString()).jsonObject
    private val programId = lock.getValue("programId").jsonPrimitive.content
    private val legacyStateRoot = config.featureRoot.resolve("program")
    private val legacyStatePath = legacyStateRoot.resolve("state.json")
    private val stateRoot = config.featureRoot.resolve("programs").resolve(programId)
    private val statePath = stateRoot.resolve("state.json")

    init {
        require(PROGRAM_ID.matches(programId)) { "social program id is not a safe path component" }
        Files.createDirectories(stateRoot)
        Files.createDirectories(legacyStateRoot)
        require(lock.getValue("schemaVersion").jsonPrimitive.content == "marketlab.social-program-lock.v1")
        migrateLegacyState()
    }

    fun tick(): SocialProgramState {
        val now = clock.instant().toEpochMilli()
        val markers =
            sortedMapOf(
                "hyperliquid-mainnet" to
                    Files.exists(config.universeRoot.resolve(".social-universe-ready.json")),
                "public-information" to
                    Files.exists(config.rawRoot.resolve(".social-collector-ready.json")),
                "sentiment-models" to
                    Files.exists(config.featureRoot.resolve(".sentiment-worker-ready")),
            )
        val prior =
            if (Files.isRegularFile(statePath)) {
                JSON.decodeFromString<SocialProgramState>(Files.readString(statePath))
                    .also {
                        require(it.programLockSha256 == lockSha256) {
                            "social program lock changed after clock state was created"
                        }
                    }
            } else {
                null
            }
        val startedAt =
            prior?.startedAtEpochMillis
                ?: now.takeIf { markers.values.all { ready -> ready } }
        val (phase, phaseStart, next) =
            phase(startedAt, now)
        val state =
            SocialProgramState(
                programId = programId,
                programLockSha256 = lockSha256,
                startedAtEpochMillis = startedAt,
                phase = phase,
                phaseStartedAtEpochMillis = phaseStart,
                nextPhaseAtEpochMillis = next,
                requiredMarkers = markers,
                updatedAtEpochMillis = now,
            )
        publish(JSON.encodeToString(state).toByteArray(Charsets.UTF_8), statePath)
        publish(JSON.encodeToString(state).toByteArray(Charsets.UTF_8), legacyStatePath)
        if (phase >= SocialProgramPhase.WARMUP) {
            val frozen = stateRoot.resolve("preregistration-frozen.json")
            if (!Files.exists(frozen)) {
                publish(lockBytes, frozen, replace = false)
            }
        }
        return state
    }

    private fun phase(startedAt: Long?, now: Long): Triple<SocialProgramPhase, Long?, Long?> {
        if (startedAt == null) {
            return Triple(SocialProgramPhase.WAITING_FOR_REQUIRED_SOURCES, null, null)
        }
        val boundaries =
            listOf(
                4L to SocialProgramPhase.STABILIZATION,
                34L to SocialProgramPhase.WARMUP,
                154L to SocialProgramPhase.TRAINING,
                210L to SocialProgramPhase.DEVELOPMENT,
                270L to SocialProgramPhase.SEALED_HOLDOUT,
            )
        val elapsed = now - startedAt
        val selected =
            boundaries.withIndex().firstOrNull { elapsed < it.value.first * DAY_MILLIS }
        if (selected == null) {
            return Triple(
                SocialProgramPhase.CONFIRMATION_COMPLETE,
                startedAt + 270L * DAY_MILLIS,
                null,
            )
        }
        val index = selected.index
        val phase = selected.value.second
        val phaseStart =
            if (index == 0) startedAt else startedAt + boundaries[index - 1].first * DAY_MILLIS
        val next = startedAt + selected.value.first * DAY_MILLIS
        return Triple(phase, phaseStart, next)
    }

    private fun publish(bytes: ByteArray, destination: Path, replace: Boolean = true) {
        val temporary = destination.resolveSibling(".${UUID.randomUUID()}.partial")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(
            temporary,
            destination,
            *if (replace) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            },
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        val PROGRAM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = false
            }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }

    private fun migrateLegacyState() {
        if (Files.exists(statePath) || !Files.isRegularFile(legacyStatePath)) return
        val prior =
            runCatching {
                JSON.decodeFromString<SocialProgramState>(Files.readString(legacyStatePath))
            }.getOrNull() ?: return
        val priorRoot = config.featureRoot.resolve("programs").resolve(prior.programId)
        require(priorRoot.normalize().parent == config.featureRoot.resolve("programs").normalize()) {
            "legacy social program id is not a safe path component"
        }
        Files.createDirectories(priorRoot)
        val priorState = priorRoot.resolve("state.json")
        if (!Files.exists(priorState)) {
            publish(Files.readAllBytes(legacyStatePath), priorState, replace = false)
        }
        val legacyFrozen = legacyStateRoot.resolve("preregistration-frozen.json")
        val priorFrozen = priorRoot.resolve("preregistration-frozen.json")
        if (Files.isRegularFile(legacyFrozen) && !Files.exists(priorFrozen)) {
            publish(Files.readAllBytes(legacyFrozen), priorFrozen, replace = false)
        }
    }
}
