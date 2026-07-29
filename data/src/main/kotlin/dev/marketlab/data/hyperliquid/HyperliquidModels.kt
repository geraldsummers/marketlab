package dev.marketlab.data.hyperliquid

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

internal data class CapturedResponse<T>(
    val requestBody: String,
    val rawBody: ByteArray,
    val requestedAt: Instant,
    val receivedAt: Instant,
    val value: T,
)

internal data class HyperliquidAsset(
    val index: Int,
    val name: String,
    val sizeDecimals: Int,
    val maxLeverage: Int,
    val marginTableId: Int,
    val isDelisted: Boolean,
    val onlyIsolated: Boolean,
)

internal data class HyperliquidAssetContext(
    val index: Int?,
    val name: String,
    val markPrice: BigDecimal?,
    val oraclePrice: BigDecimal?,
    val midPrice: BigDecimal?,
    val fundingRate: BigDecimal?,
    val openInterest: BigDecimal?,
    val dayNotionalVolume: BigDecimal?,
    val premium: BigDecimal?,
)

internal data class MetaAndAssetContexts(
    val assets: List<HyperliquidAsset>,
    val contexts: List<HyperliquidAssetContext>,
)

internal data class HyperliquidCandle(
    val coin: String,
    val interval: String,
    val openTime: Instant,
    val closeTime: Instant,
    val open: BigDecimal,
    val close: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val volume: BigDecimal,
    val tradeCount: Long,
)

internal data class HyperliquidFunding(
    val coin: String,
    val time: Instant,
    val fundingRate: BigDecimal,
    val premium: BigDecimal,
)

internal data class HyperliquidBookLevel(
    val price: BigDecimal,
    val size: BigDecimal,
    val orderCount: Int,
)

internal data class HyperliquidL2Book(
    val coin: String,
    val time: Instant,
    val bids: List<HyperliquidBookLevel>,
    val asks: List<HyperliquidBookLevel>,
)

internal object HyperliquidJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parseMetaAndAssetContexts(rawBody: ByteArray): MetaAndAssetContexts {
        val root = parse(rawBody).jsonArray
        require(root.size == 2) { "metaAndAssetCtxs must contain metadata and contexts" }
        val universe = root[0].jsonObject.requiredArray("universe")
        val contextObjects = root[1].jsonArray
        require(universe.size == contextObjects.size) {
            "Hyperliquid metadata/context cardinality mismatch: ${universe.size} != ${contextObjects.size}"
        }
        val assets = universe.mapIndexed { index, element ->
            val value = element.jsonObject
            HyperliquidAsset(
                index = index,
                name = value.requiredString("name"),
                sizeDecimals = value.requiredInt("szDecimals"),
                maxLeverage = value.requiredInt("maxLeverage"),
                marginTableId = value.requiredInt("marginTableId"),
                isDelisted = value.optionalBoolean("isDelisted") ?: false,
                onlyIsolated = value.optionalBoolean("onlyIsolated") ?: false,
            )
        }
        val contexts = contextObjects.mapIndexed { index, element ->
            val value = element.jsonObject
            HyperliquidAssetContext(
                index = index,
                name = assets[index].name,
                markPrice = value.optionalDecimal("markPx"),
                oraclePrice = value.optionalDecimal("oraclePx"),
                midPrice = value.optionalDecimal("midPx"),
                fundingRate = value.optionalDecimal("funding"),
                openInterest = value.optionalDecimal("openInterest"),
                dayNotionalVolume = value.optionalDecimal("dayNtlVlm"),
                premium = value.optionalDecimal("premium"),
            )
        }
        return MetaAndAssetContexts(assets, contexts)
    }

    fun parseCandles(rawBody: ByteArray): List<HyperliquidCandle> =
        parse(rawBody).jsonArray.map { element ->
            val value = element.jsonObject
            HyperliquidCandle(
                coin = value.requiredString("s"),
                interval = value.requiredString("i"),
                openTime = value.requiredInstant("t"),
                closeTime = value.requiredInstant("T"),
                open = value.requiredDecimal("o"),
                close = value.requiredDecimal("c"),
                high = value.requiredDecimal("h"),
                low = value.requiredDecimal("l"),
                volume = value.requiredDecimal("v"),
                tradeCount = value.requiredLong("n"),
            )
        }

    fun parseFunding(rawBody: ByteArray): List<HyperliquidFunding> =
        parse(rawBody).jsonArray.map { element ->
            val value = element.jsonObject
            HyperliquidFunding(
                coin = value.requiredString("coin"),
                time = value.requiredInstant("time"),
                fundingRate = value.requiredDecimal("fundingRate"),
                premium = value.requiredDecimal("premium"),
            )
        }

    fun parseL2Book(rawBody: ByteArray): HyperliquidL2Book {
        val root = parse(rawBody).jsonObject
        val levels = root.requiredArray("levels")
        require(levels.size == 2) { "l2Book must contain bid and ask arrays" }
        return HyperliquidL2Book(
            coin = root.requiredString("coin"),
            time = root.requiredInstant("time"),
            bids = parseBookSide(levels[0].jsonArray),
            asks = parseBookSide(levels[1].jsonArray),
        )
    }

    fun parseElement(raw: String): JsonElement = json.parseToJsonElement(raw)

    private fun parse(rawBody: ByteArray): JsonElement =
        json.parseToJsonElement(rawBody.toString(Charsets.UTF_8))

    private fun parseBookSide(levels: JsonArray): List<HyperliquidBookLevel> =
        levels.map { element ->
            val value = element.jsonObject
            HyperliquidBookLevel(
                price = value.requiredDecimal("px"),
                size = value.requiredDecimal("sz"),
                orderCount = value.requiredInt("n"),
            )
        }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        requireNotNull(this[key]) { "Missing array '$key'" }.jsonArray

    private fun JsonObject.requiredString(key: String): String =
        requireNotNull(this[key]) { "Missing string '$key'" }.jsonPrimitive.content

    private fun JsonObject.requiredInt(key: String): Int =
        requireNotNull(this[key]) { "Missing integer '$key'" }.jsonPrimitive.int

    private fun JsonObject.requiredLong(key: String): Long =
        requireNotNull(this[key]) { "Missing long '$key'" }.jsonPrimitive.long

    private fun JsonObject.requiredInstant(key: String): Instant =
        Instant.ofEpochMilli(requiredLong(key))

    private fun JsonObject.requiredDecimal(key: String): BigDecimal =
        requiredString(key).toBigDecimal()

    private fun JsonObject.optionalDecimal(key: String): BigDecimal? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.content == "null") return null
        return primitive.content.toBigDecimal()
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    @Suppress("unused")
    private fun JsonObject.optionalInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    @Suppress("unused")
    private fun JsonObject.optionalLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
