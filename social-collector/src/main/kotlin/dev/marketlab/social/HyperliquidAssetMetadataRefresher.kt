package dev.marketlab.social

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class HyperliquidAssetMetadataRefresher(
    private val client: HttpClient,
    private val resolver: CryptoEntityResolver,
    private val refreshMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run(emit: suspend (SourceItem) -> Unit): Nothing {
        while (true) {
            try {
                val response =
                    client.post(INFO_URI) {
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("type", "meta") }.toString())
                    }.body<String>()
                val universe =
                    Json.parseToJsonElement(response)
                        .jsonObject["universe"]
                        ?.jsonArray
                        .orEmpty()
                val dynamic =
                    universe.mapNotNull { item ->
                        val symbol =
                            item.jsonObject["name"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.takeIf { SYMBOL.matches(it) }
                                ?: return@mapNotNull null
                        AssetAlias(
                            symbol = symbol,
                            names = emptySet(),
                            ambiguousBareSymbol = true,
                        )
                    }
                resolver.replaceAliases(
                    (CryptoEntityResolver.DEFAULT_ALIASES + dynamic)
                        .distinctBy(AssetAlias::symbol),
                )
                emit(
                    SourceItem.Ready(
                        source = SOURCE,
                        observedAt = clock.instant(),
                        sourceUri = java.net.URI.create(INFO_URI),
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = SOURCE,
                        observedAt = clock.instant(),
                        sourceUri = java.net.URI.create(INFO_URI),
                        issueKind = "SOURCE_GAP",
                        severity = "WARNING",
                        message = "Hyperliquid universe refresh failed: ${exception.javaClass.simpleName}",
                    ),
                )
            }
            delay(refreshMillis)
        }
    }

    private companion object {
        const val INFO_URI = "https://api.hyperliquid.xyz/info"
        const val SOURCE = "hyperliquid-asset-metadata"
        val SYMBOL = Regex("[A-Z0-9][A-Z0-9._:-]{0,63}")
    }
}
