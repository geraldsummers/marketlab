package dev.marketlab.backfill

import dev.marketlab.evidence.ImmutableEvidenceStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class FunctionalFeatureMaterializerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `complete panel retains zero activity and uses only prior social buckets`() {
        val root = directory.resolve("evidence")
        Files.createDirectories(root)
        val store = ImmutableEvidenceStore(root)
        val start = Instant.parse("2025-01-01T00:00:00Z")
        val end = Instant.parse("2025-02-03T00:00:00Z")
        val programLock = directory.resolve("program.json")
        Files.writeString(programLock, programLock(start, end))
        val featureLock = directory.resolve("features.json")
        Files.writeString(featureLock, "{}")

        val bars =
            (0 until 33 * 96).map { offset ->
                val openTime = start.toEpochMilli() + offset * QUARTER_HOUR
                MarketBar15m(
                    symbol = "BTC",
                    openTimeEpochMillis = openTime,
                    closeTimeExclusiveEpochMillis = openTime + QUARTER_HOUR,
                    open = 100.0,
                    close = 100.0 * exp(0.0001),
                    realizedVariance1m = 1.0e-8,
                    minuteCount = 15,
                )
            }
        val marketObject = store.storeObject(jsonLines(bars), "jsonl")
        val marketManifest =
            MarketAssetManifest(
                symbol = "BTC",
                archiveObjects = emptyList(),
                outputUri = marketObject.uri,
                outputSha256 = marketObject.sha256,
                barCount = bars.size,
                firstOpenTimeEpochMillis = bars.first().openTimeEpochMillis,
                lastCloseTimeExclusiveEpochMillis = bars.last().closeTimeExclusiveEpochMillis,
                completedAtEpochMillis = end.toEpochMilli(),
            )
        store.publish(JSON.encodeToString(marketManifest).toByteArray(), root.resolve("market/manifests/BTC.json"))

        val eventTime = Instant.parse("2025-01-30T23:55:00Z")
        generateSequence(LocalDate.parse("2025-01-01")) { it.plusDays(1).takeIf { next -> next < LocalDate.parse("2025-02-03") } }
            .forEach { date ->
                val observations =
                    if (date == LocalDate.parse("2025-01-30")) {
                        listOf(
                            SocialObservation(
                                symbol = "BTC",
                                sourceEventId = "at://event/1",
                                authorIdSha256 = "a".repeat(64),
                                createdAtEpochMillis = eventTime.toEpochMilli(),
                                indexedAtAvailabilityProxyEpochMillis = eventTime.toEpochMilli(),
                                retrievedAtEpochMillis = end.toEpochMilli(),
                                textSha256 = "b".repeat(64),
                                language = "en",
                                negativeProbability = 0.1,
                                neutralProbability = 0.2,
                                positiveProbability = 0.7,
                                polarity = 0.6,
                                rawResponseSha256 = "c".repeat(64),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                val output = store.storeObject(jsonLines(observations), "jsonl")
                val manifest =
                    SocialDayManifest(
                        symbol = "BTC",
                        date = date.toString(),
                        query = "bitcoin",
                        responseObjects = emptyList(),
                        outputUri = output.uri,
                        outputSha256 = output.sha256,
                        observationCount = observations.size,
                        completedAtEpochMillis = end.toEpochMilli(),
                    )
                store.publish(JSON.encodeToString(manifest).toByteArray(), root.resolve("social/manifests/BTC/$date.json"))
            }

        FunctionalFeatureMaterializer(
            FunctionalFeatureConfig(root, programLock, featureLock, start, end),
            store,
        ).run()

        val manifest = JSON.decodeFromString<FunctionalFeatureManifest>(Files.readString(root.resolve("functional/features/manifest.json")))
        assertEquals(288, manifest.rowCount)
        val rows =
            Files.readString(root.resolve(manifest.outputUri)).lineSequence().filter(String::isNotBlank)
                .map { JSON.decodeFromString<FunctionalFeatureRow>(it) }.toList()
        val first = rows.first()
        assertEquals(1.0, first.features["post_count_15m"])
        assertEquals(1.0, first.features["has_posts_15m"])
        assertNotNull(first.next15mReturn)
        val quiet = rows.first { it.decisionTimeEpochMillis == first.decisionTimeEpochMillis + QUARTER_HOUR }
        assertEquals(0.0, quiet.features["post_count_15m"])
        assertEquals(0.0, quiet.features["has_posts_15m"])
        assertTrue(quiet.features.getValue("post_count_1h") > 0.0)
    }

    private inline fun <reified T> jsonLines(rows: List<T>) =
        rows.joinToString("\n", postfix = "\n") { JSON.encodeToString(it) }.toByteArray()

    private fun programLock(start: Instant, end: Instant) =
        """
        {
          "schemaVersion":"marketlab.social-backfill-program-lock.v2",
          "programId":"functional-test",
          "period":{"startInclusive":"$start","endExclusive":"$end"},
          "sources":{
            "social":{"queries":{"BTC":"bitcoin"},"matchTerms":{"BTC":["bitcoin"]},"plainSymbols":[]},
            "market":{"months":["2025-01"]}
          },
          "universe":{"symbols":["BTC"]},
          "schedule":{"featureWarmupDays":30,"trainingDays":1,"developmentDays":1,"holdoutDays":1,"unusedTailDays":0}
        }
        """.trimIndent()

    private companion object {
        const val QUARTER_HOUR = 15L * 60L * 1_000L
        val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
    }
}
