package dev.marketlab.data.hyperliquid

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.MarketEventId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.DataQualityIssue
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.contracts.market.AggressorSide
import dev.marketlab.contracts.market.Bbo
import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.contracts.market.Trade
import dev.marketlab.data.json.CanonicalJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.Closeable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

sealed interface HyperliquidStreamSubscription {
    val channel: String
    val expectedMaximumGap: Duration

    data class Trades(
        val coin: String,
        override val expectedMaximumGap: Duration = Duration.ofMinutes(1),
    ) : HyperliquidStreamSubscription {
        override val channel: String = "trades"
    }

    data class Bbo(
        val coin: String,
        override val expectedMaximumGap: Duration = Duration.ofMinutes(1),
    ) : HyperliquidStreamSubscription {
        override val channel: String = "bbo"
    }

    data class L2Book(
        val coin: String,
        override val expectedMaximumGap: Duration = Duration.ofMinutes(1),
    ) : HyperliquidStreamSubscription {
        override val channel: String = "l2Book"
    }

    data class Candles(
        val coin: String,
        val interval: String,
        override val expectedMaximumGap: Duration = Duration.ofMinutes(5),
    ) : HyperliquidStreamSubscription {
        override val channel: String = "candle"
    }

    data class ActiveAssetContext(
        val coin: String,
        override val expectedMaximumGap: Duration = Duration.ofMinutes(2),
    ) : HyperliquidStreamSubscription {
        override val channel: String = "activeAssetCtx"
    }
}

sealed interface HyperliquidStreamItem {
    data class Connected(val at: MarketTimestamp) : HyperliquidStreamItem

    data class SubscriptionAcknowledged(
        val at: MarketTimestamp,
        val rawBody: ByteArray,
        val contentHash: Sha256Digest,
    ) : HyperliquidStreamItem

    data class Observation(
        val event: MarketEvent,
        val rawBody: ByteArray,
        val contentHash: Sha256Digest,
        val receivedAt: MarketTimestamp,
    ) : HyperliquidStreamItem

    data class QualitySignal(val issue: DataQualityIssue) : HyperliquidStreamItem
}

/**
 * Reconnecting mainnet collector. Hyperliquid public streams do not expose a
 * universal sequence number, so reconnect boundaries and timestamp stalls are
 * surfaced explicitly instead of pretending continuity can be proven.
 */
