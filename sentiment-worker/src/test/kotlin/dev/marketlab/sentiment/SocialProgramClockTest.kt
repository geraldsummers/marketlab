package dev.marketlab.sentiment

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SocialProgramClockTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `new program keeps legacy state and starts an isolated clock`() {
        val raw = temporary.resolve("raw").createDirectories()
        val features = temporary.resolve("features").createDirectories()
        val models = temporary.resolve("models").createDirectories()
        val universe = temporary.resolve("universe").createDirectories()
        val lock = temporary.resolve("social-program.lock.json")
        val modelLock = temporary.resolve("model.lock.json").also { it.writeText("{}") }
        lock.writeText(
            """
            {
              "schemaVersion":"marketlab.social-program-lock.v1",
              "programId":"social-v2"
            }
            """.trimIndent(),
        )
        val legacy = features.resolve("program").createDirectories()
        legacy.resolve("state.json").writeText(
            """
            {
              "schemaVersion":"marketlab.social-program-state.v1",
              "programId":"social-v1",
              "programLockSha256":"${"a".repeat(64)}",
              "phase":"WAITING_FOR_REQUIRED_SOURCES",
              "requiredMarkers":{},
              "updatedAtEpochMillis":1
            }
            """.trimIndent(),
        )
        val config =
            SentimentWorkerConfig(
                rawRoot = raw,
                featureRoot = features,
                modelsRoot = models,
                modelLock = modelLock,
                universeRoot = universe,
                programLock = lock,
                sourceRevision = "b".repeat(64),
                pollInterval = Duration.ofSeconds(30),
            )
        val now = Instant.parse("2026-07-29T12:00:00Z")
        val clock = SocialProgramClock(config, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(SocialProgramPhase.WAITING_FOR_REQUIRED_SOURCES, clock.tick().phase)
        assertTrue(Files.isRegularFile(features.resolve("programs/social-v1/state.json")))
        assertTrue(Files.isRegularFile(features.resolve("programs/social-v2/state.json")))

        raw.resolve(".social-collector-ready.json").writeText("{}")
        universe.resolve(".social-universe-ready.json").writeText("{}")
        features.resolve(".sentiment-worker-ready").writeText("")
        val started = clock.tick()

        assertEquals(SocialProgramPhase.STABILIZATION, started.phase)
        assertEquals(now.toEpochMilli(), started.startedAtEpochMillis)
        assertTrue(Files.readString(legacy.resolve("state.json")).contains("\"programId\":\"social-v2\""))
    }
}
