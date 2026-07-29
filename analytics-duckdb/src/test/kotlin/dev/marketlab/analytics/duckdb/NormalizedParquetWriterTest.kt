package dev.marketlab.analytics.duckdb

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.JobId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.market.Candle
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizedParquetWriterTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun `normalizes real candles deterministically with all three clocks`() {
        val writer = NormalizedParquetWriter(temporaryRoot.resolve("normalized"))

        val first = writer.candles(realCandles)
        val repeated = writer.candles(realCandles)

        assertEquals(first.path, repeated.path)
        assertEquals(first.contentHash, repeated.contentHash)
        DuckDbJob.open(
            DuckDbJobConfig(
                workspaceRoot = temporaryRoot.resolve("analytics"),
                allowedInputRoots = listOf(temporaryRoot.resolve("normalized")),
            ),
            JobId("normalize-test"),
        ).use { job ->
            job.registerParquetView("candles", listOf(first.path))
            val result = job.query(
                """
                SELECT event_id, close, exchange_time_ms, received_at_ms, available_at_ms
                FROM candles
                ORDER BY exchange_time_ms, event_id
                """.trimIndent(),
            )
            assertEquals(2, result.rows.size)
            assertEquals(DuckDbValue.Decimal(DecimalValue.of("65148")), result.rows[0][1])
            assertEquals(DuckDbValue.Integral("1785121199999"), result.rows[0][2])
            assertEquals(DuckDbValue.Integral("1785211200000"), result.rows[0][3])
            assertEquals(DuckDbValue.Integral("1785121200000"), result.rows[0][4])
        }
    }

    /*
     * Exact closed Hyperliquid BTC hourly candles returned on 2026-07-28.
     * Historical receipt time is later; semantic availability is close+1 ms.
     */
    private val realCandles = listOf(
        candle(
            id = "hl:candle:BTC:1h:1785117600000",
            exchangeTime = 1785121199999,
            availableAt = 1785121200000,
            open = "65180",
            high = "65259",
            low = "65083",
            close = "65148",
            volume = "593.26931",
            trades = 8130,
        ),
        candle(
            id = "hl:candle:BTC:1h:1785121200000",
            exchangeTime = 1785124799999,
            availableAt = 1785124800000,
            open = "65148",
            high = "65342",
            low = "65143",
            close = "65261",
            volume = "318.24114",
            trades = 6956,
        ),
    )

    private fun candle(
        id: String,
        exchangeTime: Long,
        availableAt: Long,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: String,
        trades: Long,
    ): Candle =
        Candle(
            header = dev.marketlab.contracts.market.EventHeader(
                id = MarketEventId(id),
                source = DataSourceId("hyperliquid-mainnet"),
                instrument = InstrumentId("hyperliquid:perpetual:BTC"),
                exchangeTime = MarketTimestamp(exchangeTime),
                receivedAt = MarketTimestamp(1785211200000),
                availableAt = MarketTimestamp(availableAt),
            ),
            intervalMillis = 3_600_000,
            open = DecimalValue.of(open),
            high = DecimalValue.of(high),
            low = DecimalValue.of(low),
            close = DecimalValue.of(close),
            baseVolume = DecimalValue.of(volume),
            tradeCount = trades,
            closed = true,
        )
}
