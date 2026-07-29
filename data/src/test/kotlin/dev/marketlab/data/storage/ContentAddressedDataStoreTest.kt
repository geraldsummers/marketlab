package dev.marketlab.data.storage

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.SourceRequest
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class ContentAddressedDataStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `identical non-market provenance bytes reuse one immutable object`() {
        val bytes = """{"adapter":"test","payloadKind":"non-market-provenance"}"""
            .toByteArray(Charsets.UTF_8)
        val store = ContentAddressedDataStore(temporaryDirectory)
        val descriptor = RawObjectDescriptor(
            source = DataSourceId("test-provenance"),
            request = SourceRequest("GET", "https://example.invalid/provenance"),
            retrievedAt = Instant.parse("2026-07-28T00:00:00Z"),
            schemaVersion = "test-v1",
            adapterVersion = "test-v1",
            rowCount = 1,
            eventTimeRange = TimeRange(MarketTimestamp(1L), MarketTimestamp(2L)),
            availabilityTimeRange = TimeRange(MarketTimestamp(1L), MarketTimestamp(2L)),
            production = false,
        )

        val first = store.putRaw(bytes, descriptor)
        val second = store.putRaw(bytes, descriptor)

        assertEquals(first.contentHash, second.contentHash)
        assertContentEquals(bytes, store.readRaw(first.contentHash))
        store.verifyObject(first)
    }

    @Test
    fun `snapshot verification recomputes identity instead of trusting embedded hash`() {
        // Exact Hyperliquid mainnet BTC daily candle retrieved on 2026-07-28.
        val bytes =
            """
            [{"t":1784332800000,"T":1784419199999,"s":"BTC","i":"1d","o":"63927.0","c":"64827.0","h":"64873.0","l":"63873.0","v":"12085.92978","n":147009}]
            """.trimIndent().toByteArray(Charsets.UTF_8)
        val store = ContentAddressedDataStore(temporaryDirectory)
        val objectManifest =
            store.putRaw(
                bytes,
                RawObjectDescriptor(
                    source = DataSourceId("hyperliquid-mainnet"),
                    request =
                        SourceRequest(
                            "POST",
                            "https://api.hyperliquid.xyz/info",
                            mapOf(
                                "body" to
                                    """{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1d","startTime":1784332800000,"endTime":1784419200000}}""",
                            ),
                        ),
                    retrievedAt = Instant.parse("2026-07-28T12:00:00Z"),
                    schemaVersion = "hyperliquid-info-v1",
                    adapterVersion = "hyperliquid-rest-v1",
                    rowCount = 1,
                    eventTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_332_800_000L),
                            MarketTimestamp(1_784_419_200_000L),
                        ),
                    availabilityTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_419_200_000L),
                            MarketTimestamp(1_784_419_200_001L),
                        ),
                    production = true,
                ),
            )
        val snapshot =
            store.createSnapshot(
                createdAt = Instant.parse("2026-07-28T12:00:00Z"),
                requirements =
                    listOf(
                        DataRequirement(
                            key = "hyperliquid_candles",
                            observation = ObservationKind.CANDLE,
                            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                            sampling = Sampling.FixedDuration(86_400_000L),
                            requiredFields = listOf("close"),
                            minimumHistoryMillis = 86_400_000L,
                        ),
                    ),
                objects = listOf(objectManifest),
                quality =
                    DataQualityReport(
                        checkedAt = MarketTimestamp(1_785_283_200_000L),
                        issues = emptyList(),
                    ),
            )

        store.verifySnapshot(snapshot)

        val tampered =
            snapshot.copy(
                quality =
                    DataQualityReport(
                        checkedAt = snapshot.quality.checkedAt,
                        issues =
                            listOf(
                                dev.marketlab.contracts.data.DataQualityIssue(
                                    kind =
                                        dev.marketlab.contracts.data.QualityIssueKind
                                            .SOURCE_REVISION,
                                    severity = QualitySeverity.WARNING,
                                    message = "tampered metadata",
                                ),
                            ),
                    ),
            )
        assertFailsWith<IllegalStateException> { store.verifySnapshot(tampered) }
    }
}
