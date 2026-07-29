package dev.marketlab.data.quality

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.market.BookLevel
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.data.hyperliquid.HyperliquidFunding
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HyperliquidQualityTest {
    @Test
    fun `empty candle capture fails closed without fabricating observations`() {
        val report = HyperliquidQuality.candles(
            candles = emptyList(),
            checkedAt = Instant.parse("2026-07-28T00:00:00Z"),
            expectedCoin = "BTC",
            expectedInterval = "1h",
        )

        assertFalse(report.usable)
        assertTrue(report.issues.any { it.kind == QualityIssueKind.INSUFFICIENT_COVERAGE })
    }

    @Test
    fun `observed book fails closed when real receive lag exceeds the requirement`() {
        // Exact BTC top-of-book values and clocks observed on Hyperliquid
        // mainnet at exchange timestamp 1785210252158.
        val book =
            L2Book(
                header =
                    EventHeader(
                        id = MarketEventId("hyperliquid:BTC:1785210252158"),
                        source = DataSourceId("hyperliquid-mainnet"),
                        instrument = InstrumentId("hyperliquid:perpetual:BTC"),
                        exchangeTime = MarketTimestamp(1785210252158),
                        receivedAt = MarketTimestamp(1785210252170),
                        availableAt = MarketTimestamp(1785210252170),
                    ),
                bids =
                    listOf(
                        BookLevel(
                            DecimalValue.of("63353"),
                            DecimalValue.of("0.00049"),
                            1,
                        ),
                    ),
                asks =
                    listOf(
                        BookLevel(
                            DecimalValue.of("63354"),
                            DecimalValue.of("18.51384"),
                            85,
                        ),
                    ),
            )

        val report =
            HyperliquidQuality.availability(
                events = listOf(book),
                checkedAt = Instant.ofEpochMilli(1785210252170),
                maximumLagMillis = 10L,
            )

        assertFalse(report.usable)
        assertTrue(report.issues.any { it.kind == QualityIssueKind.STALE_DATA })
    }

    @Test
    fun `duplicate real funding timestamp is never silently deduplicated`() {
        // Exact Hyperliquid mainnet BTC funding observation at 1777428000000.
        val observation =
            HyperliquidFunding(
                coin = "BTC",
                time = Instant.ofEpochMilli(1777428000000),
                fundingRate = BigDecimal("0.0000009372"),
                premium = BigDecimal("-0.0004925028"),
            )

        val report =
            HyperliquidQuality.funding(
                rows = listOf(observation, observation),
                checkedAt = Instant.parse("2026-04-29T03:00:00Z"),
                expectedCoin = "BTC",
            )

        assertFalse(report.usable)
        assertTrue(report.issues.any { it.kind == QualityIssueKind.DUPLICATE })
    }
}
