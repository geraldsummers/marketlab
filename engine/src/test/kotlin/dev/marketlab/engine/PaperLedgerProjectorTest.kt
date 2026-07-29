package dev.marketlab.engine

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.FillId
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.LedgerEntryId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.OrderId
import dev.marketlab.contracts.PaperSessionId
import dev.marketlab.contracts.paper.OrderSide
import dev.marketlab.contracts.paper.PaperFill
import dev.marketlab.contracts.paper.PaperLedgerEntry
import dev.marketlab.contracts.paper.PaperLiquidity
import dev.marketlab.contracts.paper.PaperOrder
import dev.marketlab.contracts.paper.PaperOrderStatus
import dev.marketlab.contracts.paper.PaperSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaperLedgerProjectorTest {
    @Test
    fun `replay closes a position deterministically from observed prices`() {
        val first = PaperLedgerProjector.replay(sessionId, ledger)
        val second = PaperLedgerProjector.replay(sessionId, ledger)
        val position = requireNotNull(first.positions[instrument])

        assertEquals(first, second)
        assertEquals(DecimalValue.ZERO, position.quantity)
        assertNull(position.averageEntryPrice)
        assertEquals(DecimalValue.of("-0.1"), position.realizedPnl)
        assertEquals(DecimalValue.of("5.701815"), position.feesPaid)
        assertEquals(PaperSessionStatus.RUNNING, first.status)
    }

    /*
     * Prices 63354/63353 are the exact top ask/bid observed in Hyperliquid's
     * BTC book at exchange time 1785210252158. Fees use a 4.5 bps taker rate.
     */
    private val ledger: List<PaperLedgerEntry> by lazy {
        val buy = order("buy", OrderSide.BUY)
        val sell = order("sell", OrderSide.SELL)
        listOf(
            PaperLedgerEntry.SessionStatusChanged(
                id = LedgerEntryId("ledger-0"),
                sessionId = sessionId,
                sequence = 0,
                recordedAt = time,
                causedBy = null,
                from = PaperSessionStatus.CREATED,
                to = PaperSessionStatus.RUNNING,
            ),
            PaperLedgerEntry.OrderRecorded(
                id = LedgerEntryId("ledger-1"),
                sessionId = sessionId,
                sequence = 1,
                recordedAt = MarketTimestamp(time.epochMillis + 1),
                causedBy = event,
                order = buy,
            ),
            PaperLedgerEntry.FillRecorded(
                id = LedgerEntryId("ledger-2"),
                sessionId = sessionId,
                sequence = 2,
                recordedAt = MarketTimestamp(time.epochMillis + 2),
                causedBy = event,
                fill = fill(buy.id, "buy-fill", "63354", "2.85093"),
            ),
            PaperLedgerEntry.OrderRecorded(
                id = LedgerEntryId("ledger-3"),
                sessionId = sessionId,
                sequence = 3,
                recordedAt = MarketTimestamp(time.epochMillis + 3),
                causedBy = event,
                order = sell,
            ),
            PaperLedgerEntry.FillRecorded(
                id = LedgerEntryId("ledger-4"),
                sessionId = sessionId,
                sequence = 4,
                recordedAt = MarketTimestamp(time.epochMillis + 4),
                causedBy = event,
                fill = fill(sell.id, "sell-fill", "63353", "2.850885"),
            ),
        )
    }

    private fun order(id: String, side: OrderSide): PaperOrder =
        PaperOrder(
            id = OrderId(id),
            sessionId = sessionId,
            instrument = instrument,
            side = side,
            requestedQuantity = DecimalValue.of("0.1"),
            remainingQuantity = DecimalValue.of("0.1"),
            status = PaperOrderStatus.CREATED,
            decisionEventId = MarketEventId("prior-$id"),
            decisionTime = MarketTimestamp(time.epochMillis - 1_000),
            eligibleExecutionTime = time,
            createdAt = MarketTimestamp(time.epochMillis - 999),
        )

    private fun fill(orderId: OrderId, id: String, price: String, fee: String): PaperFill =
        PaperFill(
            id = FillId(id),
            orderId = orderId,
            sessionId = sessionId,
            marketEventId = event,
            time = time,
            price = DecimalValue.of(price),
            quantity = DecimalValue.of("0.1"),
            fee = DecimalValue.of(fee),
            liquidity = PaperLiquidity.TAKER,
        )

    private companion object {
        val sessionId = PaperSessionId("paper-real-book-replay")
        val instrument = InstrumentId("hyperliquid:perpetual:BTC")
        val event = MarketEventId("hyperliquid:BTC:1785210252158")
        val time = MarketTimestamp(1785210252170)
    }
}
