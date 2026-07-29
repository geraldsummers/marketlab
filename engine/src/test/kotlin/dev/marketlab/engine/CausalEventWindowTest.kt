package dev.marketlab.engine

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.EventHeader
import kotlin.test.Test
import kotlin.test.assertEquals

class CausalEventWindowTest {
    // Real Hyperliquid BTC 1h candles observed from mainnet on 2026-07-28.
    private val source = DataSourceId("hyperliquid-mainnet")
    private val instrument = InstrumentId("hyperliquid:perp:BTC")

    @Test
    fun `withholds real observations unavailable at decision time and open candles`() {
        val first = candle(
            id = "1785117600000",
            openTime = 1785117600000,
            closeTime = 1785121199999,
            open = "65180",
            high = "65259",
            low = "65083",
            close = "65148",
            volume = "593.26931",
            trades = 8130,
            closed = true,
        )
        val later = candle(
            id = "1785121200000",
            openTime = 1785121200000,
            closeTime = 1785124799999,
            open = "65148",
            high = "65342",
            low = "65143",
            close = "65261",
            volume = "318.24114",
            trades = 6956,
            closed = true,
        )
        val open = candle(
            id = "1785204000000",
            openTime = 1785204000000,
            closeTime = 1785207599999,
            open = "63200",
            high = "63278",
            low = "63069",
            close = "63160",
            volume = "820.91197",
            trades = 10219,
            closed = false,
        )

        val window = CausalEventWindow.create(
            sequenceOf(first, later, open),
            MarketTimestamp(1785121199999),
        )

        assertEquals(listOf(first), window.toList())
    }

    private fun candle(
        id: String,
        openTime: Long,
        closeTime: Long,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: String,
        trades: Long,
        closed: Boolean,
    ): Candle = Candle(
        header = EventHeader(
            id = MarketEventId(id),
            source = source,
            instrument = instrument,
            exchangeTime = MarketTimestamp(openTime),
            receivedAt = MarketTimestamp(1785207600000),
            availableAt = MarketTimestamp(closeTime),
        ),
        intervalMillis = 3_600_000,
        open = DecimalValue.of(open),
        high = DecimalValue.of(high),
        low = DecimalValue.of(low),
        close = DecimalValue.of(close),
        baseVolume = DecimalValue.of(volume),
        tradeCount = trades,
        closed = closed,
    )
}
