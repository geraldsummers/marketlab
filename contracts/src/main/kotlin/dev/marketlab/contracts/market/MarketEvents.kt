package dev.marketlab.contracts.market

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VenueKind {
    CENTRALIZED_EXCHANGE,
    DECENTRALIZED_EXCHANGE,
    BLOCKCHAIN,
    MACROECONOMIC,
}

@Serializable
data class MarketDataSource(
    val id: DataSourceId,
    val displayName: String,
    val venueKind: VenueKind,
    val production: Boolean,
) {
    init {
        require(displayName.isNotBlank()) { "data source display name cannot be blank" }
    }
}

@Serializable
enum class InstrumentKind {
    SPOT,
    PERPETUAL,
    DATED_FUTURE,
    OPTION,
    INDEX,
}

@Serializable
data class InstrumentRef(
    val id: InstrumentId,
    val venue: DataSourceId,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val kind: InstrumentKind,
) {
    init {
        require(symbol.isNotBlank()) { "instrument symbol cannot be blank" }
        require(baseAsset.isNotBlank()) { "base asset cannot be blank" }
        require(quoteAsset.isNotBlank()) { "quote asset cannot be blank" }
    }
}

@Serializable
data class EventHeader(
    val id: MarketEventId,
    val source: DataSourceId,
    val instrument: InstrumentId,
    val exchangeTime: MarketTimestamp,
    val receivedAt: MarketTimestamp,
    val availableAt: MarketTimestamp,
    val sequence: Long? = null,
) {
    init {
        require(sequence == null || sequence >= 0) { "sequence cannot be negative" }
    }
}

@Serializable
sealed interface MarketEvent {
    val header: EventHeader
}

@Serializable
enum class AggressorSide {
    BUY,
    SELL,
    UNKNOWN,
}

@Serializable
@SerialName("trade")
data class Trade(
    override val header: EventHeader,
    val tradeId: String,
    val price: DecimalValue,
    val quantity: DecimalValue,
    val aggressor: AggressorSide,
) : MarketEvent {
    init {
        require(tradeId.isNotBlank()) { "trade id cannot be blank" }
        require(price > DecimalValue.ZERO) { "trade price must be positive" }
        require(quantity > DecimalValue.ZERO) { "trade quantity must be positive" }
    }
}

@Serializable
@SerialName("bbo")
data class Bbo(
    override val header: EventHeader,
    val bidPrice: DecimalValue,
    val bidQuantity: DecimalValue,
    val askPrice: DecimalValue,
    val askQuantity: DecimalValue,
) : MarketEvent {
    init {
        require(bidPrice > DecimalValue.ZERO && askPrice > DecimalValue.ZERO) { "BBO prices must be positive" }
        require(bidQuantity >= DecimalValue.ZERO && askQuantity >= DecimalValue.ZERO) {
            "BBO quantities cannot be negative"
        }
        require(bidPrice < askPrice) { "BBO must have a positive spread" }
    }
}

@Serializable
data class BookLevel(
    val price: DecimalValue,
    val quantity: DecimalValue,
    val orderCount: Int? = null,
) {
    init {
        require(price > DecimalValue.ZERO) { "book price must be positive" }
        require(quantity >= DecimalValue.ZERO) { "book quantity cannot be negative" }
        require(orderCount == null || orderCount >= 0) { "order count cannot be negative" }
    }
}

@Serializable
@SerialName("l2_book")
data class L2Book(
    override val header: EventHeader,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>,
    val checksum: String? = null,
) : MarketEvent {
    init {
        require(bids.isNotEmpty() && asks.isNotEmpty()) { "L2 book must contain both sides" }
        require(bids.zipWithNext().all { (left, right) -> left.price > right.price }) {
            "bids must be strictly descending"
        }
        require(asks.zipWithNext().all { (left, right) -> left.price < right.price }) {
            "asks must be strictly ascending"
        }
        require(bids.first().price < asks.first().price) { "L2 book must not be crossed" }
    }
}

@Serializable
@SerialName("candle")
data class Candle(
    override val header: EventHeader,
    val intervalMillis: Long,
    val open: DecimalValue,
    val high: DecimalValue,
    val low: DecimalValue,
    val close: DecimalValue,
    val baseVolume: DecimalValue,
    val tradeCount: Long,
    val closed: Boolean,
) : MarketEvent {
    init {
        require(intervalMillis > 0) { "candle interval must be positive" }
        require(open > DecimalValue.ZERO && high > DecimalValue.ZERO) { "candle prices must be positive" }
        require(low > DecimalValue.ZERO && close > DecimalValue.ZERO) { "candle prices must be positive" }
        require(high >= open && high >= close && high >= low) { "candle high is inconsistent" }
        require(low <= open && low <= close) { "candle low is inconsistent" }
        require(baseVolume >= DecimalValue.ZERO) { "candle volume cannot be negative" }
        require(tradeCount >= 0) { "trade count cannot be negative" }
    }
}

@Serializable
@SerialName("funding")
data class Funding(
    override val header: EventHeader,
    val rate: DecimalValue,
    val intervalMillis: Long,
    val premium: DecimalValue? = null,
) : MarketEvent {
    init {
        require(intervalMillis > 0) { "funding interval must be positive" }
    }
}

@Serializable
@SerialName("open_interest")
data class OpenInterest(
    override val header: EventHeader,
    val contracts: DecimalValue,
    val notional: DecimalValue? = null,
) : MarketEvent {
    init {
        require(contracts >= DecimalValue.ZERO) { "open interest cannot be negative" }
        require(notional == null || notional >= DecimalValue.ZERO) { "open-interest notional cannot be negative" }
    }
}

@Serializable
@SerialName("mark_oracle")
data class MarkOracle(
    override val header: EventHeader,
    val markPrice: DecimalValue,
    val oraclePrice: DecimalValue,
    val indexPrice: DecimalValue? = null,
) : MarketEvent {
    init {
        require(markPrice > DecimalValue.ZERO && oraclePrice > DecimalValue.ZERO) {
            "mark and oracle prices must be positive"
        }
        require(indexPrice == null || indexPrice > DecimalValue.ZERO) { "index price must be positive" }
    }
}

@Serializable
@SerialName("instrument_metadata")
data class InstrumentMetadata(
    override val header: EventHeader,
    val tickSize: DecimalValue,
    val lotSize: DecimalValue,
    val makerFeeRate: DecimalValue?,
    val takerFeeRate: DecimalValue,
    val active: Boolean,
) : MarketEvent {
    init {
        require(tickSize > DecimalValue.ZERO && lotSize > DecimalValue.ZERO) {
            "tick and lot sizes must be positive"
        }
        require(takerFeeRate >= DecimalValue.ZERO) { "taker fee rate cannot be negative" }
    }
}
