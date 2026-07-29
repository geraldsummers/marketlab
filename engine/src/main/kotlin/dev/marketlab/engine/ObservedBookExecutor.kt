package dev.marketlab.engine

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.FillId
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.contracts.paper.OrderSide
import dev.marketlab.contracts.paper.PaperFill
import dev.marketlab.contracts.paper.PaperLiquidity
import dev.marketlab.contracts.paper.PaperOrder
import java.math.BigDecimal
import java.math.RoundingMode

data class BookExecutionProfile(
    val takerFeeRate: DecimalValue,
    val maximumDisplayedDepthFraction: DecimalValue,
) {
    init {
        require(takerFeeRate >= DecimalValue.ZERO) { "taker fee cannot be negative" }
        require(maximumDisplayedDepthFraction > DecimalValue.ZERO) {
            "depth participation must be positive"
        }
        require(maximumDisplayedDepthFraction <= DecimalValue.of("1")) {
            "depth participation cannot exceed one"
        }
    }
}

class ObservedBookExecutor(
    private val profile: BookExecutionProfile,
) : PaperExecutionVenue {
    override fun execute(order: PaperOrder, event: MarketEvent): List<PaperFill> {
        require(event is L2Book) { "promotion-grade execution requires an observed L2 book" }
        require(event.header.instrument == order.instrument) { "book and order instruments differ" }
        require(event.header.id != order.decisionEventId) { "an order cannot fill on its decision event" }
        require(event.header.availableAt >= order.eligibleExecutionTime) {
            "book was not available after the configured execution latency"
        }

        val levels = when (order.side) {
            OrderSide.BUY -> event.asks
            OrderSide.SELL -> event.bids
        }
        val displayedDepth = levels.fold(BigDecimal.ZERO) { total, level ->
            total + level.quantity.toBigDecimal()
        }
        val permitted = displayedDepth * profile.maximumDisplayedDepthFraction.toBigDecimal()
        require(order.remainingQuantity.toBigDecimal() <= permitted) {
            "order exceeds the configured displayed-depth participation"
        }

        var remaining = order.remainingQuantity.toBigDecimal()
        return buildList {
            for ((index, level) in levels.withIndex()) {
                if (remaining.signum() == 0) break
                val participatingQuantity =
                    level.quantity.toBigDecimal() *
                        profile.maximumDisplayedDepthFraction.toBigDecimal()
                val quantity = remaining.min(participatingQuantity)
                if (quantity.signum() == 0) continue
                val notional = quantity * level.price.toBigDecimal()
                val fee = notional.multiply(profile.takerFeeRate.toBigDecimal())
                    .setScale(18, RoundingMode.HALF_EVEN)
                    .stripTrailingZeros()
                add(
                    PaperFill(
                        id = FillId("${order.id.value}:${event.header.id.value}:$index"),
                        orderId = order.id,
                        sessionId = order.sessionId,
                        marketEventId = event.header.id,
                        time = event.header.availableAt,
                        price = level.price,
                        quantity = DecimalValue.of(quantity),
                        fee = DecimalValue.of(fee),
                        liquidity = PaperLiquidity.TAKER,
                    ),
                )
                remaining -= quantity
            }
        }
    }
}
