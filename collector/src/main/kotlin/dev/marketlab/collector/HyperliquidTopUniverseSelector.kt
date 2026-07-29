package dev.marketlab.collector

import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.data.HyperliquidUniverseMember
import dev.marketlab.contracts.data.HyperliquidUniverseSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
private data class UniverseReadyMarker(
    val schemaVersion: String = "marketlab.hyperliquid-social-universe-ready.v1",
    val snapshotUri: String,
    val snapshotSha256: String,
    val selectedAtEpochMillis: Long,
)

internal class HyperliquidTopUniverseSelector(
    private val client: HttpClient,
    private val universeRoot: Path,
    private val sourceRevision: String,
    private val clock: Clock = Clock.systemUTC(),
    private val requestSpacing: Duration = Duration.ofMillis(1_050),
) {
    suspend fun selectAndPublish(): HyperliquidUniverseSnapshot {
        val selectionStarted = clock.instant()
        val symbols = fetchSymbols()
        val observations = mutableListOf<UniverseObservation>()
        symbols.forEachIndexed { index, symbol ->
            if (index > 0) delay(requestSpacing.toMillis())
            fetchObservation(symbol, selectionStarted)?.let(observations::add)
        }
        require(observations.size >= TOP_COUNT) {
            "fewer than $TOP_COUNT Hyperliquid assets have 90 days of candle history"
        }
        val selectedAt = clock.instant()
        val effectiveTo = nextMondayUtc(selectedAt)
        val members =
            observations
                .sortedWith(
                    compareByDescending<UniverseObservation>(UniverseObservation::notional)
                        .thenBy(UniverseObservation::symbol),
                )
                .take(TOP_COUNT)
                .mapIndexed { index, value ->
                    HyperliquidUniverseMember(
                        rank = index + 1,
                        instrument = InstrumentId("hyperliquid:perpetual:${value.symbol}"),
                        symbol = value.symbol,
                        trailingThirtyDayNotional = value.notional,
                        listingAgeDays = value.listingAgeDays,
                    )
                }
        val snapshot =
            HyperliquidUniverseSnapshot(
                selectedAt = MarketTimestamp(selectedAt.toEpochMilli()),
                effectiveFrom = MarketTimestamp(selectedAt.toEpochMilli()),
                effectiveToExclusive = MarketTimestamp(effectiveTo.toEpochMilli()),
                members = members,
                sourceRevision = Sha256Digest(sourceRevision),
            )
        publish(snapshot)
        return snapshot
    }

    internal fun parseSymbols(body: String): List<String> {
        val universe =
            JSON.parseToJsonElement(body)
                .jsonObject["universe"]
                ?.jsonArray
                ?: error("Hyperliquid meta response has no universe")
        return universe
            .mapNotNull { item ->
                item.jsonObject["name"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { SYMBOL.matches(it) && it == it.uppercase() }
            }
            .distinct()
            .sorted()
    }

    internal fun parseObservation(
        symbol: String,
        body: String,
        selectedAt: Instant,
    ): UniverseObservation? {
        val candles = JSON.parseToJsonElement(body).jsonArray
        if (candles.isEmpty()) return null
        val complete =
            candles.mapNotNull { element ->
                val value = element.jsonObject
                val openTime = value.long("t") ?: return@mapNotNull null
                val closeTime = value.long("T") ?: return@mapNotNull null
                if (closeTime >= selectedAt.toEpochMilli()) return@mapNotNull null
                val volume = value.decimal("v") ?: return@mapNotNull null
                val open = value.decimal("o") ?: return@mapNotNull null
                val high = value.decimal("h") ?: return@mapNotNull null
                val low = value.decimal("l") ?: return@mapNotNull null
                val close = value.decimal("c") ?: return@mapNotNull null
                Candle(openTime, closeTime, volume, (open + high + low + close) / FOUR)
            }.sortedBy(Candle::openTime)
        val first = complete.firstOrNull() ?: return null
        val ageDays = ((selectedAt.toEpochMilli() - first.openTime) / DAY_MILLIS).toInt()
        if (ageDays < MINIMUM_LISTING_DAYS) return null
        val cutoff = selectedAt.minus(Duration.ofDays(30)).toEpochMilli()
        val eligible = complete.filter { it.closeTime > cutoff }
        if (eligible.size < 28) return null
        val notional =
            eligible.fold(BigDecimal.ZERO) { total, candle ->
                total + candle.volume.multiply(candle.typicalPrice)
            }.toDouble()
        require(notional.isFinite() && notional >= 0.0)
        return UniverseObservation(symbol, notional, ageDays)
    }

    private suspend fun fetchSymbols(): List<String> {
        val body =
            client.post(INFO_URI) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("type", "meta") }.toString())
            }.body<String>()
        return parseSymbols(body)
    }

    private suspend fun fetchObservation(
        symbol: String,
        selectedAt: Instant,
    ): UniverseObservation? {
        val body =
            client.post(INFO_URI) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("type", "candleSnapshot")
                        put(
                            "req",
                            buildJsonObject {
                                put("coin", symbol)
                                put("interval", "1d")
                                put(
                                    "startTime",
                                    selectedAt.minus(Duration.ofDays(92)).toEpochMilli(),
                                )
                                put("endTime", selectedAt.toEpochMilli())
                            },
                        )
                    }.toString(),
                )
            }.body<String>()
        return parseObservation(symbol, body, selectedAt)
    }

    private fun publish(snapshot: HyperliquidUniverseSnapshot) {
        Files.createDirectories(universeRoot)
        val bytes = JSON.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
        val week = WEEK.format(Instant.ofEpochMilli(snapshot.effectiveFrom.epochMillis))
        val directory = universeRoot.resolve("snapshots").resolve(week)
        Files.createDirectories(directory)
        val destination = directory.resolve("$hash.json")
        publishBytes(bytes, destination, replace = false)
        val marker =
            UniverseReadyMarker(
                snapshotUri = universeRoot.relativize(destination).toString(),
                snapshotSha256 = hash,
                selectedAtEpochMillis = snapshot.selectedAt.epochMillis,
            )
        publishBytes(
            JSON.encodeToString(marker).toByteArray(Charsets.UTF_8),
            universeRoot.resolve(".social-universe-ready.json"),
            replace = true,
        )
    }

    private fun publishBytes(bytes: ByteArray, destination: Path, replace: Boolean) {
        Files.createDirectories(destination.parent)
        val partial = destination.resolveSibling(".${UUID.randomUUID()}.partial")
        FileChannel.open(
            partial,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            Files.move(
                partial,
                destination,
                *if (replace) {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                },
            )
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(partial)
        }
    }

    private fun nextMondayUtc(at: Instant): Instant {
        var date = ZonedDateTime.ofInstant(at, ZoneOffset.UTC).toLocalDate().plusDays(1)
        while (date.dayOfWeek != DayOfWeek.MONDAY) date = date.plusDays(1)
        return date.atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    internal data class UniverseObservation(
        val symbol: String,
        val notional: Double,
        val listingAgeDays: Int,
    )

    private data class Candle(
        val openTime: Long,
        val closeTime: Long,
        val volume: BigDecimal,
        val typicalPrice: BigDecimal,
    )

    private companion object {
        const val INFO_URI = "https://api.hyperliquid.xyz/info"
        const val TOP_COUNT = 10
        const val MINIMUM_LISTING_DAYS = 90
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        val FOUR = BigDecimal("4")
        val SYMBOL = Regex("[A-Z0-9][A-Z0-9._:-]{0,63}")
        val WEEK: DateTimeFormatter = DateTimeFormatter.ofPattern("YYYY-'W'ww").withZone(ZoneOffset.UTC)
        val JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        fun JsonObject.long(name: String): Long? =
            this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        fun JsonObject.decimal(name: String): BigDecimal? =
            this[name]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
