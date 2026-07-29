package dev.marketlab.engine

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.OrderId
import dev.marketlab.contracts.PaperSessionId
import dev.marketlab.contracts.market.BookLevel
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.paper.OrderSide
import dev.marketlab.contracts.paper.PaperOrder
import dev.marketlab.contracts.paper.PaperOrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservedBookExecutorTest {
    // Exact top-three BTC levels returned by Hyperliquid mainnet at
    // exchange timestamp 1785210252158.
    private val instrument = InstrumentId("hyperliquid:perp:BTC")
    private val book = L2Book(
        header = EventHeader(
            id = MarketEventId("hyperliquid:BTC:1785210252158"),
            source = DataSourceId("hyperliquid-mainnet"),
            instrument = instrument,
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

    @Test
    fun `walks only observed ask levels after the decision`() {
        val order = PaperOrder(
            id = OrderId("order-1"),
            sessionId = PaperSessionId("paper-1"),
            instrument = instrument,
            side = OrderSide.BUY,
            requestedQuantity = DecimalValue.of("19"),
            remainingQuantity = DecimalValue.of("19"),
            status = PaperOrderStatus.CREATED,
            decisionEventId = MarketEventId("prior-real-event"),
            decisionTime = MarketTimestamp(1785210252000),
            eligibleExecutionTime = MarketTimestamp(1785210252100),
            createdAt = MarketTimestamp(1785210252001),
        )
        val executor = ObservedBookExecutor(
            BookExecutionProfile(
                takerFeeRate = DecimalValue.of("0.00045"),
                maximumDisplayedDepthFraction = DecimalValue.of("1"),
            ),
        )

        val fills = executor.execute(order, book)

        assertEquals(2, fills.size)
        assertEquals(DecimalValue.of("18.51384"), fills[0].quantity)
        assertEquals(DecimalValue.of("0.48616"), fills[1].quantity)
        assertEquals(DecimalValue.of("63355"), fills[1].price)
    }

    @Test
    fun `participation limit applies independently to every observed level`() {
        val order = PaperOrder(
            id = OrderId("order-participation"),
            sessionId = PaperSessionId("paper-1"),
            instrument = instrument,
            side = OrderSide.BUY,
            requestedQuantity = DecimalValue.of("1.9"),
            remainingQuantity = DecimalValue.of("1.9"),
            status = PaperOrderStatus.CREATED,
            decisionEventId = MarketEventId("prior-real-event"),
            decisionTime = MarketTimestamp(1785210252000),
            eligibleExecutionTime = MarketTimestamp(1785210252100),
            createdAt = MarketTimestamp(1785210252001),
        )
        val executor = ObservedBookExecutor(
            BookExecutionProfile(
                takerFeeRate = DecimalValue.of("0.00045"),
                maximumDisplayedDepthFraction = DecimalValue.of("0.10"),
            ),
        )

        val fills = executor.execute(order, book)

        assertEquals(2, fills.size)
        assertEquals(DecimalValue.of("1.851384"), fills[0].quantity)
        assertEquals(DecimalValue.of("0.048616"), fills[1].quantity)
        assertEquals(DecimalValue.of("63355"), fills[1].price)
    }
}
