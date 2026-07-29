package dev.marketlab.backfill

import dev.marketlab.evidence.ImmutableEvidenceStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.math.ln
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class BinanceMarketBackfill(
    private val config: BackfillConfig,
    private val client: HttpClient,
    private val store: ImmutableEvidenceStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run() {
        for (asset in ASSETS.filter { it.symbol in config.assets }) captureAsset(asset)
    }

    private suspend fun captureAsset(asset: AssetSpec) {
        val manifestPath = store.root.resolve("market/manifests/${asset.symbol}.json")
        if (Files.isRegularFile(manifestPath)) return
        val bars = mutableListOf<MarketBar15m>()
        val archives = mutableListOf<RawResponseManifest>()
        var previousClose: Double? = null
        var previousOpenTime: Long? = null
        var accumulator: BarAccumulator? = null
        var gapCount = 0
        var missingMinuteCount = 0L
        for (month in MONTHS) {
            val uri = archiveUri(asset, month)
            val retrievedAt = clock.instant()
            val checksumBytes = getWithRetry("$uri.CHECKSUM")
            store.storeObject(checksumBytes, "checksum")
            val expectedHash =
                checksumBytes.decodeToString().trim().split(Regex("\\s+")).first()
            require(DIGEST.matches(expectedHash)) { "invalid Binance checksum for $uri" }
            val bytes = getWithRetry(uri)
            val raw = store.storeObject(bytes, "zip")
            require(raw.sha256 == expectedHash) { "Binance archive checksum mismatch for $uri" }
            archives +=
                RawResponseManifest(
                    requestUri = uri,
                    retrievedAtEpochMillis = retrievedAt.toEpochMilli(),
                    responseSha256 = raw.sha256,
                    responseBytes = raw.bytes,
                )
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val entry = zip.nextEntry ?: error("empty Binance archive: $uri")
                require(!entry.isDirectory) { "Binance archive has no CSV: $uri" }
                zip.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val columns = line.split(',')
                        val openTime = columns.getOrNull(0)?.toLongOrNull() ?: return@forEach
                        val open = columns.getOrNull(1)?.toDoubleOrNull() ?: error("invalid open in $uri")
                        val close = columns.getOrNull(4)?.toDoubleOrNull() ?: error("invalid close in $uri")
                        require(open > 0.0 && close > 0.0)
                        if (openTime >= config.endExclusive.toEpochMilli()) return@forEach
                        previousOpenTime?.let { prior ->
                            require(openTime > prior) {
                                "duplicate or reversed Binance minute data for ${asset.symbol}"
                            }
                            if (openTime != prior + MINUTE_MILLIS) {
                                accumulator?.finish(asset.symbol)
                                    ?.takeIf { it.minuteCount == 15 }
                                    ?.let(bars::add)
                                accumulator = null
                                previousClose = null
                                gapCount += 1
                                missingMinuteCount +=
                                    (openTime - prior) / MINUTE_MILLIS - 1L
                            }
                        }
                        val priorClose = previousClose
                        previousOpenTime = openTime
                        previousClose = close
                        if (openTime < config.start.toEpochMilli()) return@forEach
                        val bucket = openTime - Math.floorMod(openTime, BAR_MILLIS)
                        if (accumulator?.openTime != bucket) {
                            accumulator?.finish(asset.symbol)
                                ?.takeIf { it.minuteCount == 15 }
                                ?.let(bars::add)
                            accumulator = BarAccumulator(bucket, open)
                        }
                        checkNotNull(accumulator).include(
                            close = close,
                            squaredReturn =
                                priorClose?.let { prior -> ln(close / prior).let { it * it } } ?: 0.0,
                        )
                    }
                }
            }
        }
        accumulator?.finish(asset.symbol)
            ?.takeIf { it.minuteCount == 15 }
            ?.let(bars::add)
        require(bars.isNotEmpty()) { "no Binance bars for ${asset.symbol}" }
        require(bars.all { it.minuteCount == 15 }) {
            "incomplete fifteen-minute Binance bars for ${asset.symbol}"
        }
        val outputBytes =
            bars.joinToString(separator = "\n", postfix = "\n") { JSON.encodeToString(it) }
                .toByteArray()
        val output = store.storeObject(outputBytes, "jsonl")
        val manifest =
            MarketAssetManifest(
                symbol = asset.symbol,
                archiveObjects = archives,
                outputUri = output.uri,
                outputSha256 = output.sha256,
                barCount = bars.size,
                gapCount = gapCount,
                missingMinuteCount = missingMinuteCount,
                firstOpenTimeEpochMillis = bars.first().openTimeEpochMillis,
                lastCloseTimeExclusiveEpochMillis = bars.last().closeTimeExclusiveEpochMillis,
                completedAtEpochMillis = clock.instant().toEpochMilli(),
            )
        store.publish(JSON.encodeToString(manifest).toByteArray(), manifestPath)
        println(
            "market ${asset.symbol} archives=${archives.size} bars=${bars.size} " +
                "gaps=$gapCount missingMinutes=$missingMinuteCount",
        )
    }

    private fun archiveUri(asset: AssetSpec, month: YearMonth): String {
        val name = "${asset.binanceSymbol}-1m-$month.zip"
        return "$ROOT/${asset.binanceSymbol}/1m/$name"
    }

    private suspend fun getWithRetry(uri: String): ByteArray {
        var delayMillis = 1_000L
        repeat(MAXIMUM_ATTEMPTS - 1) {
            try {
                return client.get(uri).body()
            } catch (_: Exception) {
                delay(delayMillis)
                delayMillis = (delayMillis * 2).coerceAtMost(30_000L)
            }
        }
        return client.get(uri).body()
    }

    private data class BarAccumulator(
        val openTime: Long,
        val open: Double,
        var close: Double = open,
        var realizedVariance: Double = 0.0,
        var count: Int = 0,
    ) {
        fun include(close: Double, squaredReturn: Double) {
            this.close = close
            realizedVariance += squaredReturn
            count += 1
        }

        fun finish(symbol: String) =
            MarketBar15m(
                symbol = symbol,
                openTimeEpochMillis = openTime,
                closeTimeExclusiveEpochMillis = openTime + BAR_MILLIS,
                open = open,
                close = close,
                realizedVariance1m = realizedVariance,
                minuteCount = count,
            )
    }

    private companion object {
        const val ROOT = "https://data.binance.vision/data/futures/um/monthly/klines"
        const val MAXIMUM_ATTEMPTS = 5
        const val MINUTE_MILLIS = 60_000L
        const val BAR_MILLIS = 15L * MINUTE_MILLIS
        val DIGEST = Regex("[0-9a-f]{64}")
        val JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }
    }
}
