package dev.marketlab.data.hyperliquid

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.data.quality.HyperliquidQuality
import dev.marketlab.data.storage.ContentAddressedDataStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

@EnabledIfEnvironmentVariable(named = "MARKETLAB_MAINNET_TESTS", matches = "1")
class HyperliquidMainnetContractTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `REST wire contracts parse current production mainnet responses`() = runBlocking {
        HyperliquidRestClient.mainnet().use { client ->
            val metadata = client.metaAndAssetContexts()
            assertTrue(metadata.value.assets.isNotEmpty())
            assertEquals(metadata.value.assets.size, metadata.value.contexts.size)

            val book = client.l2Book("BTC")
            assertTrue(HyperliquidQuality.l2Book(book.value, book.receivedAt).usable)

            val currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS)
            val pages = client.candles(
                coin = "BTC",
                interval = "1h",
                startInclusive = currentHour.minus(4, ChronoUnit.HOURS),
                endExclusive = currentHour.minus(1, ChronoUnit.HOURS),
            )
            val candles = pages.flatMap { it.value }
            assertTrue(candles.isNotEmpty())
            assertTrue(candles.all { it.closeTime.isBefore(pages.last().receivedAt) })
            assertTrue(
                HyperliquidQuality.candles(
                    candles,
                    pages.last().receivedAt,
                    "BTC",
                    "1h",
                ).usable,
            )

            val fundingPages = client.fundingHistory(
                coin = "BTC",
                startInclusive = currentHour.minus(6, ChronoUnit.HOURS),
                endExclusive = currentHour,
            )
            val funding = fundingPages.flatMap { it.value }
            assertTrue(funding.isNotEmpty())
            assertTrue(
                HyperliquidQuality.funding(
                    funding,
                    fundingPages.last().receivedAt,
                    "BTC",
                ).usable,
            )
            val fundingEvents = fundingPages.flatMap(HyperliquidEventAdapter::historicalFunding)
            assertTrue(
                fundingEvents.all {
                    it.header.availableAt == it.header.exchangeTime &&
                        it.header.receivedAt > it.header.availableAt
                },
            )

            val unfinished = client.candles(
                coin = "BTC",
                interval = "1h",
                startInclusive = currentHour,
                endExclusive = currentHour.plus(1, ChronoUnit.HOURS),
            )
            assertTrue(unfinished.flatMap { it.value }.isEmpty())
        }
    }

    @Test
    fun `real closed candles produce a content-addressed production snapshot`() = runBlocking {
        val currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val requirement = DataRequirement(
            key = "hyperliquid-btc-hourly",
            observation = ObservationKind.CANDLE,
            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
            sampling = Sampling.FixedDuration(3_600_000L),
            requiredFields = listOf("open", "high", "low", "close", "baseVolume", "tradeCount"),
            minimumHistoryMillis = 7_200_000L,
        )
        HyperliquidDataIngestor.mainnet(
            store = ContentAddressedDataStore(temporaryDirectory),
            clock = Clock.systemUTC(),
        ).use { ingestor ->
            val universe = ingestor.ingestInstrumentUniverse()
            assertTrue(universe.manifest.provenance.production)
            assertTrue(universe.instruments.any { it.instrument.symbol == "BTC" })
            val result = ingestor.ingestCandles(
                coin = "BTC",
                interval = "1h",
                startInclusive = currentHour.minus(4, ChronoUnit.HOURS),
                endExclusive = currentHour.minus(1, ChronoUnit.HOURS),
                requirement = requirement,
            )
            assertTrue(result.snapshot.quality.usable)
            assertTrue(result.snapshot.objects.all { it.provenance.production })
            assertTrue(result.observations.isNotEmpty())
            assertTrue(
                result.observations.all {
                    it.header.availableAt.epochMillis == it.header.exchangeTime.epochMillis + 1L &&
                        it.header.receivedAt > it.header.availableAt
                },
            )
            assertTrue(
                result.snapshot.objects.all {
                    it.availabilityTimeRange.toExclusive <= it.provenance.retrievedAt
                },
            )

            val contextRequirement =
                DataRequirement(
                    key = "hyperliquid-btc-mark-oracle",
                    observation = ObservationKind.MARK_ORACLE,
                    sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                    instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                    sampling = Sampling.EventTime,
                    requiredFields = listOf("markPrice", "oraclePrice"),
                    minimumHistoryMillis = 1L,
                    maximumAvailabilityLagMillis = 60_000L,
                )
            val contexts =
                ingestor.ingestAssetContexts(
                    requirement = contextRequirement,
                    instruments = setOf("BTC"),
                )
            assertEquals(1L, contexts.snapshot.objects.single().rowCount)
            assertEquals(
                "BTC",
                contexts.snapshot.objects
                    .single()
                    .provenance.request.parameters[
                        HyperliquidDataIngestor.SELECTED_INSTRUMENTS_PARAMETER
                    ],
            )
            assertTrue(
                contexts.observations.all {
                    it.header.instrument.value == "hyperliquid:perpetual:BTC"
                },
            )
            assertTrue(
                StoredHyperliquidSnapshotReader(ContentAddressedDataStore(temporaryDirectory))
                    .events(contexts.snapshot)
                    .all { it.header.instrument.value == "hyperliquid:perpetual:BTC" },
            )
        }
    }

    @Test
    fun `WebSocket collector yields a real mainnet L2 observation`() = runBlocking {
        HyperliquidWebSocketCollector.mainnet().use { collector ->
            val observation = withTimeout(30_000L) {
                collector.stream(HyperliquidStreamSubscription.L2Book("BTC"))
                    .filterIsInstance<HyperliquidStreamItem.Observation>()
                    .first()
            }
            assertTrue(observation.rawBody.isNotEmpty())
            assertEquals(DataSourceId("hyperliquid-mainnet"), observation.event.header.source)
        }
    }
}