class HyperliquidWebSocketCollector private constructor(
    private val client: HttpClient,
    private val endpoint: String,
    private val clock: Clock,
    private val heartbeatInterval: Duration,
    private val pongTimeout: Duration,
    private val closeClient: Boolean,
) : Closeable {
    fun stream(subscription: HyperliquidStreamSubscription): Flow<HyperliquidStreamItem> =
        channelFlow {
            validate(subscription)
            val seen = BoundedIdentitySet(MAX_DEDUPE_IDENTITIES)
            var lastExchangeTime: MarketTimestamp? = null
            var lastReceiveTime: MarketTimestamp? = null
            var reconnectAttempt = 0
            while (currentCoroutineContext().isActive) {
                try {
                    client.webSocket(urlString = endpoint) {
                        send(Frame.Text(subscriptionMessage(subscription)))
                        val connectedAt = timestamp(clock.instant())
                        send(HyperliquidStreamItem.Connected(connectedAt))
                        lastReceiveTime?.let { prior ->
                            if (prior < connectedAt) {
                                send(
                                    HyperliquidStreamItem.QualitySignal(
                                        gapIssue(
                                            "WebSocket reconnect boundary; exchange continuity cannot be proven",
                                            prior,
                                            connectedAt,
                                        ),
                                    ),
                                )
                            }
                        }
                        while (currentCoroutineContext().isActive) {
                            var frame = withTimeoutOrNull(heartbeatInterval.toMillis()) {
                                incoming.receive()
                            }
                            if (frame == null) {
                                send(Frame.Text(PING_MESSAGE))
                                frame = withTimeoutOrNull(pongTimeout.toMillis()) {
                                    incoming.receive()
                                } ?: error("Hyperliquid heartbeat timed out")
                            }
                            if (frame !is Frame.Text) continue
                            val receivedAt = timestamp(clock.instant())
                            lastReceiveTime = receivedAt
                            val rawText = frame.readText()
                            val acknowledgement =
                                subscriptionAcknowledgement(subscription, rawText, receivedAt)
                            if (acknowledgement != null) {
                                send(acknowledgement)
                                continue
                            }
                            val parsed = parseMessage(subscription, rawText, receivedAt)
                            if (parsed.isNotEmpty()) reconnectAttempt = 0
                            // A trades frame can contain many observations. Preserve the exact
                            // frame once and share that immutable byte array across its events
                            // instead of multiplying peak memory by the trade count.
                            val rawBody = rawText.toByteArray(Charsets.UTF_8)
                            val contentHash = Sha256Digest(CanonicalJson.sha256(rawBody))
                            for (event in parsed) {
                                val eventTime = event.header.exchangeTime
                                lastExchangeTime?.let { prior ->
                                    val delta = eventTime.epochMillis - prior.epochMillis
                                    if (delta < 0L) {
                                        send(
                                            HyperliquidStreamItem.QualitySignal(
                                                DataQualityIssue(
                                                    kind = QualityIssueKind.SOURCE_REVISION,
                                                    severity = QualitySeverity.WARNING,
                                                    message = "Out-of-order WebSocket event time",
                                                    timeRange = range(eventTime, prior),
                                                ),
                                            ),
                                        )
                                    } else if (delta > subscription.expectedMaximumGap.toMillis()) {
                                        send(
                                            HyperliquidStreamItem.QualitySignal(
                                                DataQualityIssue(
                                                    kind = QualityIssueKind.STALE_DATA,
                                                    severity = QualitySeverity.WARNING,
                                                    message = "WebSocket event-time gap of $delta ms",
                                                    timeRange = range(prior, eventTime),
                                                ),
                                            ),
                                        )
                                    }
                                }
                                if (lastExchangeTime == null || eventTime > checkNotNull(lastExchangeTime)) {
                                    lastExchangeTime = eventTime
                                }
                                if (!seen.add(event.header.id.value)) continue
                                send(
                                    HyperliquidStreamItem.Observation(
                                        event = event,
                                        rawBody = rawBody,
                                        contentHash = contentHash,
                                        receivedAt = receivedAt,
                                    ),
                                )
                            }
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    LOGGER.warn(
                        "Hyperliquid WebSocket disconnected for {}: {}",
                        subscription.channel,
                        exception.message,
                    )
                }
                val delayMillis = reconnectDelayMillis(reconnectAttempt)
                reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_EXPONENT)
                delay(delayMillis)
            }
        }

    override fun close() {
        if (closeClient) client.close()
    }

    private fun parseMessage(
        subscription: HyperliquidStreamSubscription,
        rawText: String,
        receivedAt: MarketTimestamp,
    ): List<MarketEvent> {
        val root = HyperliquidJson.parseElement(rawText).jsonObject
        val channel = root["channel"]?.jsonPrimitive?.content ?: return emptyList()
        if (channel in CONTROL_CHANNELS) return emptyList()
        if (channel != subscription.channel) return emptyList()
        val data = root["data"] ?: return emptyList()
        return when (subscription) {
            is HyperliquidStreamSubscription.Trades -> parseTrades(data, receivedAt)
            is HyperliquidStreamSubscription.Bbo -> listOfNotNull(parseBbo(data, receivedAt))
            is HyperliquidStreamSubscription.L2Book -> {
                val capture = capture(data, rawText, receivedAt, HyperliquidJson::parseL2Book)
                listOf(HyperliquidEventAdapter.l2Book(capture))
            }
            is HyperliquidStreamSubscription.Candles -> {
                val candleArray = JsonArray(listOf(data))
                val capture = capture(
                    candleArray,
                    rawText,
                    receivedAt,
                    HyperliquidJson::parseCandles,
                )
                capture.value
                    .filter { it.closeTime.toEpochMilli() < receivedAt.epochMillis }
                    .let { closed ->
                        HyperliquidEventAdapter.streamingCandles(capture.copy(value = closed))
                    }
            }
            is HyperliquidStreamSubscription.ActiveAssetContext ->
                parseActiveAssetContext(data, receivedAt)
        }
    }

    private fun subscriptionAcknowledgement(
        subscription: HyperliquidStreamSubscription,
        rawText: String,
        receivedAt: MarketTimestamp,
    ): HyperliquidStreamItem.SubscriptionAcknowledged? {
        val root = HyperliquidJson.parseElement(rawText).jsonObject
        if (root["channel"]?.jsonPrimitive?.content != "subscriptionResponse") return null
        val data = root["data"]?.jsonObject ?: return null
        if (data["method"]?.jsonPrimitive?.content != "subscribe") return null
        val acknowledged = data["subscription"]?.jsonObject ?: return null
        if (acknowledged["type"]?.jsonPrimitive?.content != subscription.channel) return null
        val expectedCoin = when (subscription) {
            is HyperliquidStreamSubscription.Trades -> subscription.coin
            is HyperliquidStreamSubscription.Bbo -> subscription.coin
            is HyperliquidStreamSubscription.L2Book -> subscription.coin
            is HyperliquidStreamSubscription.Candles -> subscription.coin
            is HyperliquidStreamSubscription.ActiveAssetContext -> subscription.coin
        }
        if (acknowledged["coin"]?.jsonPrimitive?.content != expectedCoin) return null
        val rawBody = rawText.toByteArray(Charsets.UTF_8)
        return HyperliquidStreamItem.SubscriptionAcknowledged(
            at = receivedAt,
            rawBody = rawBody,
            contentHash = Sha256Digest(CanonicalJson.sha256(rawBody)),
        )
    }

    private fun parseTrades(data: JsonElement, receivedAt: MarketTimestamp): List<Trade> =
        data.jsonArray.map { element ->
            val trade = element.jsonObject
            val coin = trade.requiredString("coin")
            val tradeId = trade.requiredString("tid")
            val exchangeTime = MarketTimestamp(trade.requiredLong("time"))
            Trade(
                header = streamHeader(
                    id = "hl:trade:$coin:$tradeId",
                    coin = coin,
                    exchangeTime = exchangeTime,
                    receivedAt = receivedAt,
                ),
                tradeId = tradeId,
                price = DecimalValue.of(trade.requiredString("px")),
                quantity = DecimalValue.of(trade.requiredString("sz")),
                aggressor = when (trade.requiredString("side")) {
                    "B" -> AggressorSide.BUY
                    "A" -> AggressorSide.SELL
                    else -> AggressorSide.UNKNOWN
                },
            )
        }

    private fun parseBbo(data: JsonElement, receivedAt: MarketTimestamp): Bbo? {
        val value = data.jsonObject
        val sides = value["bbo"]?.jsonArray ?: return null
        if (sides.size != 2 || sides.any { it is JsonNull }) return null
        val bid = sides[0].jsonObject
        val ask = sides[1].jsonObject
        val coin = value.requiredString("coin")
        val exchangeTime = MarketTimestamp(value.requiredLong("time"))
        val discriminator = CanonicalJson.sha256(data).take(16)
        return Bbo(
            header = streamHeader(
                id = "hl:bbo:$coin:${exchangeTime.epochMillis}:$discriminator",
                coin = coin,
                exchangeTime = exchangeTime,
                receivedAt = receivedAt,
            ),
            bidPrice = DecimalValue.of(bid.requiredString("px")),
            bidQuantity = DecimalValue.of(bid.requiredString("sz")),
            askPrice = DecimalValue.of(ask.requiredString("px")),
            askQuantity = DecimalValue.of(ask.requiredString("sz")),
        )
    }

    private fun parseActiveAssetContext(
        data: JsonElement,
        receivedAt: MarketTimestamp,
    ): List<MarketEvent> {
        val value = data.jsonObject
        val coin = value.requiredString("coin")
        val context = value["ctx"]?.jsonObject ?: value
        val parsed = HyperliquidAssetContext(
            index = null,
            name = coin,
            markPrice = context.optionalDecimal("markPx"),
            oraclePrice = context.optionalDecimal("oraclePx"),
            midPrice = context.optionalDecimal("midPx"),
            fundingRate = context.optionalDecimal("funding"),
            openInterest = context.optionalDecimal("openInterest"),
            dayNotionalVolume = context.optionalDecimal("dayNtlVlm"),
            premium = context.optionalDecimal("premium"),
        )
        return HyperliquidEventAdapter.context(
            context = parsed,
            receivedAt = Instant.ofEpochMilli(receivedAt.epochMillis),
            discriminator = CanonicalJson.sha256(data).take(16),
        )
    }

    private fun <T> capture(
        data: JsonElement,
        rawText: String,
        receivedAt: MarketTimestamp,
        parser: (ByteArray) -> T,
    ): CapturedResponse<T> {
        val payload = CanonicalJson.encode(data)
        val instant = Instant.ofEpochMilli(receivedAt.epochMillis)
        return CapturedResponse(
            requestBody = "",
            rawBody = rawText.toByteArray(Charsets.UTF_8),
            requestedAt = instant,
            receivedAt = instant,
            value = parser(payload),
        )
    }

    private fun streamHeader(
        id: String,
        coin: String,
        exchangeTime: MarketTimestamp,
        receivedAt: MarketTimestamp,
    ): EventHeader =
        EventHeader(
            id = MarketEventId(id),
            source = HyperliquidEventAdapter.sourceId,
            instrument = dev.marketlab.contracts.InstrumentId("hyperliquid:perpetual:$coin"),
            exchangeTime = exchangeTime,
            receivedAt = receivedAt,
            availableAt = receivedAt,
        )

    private fun subscriptionMessage(subscription: HyperliquidStreamSubscription): String {
        val body = buildJsonObject {
            put("method", "subscribe")
            put(
                "subscription",
                buildJsonObject {
                    put("type", subscription.channel)
                    when (subscription) {
                        is HyperliquidStreamSubscription.Trades -> put("coin", subscription.coin)
                        is HyperliquidStreamSubscription.Bbo -> put("coin", subscription.coin)
                        is HyperliquidStreamSubscription.L2Book -> put("coin", subscription.coin)
                        is HyperliquidStreamSubscription.Candles -> {
                            put("coin", subscription.coin)
                            put("interval", subscription.interval)
                        }
                        is HyperliquidStreamSubscription.ActiveAssetContext ->
                            put("coin", subscription.coin)
                    }
                },
            )
        }
        return CanonicalJson.encode(body).toString(Charsets.UTF_8)
    }

    private fun validate(subscription: HyperliquidStreamSubscription) {
        val coin = when (subscription) {
            is HyperliquidStreamSubscription.Trades -> subscription.coin
            is HyperliquidStreamSubscription.Bbo -> subscription.coin
            is HyperliquidStreamSubscription.L2Book -> subscription.coin
            is HyperliquidStreamSubscription.Candles -> subscription.coin
            is HyperliquidStreamSubscription.ActiveAssetContext -> subscription.coin
        }
        require(HyperliquidRestClient.SYMBOL_PATTERN.matches(coin)) { "invalid Hyperliquid symbol" }
        require(!subscription.expectedMaximumGap.isZero && !subscription.expectedMaximumGap.isNegative) {
            "expected maximum gap must be positive"
        }
        if (subscription is HyperliquidStreamSubscription.Candles) {
            CandleCadence.parse(subscription.interval)
        }
    }

    companion object {
        fun mainnet(
            clock: Clock = Clock.systemUTC(),
            heartbeatInterval: Duration = Duration.ofSeconds(20),
            pongTimeout: Duration = Duration.ofSeconds(10),
            maximumFrameBytes: Long = DEFAULT_MAXIMUM_FRAME_BYTES,
        ): HyperliquidWebSocketCollector {
            require(maximumFrameBytes > 0L) { "maximum WebSocket frame size must be positive" }
            val client = HttpClient(CIO) {
                install(WebSockets) {
                    maxFrameSize = maximumFrameBytes
                }
            }
            return HyperliquidWebSocketCollector(
                client = client,
                endpoint = HyperliquidRestClient.MAINNET_WS_URL,
                clock = clock,
                heartbeatInterval = heartbeatInterval,
                pongTimeout = pongTimeout,
                closeClient = true,
            )
        }

        internal fun create(
            client: HttpClient,
            endpoint: String,
            clock: Clock,
            heartbeatInterval: Duration,
            pongTimeout: Duration,
        ): HyperliquidWebSocketCollector =
            HyperliquidWebSocketCollector(
                client,
                endpoint,
                clock,
                heartbeatInterval,
                pongTimeout,
                closeClient = false,
            )

        private val CONTROL_CHANNELS = setOf("subscriptionResponse", "pong")
        private const val PING_MESSAGE = """{"method":"ping"}"""
        private const val MAX_DEDUPE_IDENTITIES = 100_000
        private const val MAX_RECONNECT_EXPONENT = 6
        private const val BASE_RECONNECT_DELAY_MILLIS = 250L
        private const val MAX_RECONNECT_DELAY_MILLIS = 15_000L
        const val DEFAULT_MAXIMUM_FRAME_BYTES = 2L * 1024L * 1024L
        private val LOGGER = LoggerFactory.getLogger(HyperliquidWebSocketCollector::class.java)
    }
}

private class BoundedIdentitySet(private val maximumSize: Int) {
    private val identities = object : LinkedHashMap<String, Unit>(maximumSize, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > maximumSize
    }

    init {
        require(maximumSize > 0) { "dedupe set size must be positive" }
    }

    fun add(value: String): Boolean {
        if (identities.containsKey(value)) return false
        identities[value] = Unit
        return true
    }
}

private fun reconnectDelayMillis(attempt: Int): Long {
    val multiplier = 1L shl attempt.coerceIn(0, 6)
    return (250L * multiplier).coerceAtMost(15_000L)
}

private fun gapIssue(
    message: String,
    from: MarketTimestamp,
    to: MarketTimestamp,
): DataQualityIssue =
    DataQualityIssue(
        kind = QualityIssueKind.SEQUENCE_GAP,
        severity = QualitySeverity.WARNING,
        message = message,
        timeRange = range(from, to),
    )

private fun range(left: MarketTimestamp, right: MarketTimestamp): TimeRange {
    val from = minOf(left.epochMillis, right.epochMillis)
    val to = maxOf(left.epochMillis, right.epochMillis)
    return TimeRange(MarketTimestamp(from), MarketTimestamp(if (to == from) to + 1L else to))
}

private fun timestamp(value: Instant): MarketTimestamp = MarketTimestamp(value.toEpochMilli())

private fun JsonObject.requiredString(key: String): String =
    requireNotNull(this[key]) { "missing '$key'" }.jsonPrimitive.content

private fun JsonObject.requiredLong(key: String): Long =
    requiredString(key).toLong()

private fun JsonObject.optionalDecimal(key: String): java.math.BigDecimal? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.toBigDecimal()
}
