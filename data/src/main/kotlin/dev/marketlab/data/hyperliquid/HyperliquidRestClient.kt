package dev.marketlab.data.hyperliquid

import dev.marketlab.data.json.CanonicalJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class HyperliquidRestClient(
    private val client: HttpClient,
    private val endpoint: String = MAINNET_INFO_URL,
    private val rateLimit: RateLimitGate = WeightedRateLimiter(),
    private val clock: Clock = Clock.systemUTC(),
    private val closeClient: Boolean = false,
) : Closeable {
    suspend fun metaAndAssetContexts(): CapturedResponse<MetaAndAssetContexts> =
        request(
            weight = GENERAL_INFO_WEIGHT,
            body = buildJsonObject { put("type", "metaAndAssetCtxs") },
            parser = HyperliquidJson::parseMetaAndAssetContexts,
        )

    suspend fun candles(
        coin: String,
        interval: String,
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<CapturedResponse<List<HyperliquidCandle>>> {
        requireSymbol(coin)
        require(startInclusive.isBefore(endExclusive)) { "candle range must be non-empty" }
        val cadence = CandleCadence.parse(interval)
        val pages = mutableListOf<CapturedResponse<List<HyperliquidCandle>>>()
        var cursor = startInclusive.truncatedTo(ChronoUnit.MILLIS)
        val upperBound = endExclusive.truncatedTo(ChronoUnit.MILLIS)
        while (cursor.isBefore(upperBound)) {
            val pageEndExclusive = minInstant(cadence.advance(cursor, CANDLE_PAGE_SIZE), upperBound)
            val body = candleRequest(
                coin = coin,
                interval = interval,
                startMillis = cursor.toEpochMilli(),
                endInclusiveMillis = pageEndExclusive.toEpochMilli() - 1L,
            )
            val captured = request(
                weight = CANDLE_SNAPSHOT_WEIGHT,
                body = body,
                parser = HyperliquidJson::parseCandles,
            )
            val closed = captured.value
                .asSequence()
                .filter { it.coin == coin && it.interval == interval }
                .filter { !it.openTime.isBefore(startInclusive) && it.openTime.isBefore(endExclusive) }
                .filter { it.closeTime.isBefore(captured.receivedAt) }
                .distinctBy { it.openTime }
                .sortedBy { it.openTime }
                .toList()
            pages += captured.copy(value = closed)
            cursor = pageEndExclusive
        }
        return pages
    }

    suspend fun fundingHistory(
        coin: String,
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<CapturedResponse<List<HyperliquidFunding>>> {
        requireSymbol(coin)
        require(startInclusive.isBefore(endExclusive)) { "funding range must be non-empty" }
        val pages = mutableListOf<CapturedResponse<List<HyperliquidFunding>>>()
        var cursorMillis = startInclusive.toEpochMilli()
        val endExclusiveMillis = endExclusive.toEpochMilli()
        while (cursorMillis < endExclusiveMillis) {
            val body = buildJsonObject {
                put("type", "fundingHistory")
                put("coin", coin)
                put("startTime", cursorMillis)
                put("endTime", endExclusiveMillis - 1L)
            }
            val captured = request(
                weight = GENERAL_INFO_WEIGHT,
                body = body,
                parser = HyperliquidJson::parseFunding,
            )
            val filtered = captured.value
                .asSequence()
                .filter { it.coin == coin }
                .filter {
                    val millis = it.time.toEpochMilli()
                    millis >= startInclusive.toEpochMilli() && millis < endExclusiveMillis
                }
                .sortedBy { it.time }
                .toList()
            pages += captured.copy(value = filtered)
            if (captured.value.size < FUNDING_PAGE_SIZE || captured.value.isEmpty()) break
            val lastMillis = captured.value.maxOf { it.time.toEpochMilli() }
            require(lastMillis >= cursorMillis) { "funding pagination moved backwards" }
            val next = Math.addExact(lastMillis, 1L)
            require(next > cursorMillis) { "funding pagination made no progress" }
            cursorMillis = next
        }
        return pages
    }

    suspend fun l2Book(
        coin: String,
        significantFigures: Int? = null,
        mantissa: Int? = null,
    ): CapturedResponse<HyperliquidL2Book> {
        requireSymbol(coin)
        require(significantFigures == null || significantFigures in 2..5) {
            "significantFigures must be within 2..5"
        }
        require(mantissa == null || significantFigures == 5) {
            "mantissa is supported only when significantFigures is 5"
        }
        require(mantissa == null || mantissa in setOf(1, 2, 5)) {
            "mantissa must be one of 1, 2, or 5"
        }
        val body = buildJsonObject {
            put("type", "l2Book")
            put("coin", coin)
            significantFigures?.let { put("nSigFigs", it) }
            mantissa?.let { put("mantissa", it) }
        }
        return request(
            weight = L2_BOOK_WEIGHT,
            body = body,
            parser = HyperliquidJson::parseL2Book,
        )
    }

    override fun close() {
        if (closeClient) client.close()
    }

    private suspend fun <T> request(
        weight: Int,
        body: JsonObject,
        parser: (ByteArray) -> T,
    ): CapturedResponse<T> {
        rateLimit.acquire(weight)
        val requestBytes = CanonicalJson.encode(body)
        val requestText = requestBytes.toString(Charsets.UTF_8)
        val requestedAt = clock.instant()
        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(requestText)
        }
        val responseText = response.bodyAsText()
        val receivedAt = clock.instant()
        if (!response.status.isSuccess()) {
            throw HyperliquidHttpException(response.status, responseText.take(MAX_ERROR_BODY_CHARS))
        }
        val rawBody = responseText.toByteArray(Charsets.UTF_8)
        return CapturedResponse(
            requestBody = requestText,
            rawBody = rawBody,
            requestedAt = requestedAt,
            receivedAt = receivedAt,
            value = parser(rawBody),
        )
    }

    private fun candleRequest(
        coin: String,
        interval: String,
        startMillis: Long,
        endInclusiveMillis: Long,
    ): JsonObject = buildJsonObject {
        put("type", "candleSnapshot")
        put(
            "req",
            buildJsonObject {
                put("coin", coin)
                put("interval", interval)
                put("startTime", startMillis)
                put("endTime", endInclusiveMillis)
            },
        )
    }

    private fun requireSymbol(coin: String) {
        require(SYMBOL_PATTERN.matches(coin)) { "invalid Hyperliquid symbol" }
    }

    internal companion object {
        const val MAINNET_INFO_URL = "https://api.hyperliquid.xyz/info"
        const val MAINNET_WS_URL = "wss://api.hyperliquid.xyz/ws"
        const val ADAPTER_VERSION = "hyperliquid-v1"
        const val GENERAL_INFO_WEIGHT = 20
        const val CANDLE_SNAPSHOT_WEIGHT = 20
        const val L2_BOOK_WEIGHT = 2
        const val CANDLE_PAGE_SIZE = 5_000L
        const val FUNDING_PAGE_SIZE = 500
        const val MAX_ERROR_BODY_CHARS = 4_096
        val SYMBOL_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")

        fun mainnet(
            rateLimit: RateLimitGate = WeightedRateLimiter(),
            clock: Clock = Clock.systemUTC(),
        ): HyperliquidRestClient {
            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
                expectSuccess = false
            }
            return HyperliquidRestClient(
                client = client,
                rateLimit = rateLimit,
                clock = clock,
                closeClient = true,
            )
        }
    }
}

