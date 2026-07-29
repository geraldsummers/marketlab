package dev.marketlab.collector

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.DataQualityIssue
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.contracts.market.Bbo
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.data.hyperliquid.HyperliquidStreamItem
import dev.marketlab.data.hyperliquid.HyperliquidStreamSubscription
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class StreamSegmentStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `segment preserves exact production frame clocks and quality boundary`() {
        val store = store()
        store.append(connectionEnvelope(CONNECTED_AT))
        store.append(acknowledgementEnvelope())
        store.append(observationEnvelope())
        store.append(qualityEnvelope())
        store.close()

        val indexPath = regularFiles(temporaryDirectory.resolve("index")).single()
        val index = JSON.decodeFromString<SegmentIndexEntry>(indexPath.readText())
        val manifestPath = Path.of(URI.create(index.manifestUri))
        val manifest = JSON.decodeFromString<StreamSegmentManifest>(manifestPath.readText())
        val segmentPath = Path.of(URI.create(manifest.segmentUri))
        val records = segmentPath.readLines().map { JSON.parseToJsonElement(it).jsonObject }

        assertEquals(HYPERLIQUID_SOURCE, manifest.source)
        assertTrue(manifest.production)
        assertEquals(SOURCE_REVISION, manifest.sourceRevision)
        assertEquals(4L, manifest.recordCount)
        assertEquals(1L, manifest.observationCount)
        assertEquals(2L, manifest.distinctRawMessageCount)
        assertEquals(1L, manifest.connectionCount)
        assertEquals(1L, manifest.subscriptionAcknowledgementCount)
        assertEquals(1L, manifest.qualitySignalCount)
        assertEquals(5, records.size)
        assertEquals("segment_header", records.first().getValue("recordType").jsonPrimitive.content)
        assertEquals(
            SEGMENT_SCHEMA_VERSION,
            records.first().getValue("schemaVersion").jsonPrimitive.content,
        )

        val observation =
            records.single { it.getValue("recordType").jsonPrimitive.content == "observation" }
        assertEquals(
            CAPTURED_BBO_SHA256,
            observation.getValue("rawSha256").jsonPrimitive.content,
        )
        assertContentEquals(
            CAPTURED_BBO_FRAME,
            Base64.getDecoder().decode(
                observation.getValue("rawBodyBase64").jsonPrimitive.content,
            ),
        )
        assertEquals(
            CAPTURED_EXCHANGE_TIME.toString(),
            observation.getValue("exchangeTimeEpochMillis").jsonPrimitive.content,
        )
        assertEquals(
            RECEIVED_AT.toString(),
            observation.getValue("receivedAtEpochMillis").jsonPrimitive.content,
        )
        assertEquals(
            RECEIVED_AT.toString(),
            observation.getValue("availableAtEpochMillis").jsonPrimitive.content,
        )
        assertEquals(manifest.segmentSha256, sha256(segmentPath))
        assertEquals(index.segmentSha256, manifest.segmentSha256)
        assertEquals(index.manifestSha256, sha256(manifestPath))
        assertTrue(regularFiles(temporaryDirectory.resolve(".partial")).isEmpty())

        val acknowledgement =
            records.single {
                it.getValue("recordType").jsonPrimitive.content ==
                    "subscription_acknowledgement"
            }
        assertContentEquals(
            CAPTURED_BBO_ACKNOWLEDGEMENT,
            Base64.getDecoder().decode(
                acknowledgement.getValue("rawBodyBase64").jsonPrimitive.content,
            ),
        )
    }

    @Test
    fun `duration rotation batches records into immutable segments`() {
        val first = store()
        first.append(connectionEnvelope(CONNECTED_AT))
        first.rotateIfDue(CONNECTED_AT.plus(Duration.ofMinutes(1)))
        first.append(connectionEnvelope(CONNECTED_AT.plus(Duration.ofMinutes(1))))
        first.close()

        assertEquals(2, regularFiles(temporaryDirectory.resolve("objects")).size)
        assertEquals(2, regularFiles(temporaryDirectory.resolve("manifests")).size)
        assertEquals(2, regularFiles(temporaryDirectory.resolve("index")).size)
    }

    @Test
    fun `content addressed publication verifies identical existing bytes without overwrite`() {
        store().use { it.append(connectionEnvelope(CONNECTED_AT)) }
        val objectPath = regularFiles(temporaryDirectory.resolve("objects")).single()
        val originalBytes = Files.readAllBytes(objectPath)
        val originalModifiedAt = Files.getLastModifiedTime(objectPath)

        store().use { it.append(connectionEnvelope(CONNECTED_AT)) }

        assertEquals(1, regularFiles(temporaryDirectory.resolve("objects")).size)
        assertEquals(1, regularFiles(temporaryDirectory.resolve("manifests")).size)
        assertEquals(1, regularFiles(temporaryDirectory.resolve("index")).size)
        assertContentEquals(originalBytes, Files.readAllBytes(objectPath))
        assertEquals(originalModifiedAt, Files.getLastModifiedTime(objectPath))
    }

    @Test
    fun `one raw root has exactly one active writer`() {
        val first = store()
        try {
            val error = assertFailsWith<IllegalStateException> { store() }
            assertTrue(error.message.orEmpty().contains("another collector"))
        } finally {
            first.close()
        }
    }

    @Test
    fun `readiness is current and requires every production subscription`() {
        val readyPath = temporaryDirectory.resolve(".collector-ready.json")
        Files.writeString(readyPath, """{"stale":true}""")

        store().use { store ->
            assertFalse(Files.exists(readyPath))
            assertFailsWith<IllegalArgumentException> {
                store.markReady(setOf("trades", "bbo"), FINALIZED_AT)
            }
            store.markReady(SUBSCRIPTIONS.map { it.channel }.toSet(), FINALIZED_AT)

            val marker = JSON.decodeFromString<CollectorReadyMarker>(readyPath.readText())
            assertEquals(READY_SCHEMA_VERSION, marker.schemaVersion)
            assertEquals(HYPERLIQUID_SOURCE, marker.source)
            assertTrue(marker.production)
            assertEquals(SOURCE_REVISION, marker.sourceRevision)
            assertEquals("BTC", marker.coin)
            assertEquals(listOf("bbo", "l2Book", "trades"), marker.subscriptions)
            assertEquals(FINALIZED_AT.toEpochMilli(), marker.startedAtEpochMillis)
            assertEquals(FINALIZED_AT.toEpochMilli(), marker.readyAtEpochMillis)
            assertTrue(regularFiles(temporaryDirectory.resolve(".partial")).isEmpty())
        }
    }

    @Test
    fun `unverified raw bytes are rejected before persistence`() {
        store().use { store ->
            val envelope = observationEnvelope()
            val item = envelope.item as HyperliquidStreamItem.Observation
            val corrupted =
                envelope.copy(
                    item =
                        item.copy(
                            contentHash = Sha256Digest("0".repeat(64)),
                        ),
            )

            assertFailsWith<IllegalArgumentException> { store.append(corrupted) }
            assertFalse(regularFiles(temporaryDirectory.resolve("objects")).isNotEmpty())
        }
    }

    private fun store(): StreamSegmentStore =
        StreamSegmentStore(
            config =
                CollectorConfig(
                    rawRoot = temporaryDirectory,
                    coin = "BTC",
                    sourceRevision = SOURCE_REVISION,
                    maximumSegmentBytes = 4L * 1024L * 1024L,
                    maximumSegmentDuration = Duration.ofMinutes(1),
                    queueCapacity = 4,
                    maximumFrameBytes = 64L * 1024L,
                ),
            subscriptions = SUBSCRIPTIONS,
            clock = Clock.fixed(FINALIZED_AT, ZoneOffset.UTC),
        )

    private fun connectionEnvelope(at: Instant): StreamEnvelope =
        StreamEnvelope(
            subscription = SUBSCRIPTIONS[0],
            item = HyperliquidStreamItem.Connected(MarketTimestamp(at.toEpochMilli())),
            observedAt = at,
            connectionOrdinal = 1L,
        )

    private fun observationEnvelope(): StreamEnvelope {
        val event =
            Bbo(
                header =
                    EventHeader(
                        id =
                            MarketEventId(
                                "hl:bbo:BTC:$CAPTURED_EXCHANGE_TIME:51d46ffe00c8bb14",
                            ),
                        source = DataSourceId(HYPERLIQUID_SOURCE),
                        instrument = InstrumentId("hyperliquid:perpetual:BTC"),
                        exchangeTime = MarketTimestamp(CAPTURED_EXCHANGE_TIME),
                        receivedAt = MarketTimestamp(RECEIVED_AT),
                        availableAt = MarketTimestamp(RECEIVED_AT),
                    ),
                bidPrice = DecimalValue.of("63499.0"),
                bidQuantity = DecimalValue.of("7.49207"),
                askPrice = DecimalValue.of("63500.0"),
                askQuantity = DecimalValue.of("5.19048"),
            )
        return StreamEnvelope(
            subscription = SUBSCRIPTIONS[1],
            item =
                HyperliquidStreamItem.Observation(
                    event = event,
                    rawBody = CAPTURED_BBO_FRAME,
                    contentHash = Sha256Digest(CAPTURED_BBO_SHA256),
                    receivedAt = MarketTimestamp(RECEIVED_AT),
                ),
            observedAt = Instant.ofEpochMilli(RECEIVED_AT),
            connectionOrdinal = null,
        )
    }

    private fun acknowledgementEnvelope(): StreamEnvelope =
        StreamEnvelope(
            subscription = SUBSCRIPTIONS[1],
            item =
                HyperliquidStreamItem.SubscriptionAcknowledged(
                    at = MarketTimestamp(CONNECTED_AT.plusMillis(1).toEpochMilli()),
                    rawBody = CAPTURED_BBO_ACKNOWLEDGEMENT,
                    contentHash = Sha256Digest(CAPTURED_BBO_ACKNOWLEDGEMENT_SHA256),
                ),
            observedAt = CONNECTED_AT.plusMillis(1),
            connectionOrdinal = null,
        )

    private fun qualityEnvelope(): StreamEnvelope =
        StreamEnvelope(
            subscription = SUBSCRIPTIONS[2],
            item =
                HyperliquidStreamItem.QualitySignal(
                    DataQualityIssue(
                        kind = QualityIssueKind.SEQUENCE_GAP,
                        severity = QualitySeverity.WARNING,
                        message = "Protocol fixture reconnect boundary; continuity is unknown",
                        timeRange =
                            TimeRange(
                                MarketTimestamp(CONNECTED_AT.toEpochMilli()),
                                MarketTimestamp(CONNECTED_AT.plusMillis(2).toEpochMilli()),
                            ),
                    ),
                ),
            observedAt = CONNECTED_AT.plusMillis(2),
            connectionOrdinal = null,
        )

    private fun regularFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).sorted().toList()
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val SOURCE_REVISION =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CAPTURED_EXCHANGE_TIME = 1_785_216_157_270L
        const val RECEIVED_AT = 1_785_216_157_500L
        val CONNECTED_AT: Instant = Instant.ofEpochMilli(1_785_216_157_000L)
        val FINALIZED_AT: Instant = Instant.ofEpochMilli(1_785_216_160_000L)
        val SUBSCRIPTIONS =
            listOf(
                HyperliquidStreamSubscription.Trades("BTC"),
                HyperliquidStreamSubscription.Bbo("BTC"),
                HyperliquidStreamSubscription.L2Book("BTC"),
            )

        /*
         * Exact text frame captured from Hyperliquid mainnet BTC bbo on
         * 2026-07-28. This is a single protocol fixture, not a generated price
         * series. RECEIVE_AT is test transport metadata and is kept distinct
         * from the frame's exchange clock.
         */
        val CAPTURED_BBO_FRAME =
            """
            {"channel":"bbo","data":{"coin":"BTC","time":1785216157270,"bbo":[{"px":"63499.0","sz":"7.49207","n":17},{"px":"63500.0","sz":"5.19048","n":31}]}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
        const val CAPTURED_BBO_SHA256 =
            "1765289c624a6911c918dfe3aa5f8b0632d669d43a90c37a029286a6245775c9"
        val CAPTURED_BBO_ACKNOWLEDGEMENT =
            """
            {"channel":"subscriptionResponse","data":{"method":"subscribe","subscription":{"type":"bbo","coin":"BTC"}}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
        const val CAPTURED_BBO_ACKNOWLEDGEMENT_SHA256 =
            "c18117d2c3374dfdef056757ad999b78efc327df93752b187ef3caeb7fdb5b10"
        val JSON: Json =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }
    }
}
