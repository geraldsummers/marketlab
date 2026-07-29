package dev.marketlab.engine

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.paper.OrderSide
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class TradingMetricSet(
    val count: Int,
    val grossLogReturn: Double,
    val netLogReturn: Double,
    val annualizedSharpe: Double,
    val maximumDrawdown: Double,
    val turnover: Double,
    val totalCosts: Double,
)

object TradingEvaluation {
    /**
     * Converts forecasts into {-1,0,+1} positions. Returns must be observed
     * log returns; [oneWayCostRate] is charged on absolute position changes.
     */
    fun directional(
        realizedLogReturns: List<Double>,
        forecasts: List<Double>,
        oneWayCostRate: Double,
        periodsPerYear: Double,
    ): TradingMetricSet {
        require(realizedLogReturns.isNotEmpty())
        require(realizedLogReturns.size == forecasts.size)
        require(realizedLogReturns.all(Double::isFinite) && forecasts.all(Double::isFinite))
        require(oneWayCostRate.isFinite() && oneWayCostRate >= 0.0)
        require(periodsPerYear.isFinite() && periodsPerYear > 0.0)

        var priorPosition = 0.0
        var turnover = 0.0
        val gross = ArrayList<Double>(forecasts.size)
        val net = ArrayList<Double>(forecasts.size)
        forecasts.indices.forEach { index ->
            val position = when {
                forecasts[index] > 0.0 -> 1.0
                forecasts[index] < 0.0 -> -1.0
                else -> 0.0
            }
            val traded = abs(position - priorPosition)
            val periodGross = position * realizedLogReturns[index]
            gross += periodGross
            net += periodGross - traded * oneWayCostRate
            turnover += traded
            priorPosition = position
        }
        val mean = net.average()
        val sampleVariance = if (net.size > 1) {
            net.sumOf { (it - mean) * (it - mean) } / (net.size - 1)
        } else {
            0.0
        }
        val sharpe = if (sampleVariance > 0.0) {
            mean / sqrt(sampleVariance) * sqrt(periodsPerYear)
        } else {
            0.0
        }
        var cumulative = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        net.forEach { value ->
            cumulative += value
            peak = maxOf(peak, cumulative)
            maxDrawdown = maxOf(maxDrawdown, 1.0 - exp(cumulative - peak))
        }
        return TradingMetricSet(
            count = net.size,
            grossLogReturn = gross.sum(),
            netLogReturn = net.sum(),
            annualizedSharpe = sharpe,
            maximumDrawdown = maxDrawdown,
            turnover = turnover,
            totalCosts = turnover * oneWayCostRate,
        )
    }
}

data class CapacityEstimate(
    val requestedNotional: DecimalValue,
    val filledNotional: DecimalValue,
    val filledQuantity: DecimalValue,
    val vwap: DecimalValue?,
    val referencePrice: DecimalValue,
    val impactBasisPoints: Double?,
    val complete: Boolean,
)

/**
 * A point-in-time, explicitly non-historical capacity check over an observed
 * Hyperliquid book. No missing levels or queue fills are invented.
 */
object BookCapacityAnalyzer {
    fun sweep(
        book: L2Book,
        side: OrderSide,
        notional: DecimalValue,
        maximumDisplayedDepthFraction: DecimalValue,
    ): CapacityEstimate {
        require(notional > DecimalValue.ZERO)
        require(maximumDisplayedDepthFraction > DecimalValue.ZERO)
        require(maximumDisplayedDepthFraction <= DecimalValue.of("1"))
        val levels = when (side) {
            OrderSide.BUY -> book.asks
            OrderSide.SELL -> book.bids
        }
        require(levels.isNotEmpty())
        val reference = levels.first().price
        val target = notional.toBigDecimal()
        var filledNotional = BigDecimal.ZERO
        var filledQuantity = BigDecimal.ZERO
        for (level in levels) {
            val levelQuantity = level.quantity.toBigDecimal()
                .multiply(maximumDisplayedDepthFraction.toBigDecimal(), MathContext.DECIMAL128)
            val remainingNotional = target - filledNotional
            if (remainingNotional.signum() <= 0) break
            val affordable = remainingNotional.divide(level.price.toBigDecimal(), MathContext.DECIMAL128)
            val quantity = levelQuantity.min(affordable)
            if (quantity.signum() <= 0) continue
            filledQuantity += quantity
            filledNotional += quantity.multiply(level.price.toBigDecimal(), MathContext.DECIMAL128)
        }
        val complete = filledNotional >= target.subtract(BigDecimal("0.00000001"))
        val vwap = if (filledQuantity.signum() > 0) {
            filledNotional.divide(filledQuantity, MathContext.DECIMAL128)
        } else {
            null
        }
        val impact = vwap?.let {
            val signed = when (side) {
                OrderSide.BUY -> it.divide(reference.toBigDecimal(), MathContext.DECIMAL128) - BigDecimal.ONE
                OrderSide.SELL -> BigDecimal.ONE - it.divide(reference.toBigDecimal(), MathContext.DECIMAL128)
            }
            signed.toDouble() * BASIS_POINTS
        }
        return CapacityEstimate(
            requestedNotional = notional,
            filledNotional = DecimalValue.of(filledNotional),
            filledQuantity = DecimalValue.of(filledQuantity),
            vwap = vwap?.let(DecimalValue::of),
            referencePrice = reference,
            impactBasisPoints = impact,
            complete = complete,
        )
    }

    private const val BASIS_POINTS = 10_000.0
}
