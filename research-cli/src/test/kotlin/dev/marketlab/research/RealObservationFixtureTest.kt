package dev.marketlab.research

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.market.Funding
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

class RealObservationFixtureTest {
    @Test
    fun `real funding is aligned only with the subsequent completed candle`() {
        val rows = FundingReturnDataset.align(
            coin = "BTC",
            startInclusive = java.time.Instant.ofEpochMilli(FUNDING_BUCKET),
            endExclusive = java.time.Instant.ofEpochMilli(FUNDING_BUCKET + HOUR),
            funding = listOf(funding),
            candles = listOf(candle),
        )

        val row = rows.single()
        assertEquals("BTC:1785117600000", row.rowId)
        assertEquals(1785121200000L, row.decisionTime.epochMillis)
        assertEquals(0.0000109297, row.features.values.getValue("funding_rate"))
        assertEquals(ln(65_261.0 / 65_148.0), row.label, absoluteTolerance = 1e-15)
        assertTrue(funding.header.availableAt < row.decisionTime)
    }

    @Test
    fun `a missing real observation fails closed instead of creating a row`() {
        assertFailsWith<IllegalStateException> {
            FundingReturnDataset.align(
                coin = "BTC",
                startInclusive = java.time.Instant.ofEpochMilli(FUNDING_BUCKET),
                endExclusive = java.time.Instant.ofEpochMilli(FUNDING_BUCKET + HOUR),
                funding = listOf(funding),
                candles = emptyList(),
            )
        }
    }

    @Test
    fun `canonical output and digest are stable for exact real observations`() {
        val fixture = WireFactFixture(
            bookAsks = listOf("63354@18.51384", "63355@1.30463", "63356@1.39212"),
            bookBids = listOf("63353@0.00049", "63352@0.00017", "63350@0.79028"),
            candle = CandleFact(
                close = "65261",
                high = "65342",
                low = "65143",
                open = "65148",
                openTimeEpochMillis = 1785121200000,
                tradeCount = 6956,
                volume = "318.24114",
            ),
            funding = FundingFact(
                fundingRate = "0.0000109297",
                premium = "-0.0004125622",
                timeEpochMillis = 1785117600111,
            ),
        )

        val bytes = CanonicalReportJson.encode(WireFactFixture.serializer(), fixture)
        val text = bytes.toString(Charsets.UTF_8)

        assertEquals(
            """{"bookAsks":["63354@18.51384","63355@1.30463","63356@1.39212"],""" +
                """"bookBids":["63353@0.00049","63352@0.00017","63350@0.79028"],""" +
                """"candle":{"close":"65261","high":"65342","low":"65143","open":"65148",""" +
                """"openTimeEpochMillis":1785121200000,"tradeCount":6956,"volume":"318.24114"},""" +
                """"funding":{"fundingRate":"0.0000109297","premium":"-0.0004125622",""" +
                """"timeEpochMillis":1785117600111}}""",
            text,
        )
        assertEquals(EXPECTED_FIXTURE_SHA256, CanonicalReportJson.sha256(bytes))
    }

    /*
     * Exact Hyperliquid mainnet facts:
     * - fundingHistory BTC row at 1785117600111;
     * - BTC 1h candle opening 1785121200000;
     * - L2 levels observed at exchange time 1785210252158.
     * The normalized receivedAt below uses that real L2 capture's observed receive
     * time; it does not enter either the feature or label.
     */
    private val funding = Funding(
        header = EventHeader(
            id = MarketEventId("hl:funding:BTC:1785117600111"),
            source = DataSourceId("hyperliquid-mainnet"),
            instrument = InstrumentId("hyperliquid:perpetual:BTC"),
            exchangeTime = MarketTimestamp(1785117600111),
            receivedAt = MarketTimestamp(1785210252170),
            availableAt = MarketTimestamp(1785117600111),
        ),
        rate = DecimalValue.of("0.0000109297"),
        intervalMillis = HOUR,
        premium = DecimalValue.of("-0.0004125622"),
    )

    private val candle = Candle(
        header = EventHeader(
            id = MarketEventId("hl:candle:BTC:1h:1785121200000"),
            source = DataSourceId("hyperliquid-mainnet"),
            instrument = InstrumentId("hyperliquid:perpetual:BTC"),
            exchangeTime = MarketTimestamp(1785124799999),
            receivedAt = MarketTimestamp(1785210252170),
            availableAt = MarketTimestamp(1785124800000),
        ),
        intervalMillis = HOUR,
        open = DecimalValue.of("65148"),
        high = DecimalValue.of("65342"),
        low = DecimalValue.of("65143"),
        close = DecimalValue.of("65261"),
        baseVolume = DecimalValue.of("318.24114"),
        tradeCount = 6956,
        closed = true,
    )

    @Serializable
    private data class WireFactFixture(
        val bookAsks: List<String>,
        val bookBids: List<String>,
        val candle: CandleFact,
        val funding: FundingFact,
    )

    @Serializable
    private data class CandleFact(
        val close: String,
        val high: String,
        val low: String,
        val open: String,
        val openTimeEpochMillis: Long,
        val tradeCount: Long,
        val volume: String,
    )

    @Serializable
    private data class FundingFact(
        val fundingRate: String,
        val premium: String,
        val timeEpochMillis: Long,
    )

    private companion object {
        const val FUNDING_BUCKET = 1785117600000L
        const val HOUR = 3_600_000L
        const val EXPECTED_FIXTURE_SHA256 =
            "78f953c50a27a5e81a6f058fcf4bc1de0e983cc49b4d1ed8c06c7808b695c506"
    }
}
