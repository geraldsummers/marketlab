package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.MarketEvent

/**
 * The only event view exposed to feature code. A feature cannot ask for an
 * observation that was not causally available at [decisionTime].
 */
class CausalEventWindow private constructor(
    val decisionTime: MarketTimestamp,
    private val orderedEvents: List<MarketEvent>,
) : Iterable<MarketEvent> {
    override fun iterator(): Iterator<MarketEvent> = orderedEvents.iterator()

    inline fun <reified T : MarketEvent> events(): List<T> = filterIsInstance<T>()

    fun latest(predicate: (MarketEvent) -> Boolean = { true }): MarketEvent? =
        orderedEvents.lastOrNull(predicate)

    companion object {
        fun create(
            events: Sequence<MarketEvent>,
            decisionTime: MarketTimestamp,
            quality: DataQualityReport? = null,
        ): CausalEventWindow {
            require(quality == null || quality.usable) {
                "A snapshot with fatal quality issues cannot enter a causal feature window"
            }
            val visible = events
                .filter { it.header.availableAt <= decisionTime }
                .filterNot { it is Candle && !it.closed }
                .sortedWith(MARKET_EVENT_ORDER)
                .toList()
            return CausalEventWindow(decisionTime, visible)
        }
    }
}

val MARKET_EVENT_ORDER: Comparator<MarketEvent> =
    compareBy<MarketEvent>(
        { it.header.exchangeTime.epochMillis },
        { it.header.sequence ?: Long.MAX_VALUE },
        { it.header.receivedAt.epochMillis },
        { it.header.id.value },
    )

