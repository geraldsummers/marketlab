package dev.marketlab.engine

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.OrderId
import dev.marketlab.contracts.PaperSessionId
import dev.marketlab.contracts.paper.OrderSide
import dev.marketlab.contracts.paper.PaperLedgerEntry
import dev.marketlab.contracts.paper.PaperOrder
import dev.marketlab.contracts.paper.PaperOrderStatus
import dev.marketlab.contracts.paper.PaperPosition
import dev.marketlab.contracts.paper.PaperSessionStatus
import java.math.BigDecimal
import java.math.MathContext

data class PaperPortfolioState(
    val sessionId: PaperSessionId,
    val status: PaperSessionStatus,
    val nextSequence: Long,
    val orders: Map<OrderId, PaperOrder>,
    val positions: Map<InstrumentId, PaperPosition>,
) {
    companion object {
        fun empty(sessionId: PaperSessionId): PaperPortfolioState = PaperPortfolioState(
            sessionId = sessionId,
            status = PaperSessionStatus.CREATED,
            nextSequence = 0,
            orders = emptyMap(),
            positions = emptyMap(),
        )
    }
}

/**
 * Deterministic projection of the append-only paper ledger. Rebuilding from the
 * same entries must produce the same orders and positions byte-for-byte.
 */
object PaperLedgerProjector {
    fun replay(
        sessionId: PaperSessionId,
        entries: Iterable<PaperLedgerEntry>,
    ): PaperPortfolioState = entries.fold(PaperPortfolioState.empty(sessionId), ::apply)

    fun apply(state: PaperPortfolioState, entry: PaperLedgerEntry): PaperPortfolioState {
        require(entry.sessionId == state.sessionId) { "ledger entry belongs to another session" }
        require(entry.sequence == state.nextSequence) {
            "expected ledger sequence ${state.nextSequence}, received ${entry.sequence}"
        }

        val advanced = when (entry) {
            is PaperLedgerEntry.SessionStatusChanged -> {
                require(entry.from == state.status) { "session status transition starts from stale state" }
                state.copy(status = entry.to)
            }

            is PaperLedgerEntry.TargetPositionSet -> state

            is PaperLedgerEntry.OrderRecorded -> {
                require(entry.order.id !in state.orders) { "paper order was recorded twice" }
                require(entry.order.sessionId == state.sessionId) { "paper order belongs to another session" }
                state.copy(orders = state.orders + (entry.order.id to entry.order))
            }

            is PaperLedgerEntry.FillRecorded -> applyFill(state, entry)
            is PaperLedgerEntry.FundingApplied -> applyFunding(state, entry)
        }
        return advanced.copy(nextSequence = state.nextSequence + 1)
    }

    private fun applyFill(
        state: PaperPortfolioState,
        entry: PaperLedgerEntry.FillRecorded,
    ): PaperPortfolioState {
        val fill = entry.fill
        val order = requireNotNull(state.orders[fill.orderId]) { "fill references an unknown order" }
        require(fill.sessionId == state.sessionId) { "fill belongs to another session" }
        val fillQuantity = fill.quantity.toBigDecimal()
        val remainingBefore = order.remainingQuantity.toBigDecimal()
        require(fillQuantity <= remainingBefore) { "fill exceeds order remainder" }

        val remainingAfter = remainingBefore - fillQuantity
        val updatedOrder = order.copy(
            remainingQuantity = DecimalValue.of(remainingAfter),
            status = if (remainingAfter.signum() == 0) {
                PaperOrderStatus.FILLED
            } else {
                PaperOrderStatus.PARTIALLY_FILLED
            },
        )

        val previous = state.positions[order.instrument] ?: flatPosition(
            state.sessionId,
            order.instrument,
            entry.recordedAt,
        )
        val updatedPosition = positionAfterFill(previous, order.side, fill.price, fill.quantity, fill.fee, entry.recordedAt)
        return state.copy(
            orders = state.orders + (order.id to updatedOrder),
            positions = state.positions + (order.instrument to updatedPosition),
        )
    }

    private fun applyFunding(
        state: PaperPortfolioState,
        entry: PaperLedgerEntry.FundingApplied,
    ): PaperPortfolioState {
        val previous = state.positions[entry.instrument] ?: flatPosition(
            state.sessionId,
            entry.instrument,
            entry.recordedAt,
        )
        val updated = previous.copy(
            fundingPaid = DecimalValue.of(
                previous.fundingPaid.toBigDecimal() + entry.amount.toBigDecimal(),
            ),
            updatedAt = entry.recordedAt,
        )
        return state.copy(positions = state.positions + (entry.instrument to updated))
    }

    private fun positionAfterFill(
        previous: PaperPosition,
        side: OrderSide,
        fillPriceValue: DecimalValue,
        fillQuantityValue: DecimalValue,
        fee: DecimalValue,
        at: MarketTimestamp,
    ): PaperPosition {
        val previousQuantity = previous.quantity.toBigDecimal()
        val fillQuantity = fillQuantityValue.toBigDecimal()
            .let { if (side == OrderSide.BUY) it else it.negate() }
        val newQuantity = previousQuantity + fillQuantity
        val fillPrice = fillPriceValue.toBigDecimal()
        val previousAverage = previous.averageEntryPrice?.toBigDecimal()

        var realized = previous.realizedPnl.toBigDecimal()
        val newAverage = when {
            previousQuantity.signum() == 0 -> fillPrice
            previousQuantity.signum() == fillQuantity.signum() -> {
                val oldNotional = previousAverage!!.multiply(previousQuantity.abs(), MathContext.DECIMAL128)
                val fillNotional = fillPrice.multiply(fillQuantity.abs(), MathContext.DECIMAL128)
                oldNotional.add(fillNotional)
                    .divide(newQuantity.abs(), MathContext.DECIMAL128)
            }

            else -> {
                val closing = previousQuantity.abs().min(fillQuantity.abs())
                val direction = BigDecimal(previousQuantity.signum())
                realized = realized.add(
                    fillPrice.subtract(previousAverage!!)
                        .multiply(closing, MathContext.DECIMAL128)
                        .multiply(direction, MathContext.DECIMAL128),
                )
                when {
                    newQuantity.signum() == 0 -> null
                    newQuantity.signum() == previousQuantity.signum() -> previousAverage
                    else -> fillPrice
                }
            }
        }

        return previous.copy(
            quantity = DecimalValue.of(newQuantity),
            averageEntryPrice = newAverage?.let(DecimalValue::of),
            realizedPnl = DecimalValue.of(realized),
            feesPaid = DecimalValue.of(previous.feesPaid.toBigDecimal() + fee.toBigDecimal()),
            updatedAt = at,
        )
    }

    private fun flatPosition(
        sessionId: PaperSessionId,
        instrument: InstrumentId,
        at: MarketTimestamp,
    ): PaperPosition = PaperPosition(
        sessionId = sessionId,
        instrument = instrument,
        quantity = DecimalValue.ZERO,
        averageEntryPrice = null,
        realizedPnl = DecimalValue.ZERO,
        fundingPaid = DecimalValue.ZERO,
        feesPaid = DecimalValue.ZERO,
        updatedAt = at,
    )
}

