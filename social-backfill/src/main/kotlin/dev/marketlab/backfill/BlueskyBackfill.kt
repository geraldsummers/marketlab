package dev.marketlab.backfill

import dev.marketlab.sentiment.FrozenLanguageDetector
import dev.marketlab.sentiment.OnnxSentimentModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class BlueskyBackfill(
    private val config: BackfillConfig,
    private val client: HttpClient,
    private val store: ArtifactStore,
    private val model: OnnxSentimentModel,
    private val languageDetector: FrozenLanguageDetector,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run() {
        for (asset in ASSETS.filter { it.symbol in config.assets }) {
            for (date in dates(config.start, config.endExclusive)) {
                captureDay(asset, date)
            }
        }
    }

    private suspend fun captureDay(asset: AssetSpec, date: LocalDate) {
        val manifestPath = store.root.resolve("social/manifests/${asset.symbol}/$date.json")
        if (Files.isRegularFile(manifestPath)) return
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val observations = linkedMapOf<String, SocialObservation>()
        val responses = mutableListOf<RawResponseManifest>()
        val windows = ArrayDeque<SearchWindow>()
        val initialWindowMillis =
            if (asset.symbol in HIGH_VOLUME_SYMBOLS) HIGH_VOLUME_WINDOW_MILLIS else STANDARD_WINDOW_MILLIS
        var windowStart = start
        while (windowStart < end) {
            val windowEnd = minOf(windowStart.plusMillis(initialWindowMillis), end)
            windows.add(SearchWindow(windowStart, windowEnd))
            windowStart = windowEnd
        }
        var requests = 0
        while (windows.isNotEmpty()) {
            require(requests++ < MAXIMUM_WINDOWS_PER_DAY) {
                "Bluesky window subdivision exceeded reviewed bound for ${asset.symbol} $date"
            }
            val window = windows.removeFirst()
            val uri =
                URLBuilder(ENDPOINT).apply {
                    parameters.append("q", asset.query)
                    parameters.append("sort", "latest")
                    parameters.append("since", window.start.toString())
                    parameters.append("until", window.endExclusive.toString())
                    parameters.append("limit", PAGE_SIZE.toString())
                }.buildString()
            val retrievedAt = clock.instant()
            val bytes = getWithRetry(uri)
            val raw = store.storeObject(bytes, "json")
            responses +=
                RawResponseManifest(
                    requestUri = uri,
                    retrievedAtEpochMillis = retrievedAt.toEpochMilli(),
                    responseSha256 = raw.sha256,
                    responseBytes = raw.bytes,
                )
            val root = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
            val posts = root["posts"]?.jsonArray.orEmpty()
            if (posts.size >= SATURATION_THRESHOLD) {
                val durationMillis = Duration.between(window.start, window.endExclusive).toMillis()
                require(durationMillis > MINIMUM_WINDOW_MILLIS) {
                    "Bluesky returned at least $SATURATION_THRESHOLD posts inside the minimum window " +
                        "${window.start}..${window.endExclusive} for ${asset.symbol}; " +
                        "completeness cannot be established"
                }
                val midpoint = window.start.plusMillis(durationMillis / 2)
                require(midpoint > window.start && midpoint < window.endExclusive)
                windows.addFirst(SearchWindow(midpoint, window.endExclusive))
                windows.addFirst(SearchWindow(window.start, midpoint))
                delay(REQUEST_SPACING_MILLIS)
                continue
            }
            posts.forEach { element ->
                parse(asset, element.jsonObject, retrievedAt, raw.sha256)?.let { observation ->
                    observations.putIfAbsent(observation.sourceEventId, observation)
                }
            }
            if (windows.isNotEmpty()) delay(REQUEST_SPACING_MILLIS)
        }
        val outputBytes =
            observations.values
                .sortedWith(
                    compareBy(
                        SocialObservation::indexedAtAvailabilityProxyEpochMillis,
                        SocialObservation::sourceEventId,
                    ),
                )
                .joinToString(separator = "\n", postfix = "\n") { JSON.encodeToString(it) }
                .toByteArray()
        val output = store.storeObject(outputBytes, "jsonl")
        val manifest =
            SocialDayManifest(
                symbol = asset.symbol,
                date = date.toString(),
                query = asset.query,
                initialWindowMillis = initialWindowMillis,
                saturationThreshold = SATURATION_THRESHOLD,
                cursorPaginationSupported = false,
                responseObjects = responses,
                outputUri = output.uri,
                outputSha256 = output.sha256,
                observationCount = observations.size,
                completedAtEpochMillis = clock.instant().toEpochMilli(),
            )
        store.publish(
            JSON.encodeToString(manifest).toByteArray(),
            manifestPath,
        )
        println(
            "social ${asset.symbol} $date windows=${responses.size} observations=${observations.size}",
        )
    }

    private fun parse(
        asset: AssetSpec,
        post: kotlinx.serialization.json.JsonObject,
        retrievedAt: Instant,
        rawHash: String,
    ): SocialObservation? {
        val uri = post["uri"]?.jsonPrimitive?.contentOrNull ?: return null
        val author = post["author"]?.jsonObject?.get("did")?.jsonPrimitive?.contentOrNull ?: return null
        val record = post["record"]?.jsonObject ?: return null
        val text = record["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        if (!asset.match.containsMatchIn(text)) return null
        val createdAt =
            record["createdAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: return null
        val indexedAt =
            post["indexedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: return null
        if (indexedAt < config.start || indexedAt >= config.endExclusive) return null
        val languages =
            record["langs"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        val language = languages.firstOrNull()
        if (!languageDetector.acceptsEnglish(language, text)) return null
        val score = model.score(uri, text)
        return SocialObservation(
            symbol = asset.symbol,
            sourceEventId = uri,
            authorIdSha256 = store.sha256(author.toByteArray()),
            createdAtEpochMillis = createdAt.toEpochMilli(),
            indexedAtAvailabilityProxyEpochMillis = indexedAt.toEpochMilli(),
            retrievedAtEpochMillis = retrievedAt.toEpochMilli(),
            textSha256 = store.sha256(text.toByteArray()),
            language = language,
            negativeProbability = score.negativeProbability,
            neutralProbability = score.neutralProbability,
            positiveProbability = score.positiveProbability,
            polarity = score.polarity,
            rawResponseSha256 = rawHash,
        )
    }

    private suspend fun getWithRetry(uri: String): ByteArray {
        var delayMillis = 5_000L
        repeat(MAXIMUM_ATTEMPTS - 1) {
            try {
                return client.get(uri).body()
            } catch (failure: Exception) {
                if (failure is ClientRequestException &&
                    failure.response.status !in
                    setOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)
                ) {
                    throw failure
                }
                delay(delayMillis)
                delayMillis = (delayMillis * 2).coerceAtMost(MAXIMUM_RETRY_DELAY_MILLIS)
            }
        }
        return client.get(uri).body()
    }

    private companion object {
        const val ENDPOINT = "https://api.bsky.app/xrpc/app.bsky.feed.searchPosts"
        const val PAGE_SIZE = 100
        const val SATURATION_THRESHOLD = 90
        const val MAXIMUM_WINDOWS_PER_DAY = 10_000
        const val MINIMUM_WINDOW_MILLIS = 1_000L
        const val MAXIMUM_ATTEMPTS = 10
        const val MAXIMUM_RETRY_DELAY_MILLIS = 5L * 60L * 1_000L
        const val REQUEST_SPACING_MILLIS = 1_000L
        const val HIGH_VOLUME_WINDOW_MILLIS = 30L * 60L * 1_000L
        const val STANDARD_WINDOW_MILLIS = 60L * 60L * 1_000L
        val HIGH_VOLUME_SYMBOLS = setOf("BTC", "ETH", "SOL", "XRP")
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
    }

    private data class SearchWindow(
        val start: Instant,
        val endExclusive: Instant,
    )
}
