package dev.marketlab.engine

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.market.BookLevel
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.paper.OrderSide
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingEvaluationTest {
    @Test
    fun `costed directional evaluation uses observed Hyperliquid returns`() {
        // Same exact BTC daily-close sample as WalkForwardExperimentTest.
        val closes = listOf(64_827.0, 64_718.0, 65_226.0, 66_527.0, 66_086.0, 65_069.0)
        val returns = closes.zipWithNext { previous, next -> ln(next / previous) }
        val laggedObservedReturns = listOf(0.0) + returns.dropLast(1)

        val result = TradingEvaluation.directional(
            realizedLogReturns = returns,
            forecasts = laggedObservedReturns,
            oneWayCostRate = 0.00045,
            periodsPerYear = 365.0,
        )

        assertTrue(result.netLogReturn <= result.grossLogReturn)
        assertTrue(result.totalCosts >= 0.0)
        assertTrue(result.maximumDrawdown in 0.0..1.0)
    }

    @Test
    fun `capacity reports only quantity visible in the real book`() {
        val small = BookCapacityAnalyzer.sweep(
            book = observedBook,
            side = OrderSide.BUY,
            notional = DecimalValue.of("10000"),
            maximumDisplayedDepthFraction = DecimalValue.of("0.10"),
        )
        val large = BookCapacityAnalyzer.sweep(
            book = observedBook,
            side = OrderSide.BUY,
            notional = DecimalValue.of("1000000"),
            maximumDisplayedDepthFraction = DecimalValue.of("0.10"),
        )

        assertTrue(small.complete)
        assertFalse(large.complete)
        assertTrue(requireNotNull(small.impactBasisPoints) >= 0.0)
    }

    // Exact Hyperliquid BTC levels observed at exchange time 1785210252158.
    private val observedBook = L2Book(
        header = EventHeader(
            id = MarketEventId("hyperliquid:BTC:1785210252158"),
            source = DataSourceId("hyperliquid-mainnet"),
            instrument = InstrumentId("hyperliquid:perpetual:BTC"),
            exchangeTime = MarketTimestamp(1785210252158),
            receivedAt = MarketTimestamp(1785210252170),
            availableAt = MarketTimestamp(1785210252170),
        ),
        bids = listOf(
            BookLevel(DecimalValue.of("63353"), DecimalValue.of("0.00049"), 1),
            BookLevel(DecimalValue.of("63352"), DecimalValue.of("0.00017"), 1),
            BookLevel(DecimalValue.of("63350"), DecimalValue.of("0.79028"), 2),
        ),
        asks = listOf(
            BookLevel(DecimalValue.of("63354"), DecimalValue.of("18.51384"), 85),
            BookLevel(DecimalValue.of("63355"), DecimalValue.of("1.30463"), 13),
            BookLevel(DecimalValue.of("63356"), DecimalValue.of("1.39212"), 5),
        ),
    )
}
