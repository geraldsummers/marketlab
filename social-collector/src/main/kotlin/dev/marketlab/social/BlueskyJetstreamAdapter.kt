package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.net.URI
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class BlueskyJetstreamAdapter(
    private val client: HttpClient,
    private val endpoints: List<URI>,
    private val clock: Clock = Clock.systemUTC(),
) : InformationSourceAdapter {
    override val source: String = "bluesky-jetstream"

    override suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing {
        var endpointIndex = 0
        var backoffMillis = 1_000L
        while (true) {
            val endpoint = endpoints[endpointIndex % endpoints.size]
            endpointIndex++
            val uri =
                URI.create(
                    endpoint.toASCIIString() +
                        if (endpoint.query == null) {
                            "?wantedCollections=app.bsky.feed.post"
                        } else {
                            "&wantedCollections=app.bsky.feed.post"
                        },
                )
            try {
                client.webSocket(urlString = uri.toASCIIString()) {
                    emit(SourceItem.Ready(source, clock.instant(), uri))
                    backoffMillis = 1_000L
                    for (frame in incoming) {
                        val bytes =
                            when (frame) {
                                is Frame.Text -> frame.readText().toByteArray(Charsets.UTF_8)
                                is Frame.Binary -> frame.readBytes()
                                else -> continue
                            }
                        parse(bytes, uri, clock.instant())?.let { emit(it) }
                    }
                    error("Bluesky Jetstream closed normally")
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = source,
                        observedAt = clock.instant(),
                        sourceUri = uri,
                        issueKind = "SOURCE_GAP",
                        severity = "WARNING",
                        message = "Jetstream disconnected: ${exception.javaClass.simpleName}",
                    ),
                )
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2L).coerceAtMost(30_000L)
            }
        }
    }

    internal fun parse(bytes: ByteArray, uri: URI, receivedAt: Instant): SourceItem.Event? {
        val root = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
        if (root.string("kind") != "commit") return null
        val did = root.string("did") ?: return null
        val timeUs = root.long("time_us") ?: return null
        val commit = root["commit"]?.jsonObject ?: return null
        if (commit.string("collection") != "app.bsky.feed.post") return null
        val rkey = commit.string("rkey") ?: return null
        val operation =
            when (commit.string("operation")) {
                "create" -> InformationMutation.CREATE
                "update" -> InformationMutation.UPDATE
                "delete" -> InformationMutation.DELETE
                else -> return null
            }
        val record = commit["record"] as? JsonObject
        val text = if (operation == InformationMutation.DELETE) null else record?.string("text") ?: return null
        val createdAt =
            record
                ?.string("createdAt")
                ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
                ?: Instant.ofEpochMilli(timeUs / 1_000L)
        val language =
            record
                ?.get("langs")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.contentOrNull
        return SourceItem.Event(
            source = source,
            observedAt = receivedAt,
            sourceUri = uri,
            sourceEventId = "$did/app.bsky.feed.post/$rkey",
            channel = InformationChannel.SOCIAL,
            mutation = operation,
            eventTime = createdAt,
            authorId = did,
            language = language,
            text = text,
            canonicalUrl = "https://bsky.app/profile/$did/post/$rkey",
            rawBody = bytes,
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun JsonObject.string(key: String): String? =
            get(key)?.jsonPrimitive?.contentOrNull

        fun JsonObject.long(key: String): Long? =
            string(key)?.toLongOrNull()
    }
}