internal class HyperliquidHttpException(
    val status: HttpStatusCode,
    responseBody: String,
) : IllegalStateException("Hyperliquid returned HTTP ${status.value}: $responseBody")

internal sealed interface CandleCadence {
    fun advance(value: Instant, bars: Long): Instant

    data class Fixed(private val millis: Long) : CandleCadence {
        override fun advance(value: Instant, bars: Long): Instant =
            value.plusMillis(Math.multiplyExact(millis, bars))
    }

    data object Monthly : CandleCadence {
        override fun advance(value: Instant, bars: Long): Instant =
            value.atZone(ZoneOffset.UTC).plusMonths(bars).toInstant()
    }

    companion object {
        private val fixed = mapOf(
            "1m" to 60_000L,
            "3m" to 180_000L,
            "5m" to 300_000L,
            "15m" to 900_000L,
            "30m" to 1_800_000L,
            "1h" to 3_600_000L,
            "2h" to 7_200_000L,
            "4h" to 14_400_000L,
            "8h" to 28_800_000L,
            "12h" to 43_200_000L,
            "1d" to 86_400_000L,
            "3d" to 259_200_000L,
            "1w" to 604_800_000L,
        )

        fun parse(interval: String): CandleCadence =
            when (interval) {
                "1M" -> Monthly
                else -> Fixed(requireNotNull(fixed[interval]) { "unsupported candle interval '$interval'" })
            }
    }
}

private fun minInstant(left: Instant, right: Instant): Instant =
    if (left <= right) left else right
