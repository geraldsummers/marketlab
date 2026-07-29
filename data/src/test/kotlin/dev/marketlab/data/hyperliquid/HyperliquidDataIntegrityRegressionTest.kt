package dev.marketlab.data.hyperliquid

import com.sun.net.httpserver.HttpServer
import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.contracts.market.MarkOracle
import dev.marketlab.data.quality.HyperliquidQuality
import dev.marketlab.data.storage.ContentAddressedDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class HyperliquidDataIntegrityRegressionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `funding client retains a duplicate production row for fatal quality detection`() =
        withProductionExcerpt(FUNDING_WITH_DUPLICATE_EXCERPT) { endpoint, requests ->
            val receivedAt = Instant.ofEpochMilli(1_777_435_201_000L)
            HyperliquidRestClient(
                client = HttpClient(CIO),
                endpoint = endpoint,
                clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
                closeClient = true,
            ).use { client ->
                val captures =
                    client.fundingHistory(
                        coin = "BTC",
                        startInclusive = Instant.ofEpochMilli(1_777_428_000_000L),
                        endExclusive = Instant.ofEpochMilli(1_777_435_200_000L),
                    )

                val rows = captures.single().value
                assertEquals(3, rows.size)
                assertEquals(2, rows.count { it.time.toEpochMilli() == 1_777_428_000_000L })
                assertContentEquals(FUNDING_WITH_DUPLICATE_EXCERPT, captures.single().rawBody)

                val quality =
                    HyperliquidQuality.funding(
                        rows = rows,
                        checkedAt = receivedAt,
                        expectedCoin = "BTC",
                    )
                assertFalse(quality.usable)
                assertEquals(
                    listOf(QualityIssueKind.DUPLICATE),
                    quality.issues.map { it.kind }.distinct(),
                )
                assertEquals(1, requests.get())
            }
        }

    @Test
    fun `asset context selection is preserved by the immutable replay boundary`() =
        withProductionExcerpt(ASSET_CONTEXT_EXCERPT) { endpoint, requests ->
            val receivedAt = Instant.parse("2026-07-28T04:00:00Z")
            val store = ContentAddressedDataStore(temporaryDirectory)
            HyperliquidDataIngestor.create(
                store = store,
                rest =
                    HyperliquidRestClient(
                        client = HttpClient(CIO),
                        endpoint = endpoint,
                        clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
                        closeClient = true,
                    ),
                clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
            ).use { ingestor ->
                val result =
                    ingestor.ingestAssetContexts(
                        requirement = markOracleRequirement(),
                        instruments = setOf("ATOM", "BTC"),
                    )

                val manifest = result.snapshot.objects.single()
                assertEquals(2L, manifest.rowCount)
                assertEquals(
                    "ATOM,BTC",
                    manifest.provenance.request.parameters[
                        HyperliquidDataIngestor.SELECTED_INSTRUMENTS_PARAMETER
                    ],
                )
                assertContentEquals(ASSET_CONTEXT_EXCERPT, store.readRaw(manifest.contentHash))
                assertEquals(
                    setOf("hyperliquid:perpetual:ATOM", "hyperliquid:perpetual:BTC"),
                    result.observations.map { it.header.instrument.value }.toSet(),
                )
                assertTrue(result.observations.all { it is MarkOracle })

                val replayed = StoredHyperliquidSnapshotReader(store).events(result.snapshot)
                assertEquals(
                    result.observations.sortedBy { it.header.id.value },
                    replayed,
                )
                assertTrue(replayed.all { it is MarkOracle })
                assertTrue(replayed.none { it.header.instrument.value.endsWith(":ETH") })
                assertEquals(1, requests.get())
            }
        }

    @Test
    fun `asset context ingestion rejects an incomplete explicit instrument scope`() =
        withProductionExcerpt(ASSET_CONTEXT_EXCERPT) { endpoint, requests ->
            val receivedAt = Instant.parse("2026-07-28T04:00:00Z")
            val store = ContentAddressedDataStore(temporaryDirectory)
            HyperliquidDataIngestor.create(
                store = store,
                rest =
                    HyperliquidRestClient(
                        client = HttpClient(CIO),
                        endpoint = endpoint,
                        clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
                        closeClient = true,
                    ),
                clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
            ).use { ingestor ->
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        ingestor.ingestAssetContexts(
                            requirement = markOracleRequirement(),
                            instruments = setOf("BTC", "DOGE"),
                        )
                    }

                assertTrue(error.message.orEmpty().contains("complete requested instrument set"))
                assertEquals(1, requests.get())
            }
        }

    private fun markOracleRequirement(): DataRequirement =
        DataRequirement(
            key = "hyperliquid-selected-mark-oracle",
            observation = ObservationKind.MARK_ORACLE,
            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
            sampling = Sampling.EventTime,
            requiredFields = listOf("markPrice", "oraclePrice"),
            minimumHistoryMillis = 1L,
        )

    private fun withProductionExcerpt(
        response: ByteArray,
        block: suspend (endpoint: String, requests: AtomicInteger) -> Unit,
    ) = runBlocking {
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.requestBody.use { it.readAllBytes() }
                    requests.incrementAndGet()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                this.executor = executor
                start()
            }
        try {
            block("http://${server.address.hostString}:${server.address.port}/", requests)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        /*
         * Exact BTC rows returned by Hyperliquid mainnet's fundingHistory endpoint
         * for [1777428000000, 1777435200000) on 2026-07-28. The first production
         * row is intentionally repeated to model an upstream duplicate without
         * inventing a market value.
         */
        val FUNDING_WITH_DUPLICATE_EXCERPT =
            """
            [{"coin":"BTC","fundingRate":"0.0000009372","premium":"-0.0004925028","time":1777428000000},{"coin":"BTC","fundingRate":"0.0000009372","premium":"-0.0004925028","time":1777428000000},{"coin":"BTC","fundingRate":"-0.0000093058","premium":"-0.0005744465","time":1777431600009}]
            """.trimIndent().toByteArray(Charsets.UTF_8)

        /*
         * Exact aligned BTC, ETH, and ATOM entries copied from a production
         * metaAndAssetCtxs response on 2026-07-28. Only the envelope is reduced
         * to those three entries so the normal suite remains small and offline.
         */
        val ASSET_CONTEXT_EXCERPT =
            """
            [{"universe":[{"szDecimals":5,"name":"BTC","maxLeverage":40,"marginTableId":56},{"szDecimals":4,"name":"ETH","maxLeverage":25,"marginTableId":55},{"szDecimals":2,"name":"ATOM","maxLeverage":5,"marginTableId":5}]},[{"funding":"0.0000125","openInterest":"36748.7346799999","prevDayPx":"65302.0","dayNtlVlm":"2307023016.123169899","premium":"-0.0003225531","oraclePx":"63245.4","markPx":"63223.0","midPx":"63224.5","impactPxs":["63224.0","63225.0"],"dayBaseVlm":"35778.4739300002"},{"funding":"0.0000105182","openInterest":"1037475.4872000002","prevDayPx":"1953.7","dayNtlVlm":"1039186575.0811302662","premium":"-0.0003194888","oraclePx":"1878.0","markPx":"1877.3","midPx":"1877.35","impactPxs":["1877.3","1877.4"],"dayBaseVlm":"537845.9202999984"},{"funding":"-0.0000397702","openInterest":"1490882.9400000002","prevDayPx":"1.3924","dayNtlVlm":"478566.1811960001","premium":"-0.0008447892","oraclePx":"1.3021","markPx":"1.3003","midPx":"1.3004","impactPxs":["1.2996","1.301"],"dayBaseVlm":"356935.1500000001"}]]
            """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
