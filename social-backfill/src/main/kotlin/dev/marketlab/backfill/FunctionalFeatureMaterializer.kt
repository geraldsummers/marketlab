package dev.marketlab.backfill

import dev.marketlab.evidence.ImmutableEvidenceStore
import dev.marketlab.historical.HistoricalStudyLock
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TreeMap
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class FunctionalFeatureMaterializer(
    private val config: FunctionalFeatureConfig,
    private val store: ImmutableEvidenceStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun run() {
        val manifestPath = store.root.resolve("functional/features/manifest.json")
        if (Files.isRegularFile(manifestPath)) {
            println("functional features already complete: $manifestPath")
            return
        }
        val study = HistoricalStudyLock.read(config.programLock)
        require(config.start == study.startInclusive && config.endExclusive == study.endExclusive) {
            "functional feature period must match the acquired study"
        }
        val featureLockHash = store.sha256(config.featureLock)
        val frozenFeatureLock = store.root.resolve("functional/feature-lock.json")
        if (Files.isRegularFile(frozenFeatureLock)) {
            require(store.sha256(frozenFeatureLock) == featureLockHash) { "functional feature lock changed after materialization" }
        } else {
            store.publish(Files.readAllBytes(config.featureLock), frozenFeatureLock)
        }
        val input = load(study)
        val rows = materialize(study, input.social, input.market)
        val bytes = rows.joinToString("\n", postfix = "\n") { JSON.encodeToString(it) }.toByteArray()
        val output = store.storeObject(bytes, "jsonl")
        val manifest =
            FunctionalFeatureManifest(
                programLockSha256 = store.sha256(config.programLock),
                inputManifestSetSha256 = input.manifestSetSha256,
                featureLockSha256 = featureLockHash,
                startInclusive = config.start.toString(),
                featureStartInclusive = config.start.plusSeconds(30L * 24L * 60L * 60L).toString(),
                endExclusive = config.endExclusive.toString(),
                rowCount = rows.size.toLong(),
                rowsBySymbol = rows.groupingBy(FunctionalFeatureRow::symbol).eachCount().mapValues { it.value.toLong() },
                outputUri = output.uri,
                outputSha256 = output.sha256,
                completedAtEpochMillis = clock.millis(),
            )
        store.publish(JSON.encodeToString(manifest).toByteArray(), manifestPath)
        println("functional features complete rows=${rows.size} sha256=${output.sha256}")
    }

    private fun load(study: HistoricalStudyLock): Acquisition {
        val social = linkedMapOf<String, List<SocialObservation>>()
        val market = linkedMapOf<String, TreeMap<Long, MarketBar15m>>()
        val hashes = mutableListOf<String>()
        study.assets.forEach { asset ->
            val marketPath = store.root.resolve("market/manifests/${asset.symbol}.json")
            require(Files.isRegularFile(marketPath)) { "missing market manifest for ${asset.symbol}" }
            hashes += "market/${asset.symbol}:${store.sha256(marketPath)}"
            val marketManifest = JSON.decodeFromString<MarketAssetManifest>(Files.readString(marketPath))
            market[asset.symbol] =
                verified(marketManifest.outputUri, marketManifest.outputSha256)
                    .decodeToString().lineSequence().filter(String::isNotBlank)
                    .map { JSON.decodeFromString<MarketBar15m>(it) }
                    .associateByTo(TreeMap(), MarketBar15m::openTimeEpochMillis)

            val observations = mutableListOf<SocialObservation>()
            dates(config.start, config.endExclusive).forEach { date ->
                val socialPath = store.root.resolve("social/manifests/${asset.symbol}/$date.json")
                require(Files.isRegularFile(socialPath)) { "missing social manifest for ${asset.symbol} $date" }
                hashes += "social/${asset.symbol}/$date:${store.sha256(socialPath)}"
                val manifest = JSON.decodeFromString<SocialDayManifest>(Files.readString(socialPath))
                observations +=
                    verified(manifest.outputUri, manifest.outputSha256)
                        .decodeToString().lineSequence().filter(String::isNotBlank)
                        .map { JSON.decodeFromString<SocialObservation>(it) }
                        .toList()
            }
            social[asset.symbol] = filterSocial(observations)
        }
        return Acquisition(social, market, store.sha256(hashes.sorted().joinToString("\n").toByteArray()))
    }

    private fun verified(uri: String, hash: String): ByteArray {
        val path = store.root.resolve(uri).normalize()
        require(path.startsWith(store.root.normalize()) && Files.isRegularFile(path)) { "invalid object URI: $uri" }
        require(store.sha256(path) == hash) { "object hash mismatch: $uri" }
        return Files.readAllBytes(path)
    }

    private fun filterSocial(rows: List<SocialObservation>): List<SocialObservation> {
        val lastByText = mutableMapOf<String, Long>()
        val authorCounts = mutableMapOf<String, Int>()
        return rows.sortedWith(compareBy(SocialObservation::indexedAtAvailabilityProxyEpochMillis, SocialObservation::sourceEventId))
            .filter { row ->
                val at = row.indexedAtAvailabilityProxyEpochMillis
                val prior = lastByText[row.textSha256]
                if (prior != null && at - prior < DAY) return@filter false
                lastByText[row.textSha256] = at
                val key = "${row.authorIdSha256}:${at / HOUR}"
                val count = (authorCounts[key] ?: 0) + 1
                authorCounts[key] = count
                count <= 3
            }
    }

    private fun materialize(
        study: HistoricalStudyLock,
        social: Map<String, List<SocialObservation>>,
        market: Map<String, TreeMap<Long, MarketBar15m>>,
    ): List<FunctionalFeatureRow> {
        val btc = market.getValue("BTC")
        val baseRows = study.assets.flatMap { asset ->
            val bars = market.getValue(asset.symbol)
            val observations = social.getValue(asset.symbol)
            val byBucket = observations.groupBy { bucket(it.indexedAtAvailabilityProxyEpochMillis, QUARTER_HOUR) }
            val byHour = observations.groupBy { bucket(it.indexedAtAvailabilityProxyEpochMillis, HOUR) }
            val eventTimes = observations.map(SocialObservation::indexedAtAvailabilityProxyEpochMillis).sorted()
            val output = mutableListOf<FunctionalFeatureRow>()
            var decision = config.start.toEpochMilli() + 30L * DAY
            var eventIndex = 0
            var latestEvent: Long? = null
            while (decision < config.endExclusive.toEpochMilli()) {
                while (eventIndex < eventTimes.size && eventTimes[eventIndex] <= decision) {
                    latestEvent = eventTimes[eventIndex++]
                }
                val latest = bars[decision - QUARTER_HOUR]
                val btcLatest = btc[decision - QUARTER_HOUR]
                if (latest != null && btcLatest != null) {
                    val trailing1h = completeVariance(bars, decision - HOUR, decision, 4)
                    val trailing24h = completeVariance(bars, decision - DAY, decision, 96)
                    if (trailing1h == null || trailing24h == null) {
                        decision += QUARTER_HOUR
                        continue
                    }
                    val prior15m = byBucket[decision - QUARTER_HOUR].orEmpty()
                    val priorHour = windowRows(byBucket, decision, 4)
                    val prior6h = windowRows(byBucket, decision, 24)
                    val prior24h = windowRows(byBucket, decision, 96)
                    val hourlyHistory = (2..721).map { byHour[decision - it * HOUR].orEmpty().size.toDouble() }
                    val hourCount = priorHour.size.toDouble()
                    val hourMean = hourlyHistory.average()
                    val hourSd = standardDeviation(hourlyHistory)
                    val instant = Instant.ofEpochMilli(decision).atZone(ZoneOffset.UTC)
                    val features =
                        linkedMapOf(
                            "post_count_15m" to prior15m.size.toDouble(),
                            "post_count_1h" to hourCount,
                            "post_count_6h" to prior6h.size.toDouble(),
                            "post_count_24h" to prior24h.size.toDouble(),
                            "has_posts_15m" to if (prior15m.isEmpty()) 0.0 else 1.0,
                            "source_count" to 1.0,
                            "sources_with_posts_15m" to if (prior15m.isEmpty()) 0.0 else 1.0,
                            "unique_authors_1h" to priorHour.map(SocialObservation::authorIdSha256).distinct().size.toDouble(),
                            "unique_authors_24h" to prior24h.map(SocialObservation::authorIdSha256).distinct().size.toDouble(),
                            "attention_burst_30d" to if (hourSd == 0.0) 0.0 else (hourCount - hourMean) / hourSd,
                            "post_count_change_1h" to (hourCount - byHour[decision - 2 * HOUR].orEmpty().size.toDouble()),
                            "minutes_since_post" to ((decision - (latestEvent ?: config.start.toEpochMilli())) / 60_000.0).coerceIn(0.0, 43_200.0),
                            "latest_return" to barReturn(latest),
                            "btc_latest_return" to barReturn(btcLatest),
                            "log_rv_1h" to ln(trailing1h.coerceAtLeast(EPSILON)),
                            "log_rv_24h" to ln(trailing24h.coerceAtLeast(EPSILON)),
                            "hour_sin" to sin(2.0 * Math.PI * instant.hour / 24.0),
                            "hour_cos" to cos(2.0 * Math.PI * instant.hour / 24.0),
                            "day_sin" to sin(2.0 * Math.PI * instant.dayOfWeek.value / 7.0),
                            "day_cos" to cos(2.0 * Math.PI * instant.dayOfWeek.value / 7.0),
                        ).apply {
                            (1..16).forEach { lag ->
                                put("post_count_15m_lag_$lag", byBucket[decision - lag * QUARTER_HOUR].orEmpty().size.toDouble())
                            }
                        }
                    val target15m = bars[decision]?.let(::barReturn)
                    val target1h = completeVariance(bars, decision, decision + HOUR, 4)
                    val daily = decision % DAY == 0L
                    output +=
                        FunctionalFeatureRow(
                            rowId = "${asset.symbol}:$decision",
                            decisionTimeEpochMillis = decision,
                            symbol = asset.symbol,
                            features = features,
                            next15mReturn = target15m,
                            next1hRealizedVariance = target1h,
                            next1dReturn = if (daily) dailyReturn(bars, decision) else null,
                            next1dRealizedVariance = if (daily) completeVariance(bars, decision, decision + DAY, 96) else null,
                        )
                }
                decision += QUARTER_HOUR
            }
            output
        }
        return baseRows.groupBy(FunctionalFeatureRow::decisionTimeEpochMillis).values.flatMap { decisionRows ->
            val ordered = decisionRows.sortedWith(compareBy({ it.features.getValue("post_count_1h") }, { it.symbol }))
            ordered.mapIndexed { index, row ->
                val rank = if (ordered.size == 1) 0.5 else index.toDouble() / (ordered.size - 1)
                row.copy(features = row.features + ("cross_section_attention_rank" to rank))
            }
        }.sortedWith(compareBy(FunctionalFeatureRow::decisionTimeEpochMillis, FunctionalFeatureRow::symbol))
    }

    private fun windowRows(byBucket: Map<Long, List<SocialObservation>>, decision: Long, buckets: Int) =
        (1..buckets).flatMap { byBucket[decision - it * QUARTER_HOUR].orEmpty() }

    private fun completeVariance(bars: TreeMap<Long, MarketBar15m>, start: Long, end: Long, expected: Int): Double? {
        val selected = bars.subMap(start, true, end, false).values
        return selected.takeIf { it.size == expected }?.sumOf(MarketBar15m::realizedVariance1m)
    }

    private fun dailyReturn(bars: TreeMap<Long, MarketBar15m>, start: Long): Double? {
        val selected = bars.subMap(start, true, start + DAY, false).values.toList()
        return selected.takeIf { it.size == 96 }?.let { ln(it.last().close / it.first().open) }
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun barReturn(bar: MarketBar15m) = ln(bar.close / bar.open)
    private fun bucket(value: Long, size: Long) = Math.floorDiv(value - 1L, size) * size

    private data class Acquisition(
        val social: Map<String, List<SocialObservation>>,
        val market: Map<String, TreeMap<Long, MarketBar15m>>,
        val manifestSetSha256: String,
    )

    private companion object {
        const val QUARTER_HOUR = 15L * 60L * 1_000L
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
        const val EPSILON = 1.0e-12
        val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
    }
}
