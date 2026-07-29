package dev.marketlab.contracts.paper

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.FillId
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.LedgerEntryId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.OrderId
import dev.marketlab.contracts.PaperSessionId
import dev.marketlab.contracts.RunId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PaperSessionStatus {
    CREATED,
    RUNNING,
    PAUSED,
    HALTED,
    STOPPED,
}

@Serializable
enum class PaperHaltReason {
    MANUAL,
    DATA_GAP,
    STALE_DATA,
    MISSING_COSTS,
    DEPTH_PARTICIPATION,
    DRAWDOWN_LIMIT,
    EXPOSURE_LIMIT,
    INTERNAL_ERROR,
}

@Serializable
data class PaperRiskLimits(
    val initialEquity: DecimalValue,
    val maximumGrossLeverage: DecimalValue,
    val maximumInstrumentFraction: DecimalValue,
    val drawdownHaltFraction: DecimalValue,
) {
    init {
        require(initialEquity > DecimalValue.ZERO) { "initial equity must be positive" }
        require(maximumGrossLeverage > DecimalValue.ZERO) { "maximum leverage must be positive" }
        require(maximumInstrumentFraction > DecimalValue.ZERO) { "instrument fraction must be positive" }
        require(maximumInstrumentFraction <= DecimalValue.of("1")) { "instrument fraction cannot exceed one" }
        require(drawdownHaltFraction > DecimalValue.ZERO) { "drawdown fraction must be positive" }
        require(drawdownHaltFraction < DecimalValue.of("1")) { "drawdown fraction must be below one" }
    }

    companion object {
        val DEFAULT: PaperRiskLimits = PaperRiskLimits(
            initialEquity = DecimalValue.of("100000"),
            maximumGrossLeverage = DecimalValue.of("1"),
            maximumInstrumentFraction = DecimalValue.of("0.25"),
            drawdownHaltFraction = DecimalValue.of("0.10"),
        )
    }
}

@Serializable
data class PaperSession(
    val id: PaperSessionId,
    val promotedRunId: RunId,
    val status: PaperSessionStatus,
    val createdAt: MarketTimestamp,
    val updatedAt: MarketTimestamp,
    val riskLimits: PaperRiskLimits,
    val haltReason: PaperHaltReason? = null,
) {
    init {
        require(updatedAt >= createdAt) { "paper session update cannot precede creation" }
        require((status == PaperSessionStatus.HALTED) == (haltReason != null)) {
            "halted sessions require exactly one halt reason"
        }
    }
}

@Serializable
data class PaperPosition(
    val sessionId: PaperSessionId,
    val instrument: InstrumentId,
    val quantity: DecimalValue,
    val averageEntryPrice: DecimalValue?,
    val realizedPnl: DecimalValue,
    val fundingPaid: DecimalValue,
    val feesPaid: DecimalValue,
    val updatedAt: MarketTimestamp,
) {
    init {
        require(averageEntryPrice == null || averageEntryPrice > DecimalValue.ZERO) {
            "average entry price must be positive"
        }
        require(quantity != DecimalValue.ZERO || averageEntryPrice == null) {
            "flat positions cannot retain an entry price"
        }
    }
}

@Serializable
enum class OrderSide {
    BUY,
    SELL,
}

@Serializable
enum class PaperOrderStatus {
    CREATED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}

@Serializable
data class PaperOrder(
    val id: OrderId,
    val sessionId: PaperSessionId,
    val instrument: InstrumentId,
    val side: OrderSide,
    val requestedQuantity: DecimalValue,
    val remainingQuantity: DecimalValue,
    val status: PaperOrderStatus,
    val decisionEventId: MarketEventId,
    val decisionTime: MarketTimestamp,
    val eligibleExecutionTime: MarketTimestamp,
    val createdAt: MarketTimestamp,
) {
    init {
        require(requestedQuantity > DecimalValue.ZERO) { "requested quantity must be positive" }
        require(remainingQuantity >= DecimalValue.ZERO) { "remaining quantity cannot be negative" }
        require(remainingQuantity <= requestedQuantity) { "remaining quantity cannot exceed requested quantity" }
        require(eligibleExecutionTime >= decisionTime) { "execution eligibility cannot precede the decision" }
        require(createdAt >= decisionTime) { "order creation cannot precede the decision" }
    }
}

@Serializable
data class PaperFill(
    val id: FillId,
    val orderId: OrderId,
    val sessionId: PaperSessionId,
    val marketEventId: MarketEventId,
    val time: MarketTimestamp,
    val price: DecimalValue,
    val quantity: DecimalValue,
    val fee: DecimalValue,
    val liquidity: PaperLiquidity,
) {
    init {
        require(price > DecimalValue.ZERO) { "fill price must be positive" }
        require(quantity > DecimalValue.ZERO) { "fill quantity must be positive" }
        require(fee >= DecimalValue.ZERO) { "fill fee cannot be negative" }
    }
}

@Serializable
enum class PaperLiquidity {
    TAKER,
}

@Serializable
sealed interface PaperLedgerEntry {
    val id: LedgerEntryId
    val sessionId: PaperSessionId
    val sequence: Long
    val recordedAt: MarketTimestamp
    val causedBy: MarketEventId?

    @Serializable
    @SerialName("session_status")
    data class SessionStatusChanged(
        override val id: LedgerEntryId,
        override val sessionId: PaperSessionId,
        override val sequence: Long,
        override val recordedAt: MarketTimestamp,
        override val causedBy: MarketEventId?,
        val from: PaperSessionStatus,
        val to: PaperSessionStatus,
        val haltReason: PaperHaltReason? = null,
    ) : PaperLedgerEntry

    @Serializable
    @SerialName("target_position")
    data class TargetPositionSet(
        override val id: LedgerEntryId,
        override val sessionId: PaperSessionId,
        override val sequence: Long,
        override val recordedAt: MarketTimestamp,
        override val causedBy: MarketEventId,
        val instrument: InstrumentId,
        val targetQuantity: DecimalValue,
    ) : PaperLedgerEntry

    @Serializable
    @SerialName("order")
    data class OrderRecorded(
        override val id: LedgerEntryId,
        override val sessionId: PaperSessionId,
        override val sequence: Long,
        override val recordedAt: MarketTimestamp,
        override val causedBy: MarketEventId,
        val order: PaperOrder,
    ) : PaperLedgerEntry

    @Serializable
    @SerialName("fill")
    data class FillRecorded(
        override val id: LedgerEntryId,
        override val sessionId: PaperSessionId,
        override val sequence: Long,
        override val recordedAt: MarketTimestamp,
        override val causedBy: MarketEventId,
        val fill: PaperFill,
    ) : PaperLedgerEntry

    @Serializable
    @SerialName("funding")
    data class FundingApplied(
        override val id: LedgerEntryId,
        override val sessionId: PaperSessionId,
        override val sequence: Long,
        override val recordedAt: MarketTimestamp,
        override val causedBy: MarketEventId,
        val instrument: InstrumentId,
        val amount: DecimalValue,
    ) : PaperLedgerEntry
}
