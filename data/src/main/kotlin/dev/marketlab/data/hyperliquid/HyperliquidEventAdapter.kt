package dev.marketlab.data.hyperliquid

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.market.BookLevel
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.market.Funding
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.market.MarkOracle
import dev.marketlab.contracts.market.MarketDataSource
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.contracts.market.OpenInterest
import dev.marketlab.contracts.market.VenueKind
import dev.marketlab.data.json.CanonicalJson
import java.time.Instant

internal object HyperliquidEventAdapter {
    val sourceId = DataSourceId("hyperliquid-mainnet")
    val source = MarketDataSource(
        id = sourceId,
        displayName = "Hyperliquid Mainnet",
        venueKind = VenueKind.DECENTRALIZED_EXCHANGE,
        production = true,
    )

    /**
     * REST candle history has no original receive timestamp. Its conservative
     * semantic availability is the first millisecond after the exchange's
     * inclusive candle close; [EventHeader.receivedAt] still records when this
     * collector actually retrieved the historical response.
     */
    fun historicalCandles(captured: CapturedResponse<List<HyperliquidCandle>>): List<Candle> =
        candles(captured, observedAvailability = false)

    /** Streaming data is never backdated: availability is local receive time. */
    fun streamingCandles(captured: CapturedResponse<List<HyperliquidCandle>>): List<Candle> =
        candles(captured, observedAvailability = true)

    private fun candles(
        captured: CapturedResponse<List<HyperliquidCandle>>,
        observedAvailability: Boolean,
    ): List<Candle> =
        captured.value.map { candle ->
            val intervalMillis = Math.addExact(
                candle.closeTime.toEpochMilli() - candle.openTime.toEpochMilli(),
                1L,
            )
            Candle(
                header = header(
                    kind = "candle",
                    coin = candle.coin,
                    exchangeTime = candle.closeTime,
                    receivedAt = captured.receivedAt,
                    availableAt = if (observedAvailability) {
                        captured.receivedAt
                    } else {
                        candle.closeTime.plusMillis(1L)
                    },
                    discriminator = "${candle.interval}:${candle.openTime.toEpochMilli()}",
                ),
                intervalMillis = intervalMillis,
                open = DecimalValue.of(candle.open),
                high = DecimalValue.of(candle.high),
                low = DecimalValue.of(candle.low),
                close = DecimalValue.of(candle.close),
                baseVolume = DecimalValue.of(candle.volume),
                tradeCount = candle.tradeCount,
                closed = true,
            )
        }

    /**
     * Hyperliquid's historical funding record does not expose publication time.
     * We use the funding event time as semantic availability and retain the
     * later REST retrieval in receivedAt.
     */
    fun historicalFunding(captured: CapturedResponse<List<HyperliquidFunding>>): List<Funding> =
        captured.value.map { funding ->
            Funding(
                header = header(
                    kind = "funding",
                    coin = funding.coin,
                    exchangeTime = funding.time,
                    receivedAt = captured.receivedAt,
                    availableAt = funding.time,
                    discriminator = funding.time.toEpochMilli().toString(),
                ),
                rate = DecimalValue.of(funding.fundingRate),
                intervalMillis = FUNDING_INTERVAL_MILLIS,
                premium = DecimalValue.of(funding.premium),
            )
        }

    fun l2Book(captured: CapturedResponse<HyperliquidL2Book>): L2Book {
        val book = captured.value
        val rawHash = CanonicalJson.sha256(captured.rawBody)
        return L2Book(
            header = header(
                kind = "l2",
                coin = book.coin,
                exchangeTime = book.time,
                receivedAt = captured.receivedAt,
                availableAt = captured.receivedAt,
                discriminator = "${book.time.toEpochMilli()}:${rawHash.take(16)}",
            ),
            bids = book.bids.map(::bookLevel),
            asks = book.asks.map(::bookLevel),
            checksum = rawHash,
        )
    }

    fun contexts(captured: CapturedResponse<MetaAndAssetContexts>): List<MarketEvent> =
        captured.value.contexts.flatMap { context ->
            context(
                context = context,
                receivedAt = captured.receivedAt,
                discriminator = captured.receivedAt.toEpochMilli().toString(),
            )
        }

    fun context(
        context: HyperliquidAssetContext,
        receivedAt: Instant,
        discriminator: String,
    ): List<MarketEvent> =
        buildList {
            val mark = context.markPrice
            val oracle = context.oraclePrice
            if (mark != null && oracle != null) {
                add(
                    MarkOracle(
                        header = header(
                            kind = "mark-oracle",
                            coin = context.name,
                            exchangeTime = receivedAt,
                            receivedAt = receivedAt,
                            availableAt = receivedAt,
                            discriminator = discriminator,
                        ),
                        markPrice = DecimalValue.of(mark),
                        oraclePrice = DecimalValue.of(oracle),
                        indexPrice = null,
                    ),
                )
            }
            context.openInterest?.let { openInterest ->
                add(
                    OpenInterest(
                        header = header(
                            kind = "open-interest",
                            coin = context.name,
                            exchangeTime = receivedAt,
                            receivedAt = receivedAt,
                            availableAt = receivedAt,
                            discriminator = discriminator,
                        ),
                        contracts = DecimalValue.of(openInterest),
                        notional = null,
                    ),
                )
            }
        }

    private fun bookLevel(level: HyperliquidBookLevel): BookLevel =
        BookLevel(
            price = DecimalValue.of(level.price),
            quantity = DecimalValue.of(level.size),
            orderCount = level.orderCount,
        )

    private fun header(
        kind: String,
        coin: String,
        exchangeTime: Instant,
        receivedAt: Instant,
        availableAt: Instant,
        discriminator: String,
    ): EventHeader =
        EventHeader(
            id = MarketEventId("hl:$kind:$coin:$discriminator"),
            source = sourceId,
            instrument = instrumentId(coin),
            exchangeTime = MarketTimestamp(exchangeTime.toEpochMilli()),
            receivedAt = MarketTimestamp(receivedAt.toEpochMilli()),
            availableAt = MarketTimestamp(availableAt.toEpochMilli()),
        )

    private fun instrumentId(coin: String): InstrumentId =
        InstrumentId("hyperliquid:perpetual:$coin")

    private const val FUNDING_INTERVAL_MILLIS = 3_600_000L
}
