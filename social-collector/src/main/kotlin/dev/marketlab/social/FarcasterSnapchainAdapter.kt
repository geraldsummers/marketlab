package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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
import kotlinx.serialization.json.longOrNull

/**
 * Credential-free reader for a locally operated Snapchain node. Snapchain has
 * verified each merged message before exposing it; the signed envelope and the
 * exact HTTP response are both retained.
 */
internal class FarcasterSnapchainAdapter(
    private val client: HttpClient,
    private val eventsUri: URI,
    private val pollIntervalMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) : InformationSourceAdapter {
    override val source: String = "farcaster-snapchain"

    override suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing {
        var nextEventId: Long? = null
        var ready = false
        while (true) {
            try {
                val uri =
                    URI.create(
                        eventsUri.toASCIIString() +
                            nextEventId?.let { id ->
                                "${if (eventsUri.query == null) "?" else "&"}from_event_id=$id"
                            }.orEmpty(),
                    )
                val bytes = client.get(uri.toASCIIString()).body<ByteArray>()
                val receivedAt = clock.instant()
                emit(
                    SourceItem.RawCapture(
                        source = source,
                        observedAt = receivedAt,
                        sourceUri = uri,
                        mediaType = "application/json",
                        rawBody = bytes,
                    ),
                )
                val page = parsePage(bytes, uri, receivedAt)
                page.events.forEach { emit(it) }
                nextEventId = maxOf(nextEventId ?: 0L, page.nextEventId)
                if (!ready) {
                    emit(SourceItem.Ready(source, receivedAt, eventsUri))
                    ready = true
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = source,
                        observedAt = clock.instant(),
                        sourceUri = eventsUri,
                        issueKind = "SOURCE_GAP",
                        severity = "WARNING",
                        message = "Farcaster Snapchain poll failed: ${exception.javaClass.simpleName}",
                    ),
                )
            }
            delay(pollIntervalMillis)
        }
    }

    internal fun parsePage(
        bytes: ByteArray,
        uri: URI,
        receivedAt: Instant,
    ): FarcasterPage {
        val root = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
        val next =
            root["nextPageEventId"]?.jsonPrimitive?.longOrNull
                ?: root["next_page_event_id"]?.jsonPrimitive?.longOrNull
                ?: 0L
        val events =
            root["events"]?.jsonArray.orEmpty().mapNotNull { element ->
                val event = element.jsonObject
                if (event.string("type") != "HUB_EVENT_TYPE_MERGE_MESSAGE") return@mapNotNull null
                val body =
                    event["mergeMessageBody"]?.jsonObject
                        ?: event["merge_message_body"]?.jsonObject
                        ?: return@mapNotNull null
                val message = body["message"]?.jsonObject ?: return@mapNotNull null
                val data = message["data"]?.jsonObject ?: return@mapNotNull null
                val type = data.string("type") ?: return@mapNotNull null
                val fid = data["fid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val timestamp = data["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val hash = message.string("hash")?.removePrefix("0x") ?: return@mapNotNull null
                if (!HASH.matches(hash)) return@mapNotNull null
                val mutation =
                    when (type) {
                        "MESSAGE_TYPE_CAST_ADD" -> InformationMutation.CREATE
                        "MESSAGE_TYPE_CAST_REMOVE" -> InformationMutation.DELETE
                        else -> return@mapNotNull null
                    }
                val text =
                    if (mutation == InformationMutation.CREATE) {
                        data["castAddBody"]?.jsonObject?.string("text")
                            ?: data["cast_add_body"]?.jsonObject?.string("text")
                            ?: return@mapNotNull null
                    } else {
                        null
                    }
                SourceItem.Event(
                    source = source,
                    observedAt = receivedAt,
                    sourceUri = uri,
                    sourceEventId = hash.lowercase(),
                    channel = InformationChannel.SOCIAL,
                    mutation = mutation,
                    eventTime = FARCASTER_EPOCH.plusSeconds(timestamp),
                    authorId = fid.toString(),
                    language = null,
                    text = text,
                    canonicalUrl = null,
                    rawBody = element.toString().toByteArray(Charsets.UTF_8),
                )
            }
        return FarcasterPage(next, events)
    }

    internal data class FarcasterPage(
        val nextEventId: Long,
        val events: List<SourceItem.Event>,
    )

    private companion object {
        val FARCASTER_EPOCH: Instant = Instant.parse("2021-01-01T00:00:00Z")
        val HASH = Regex("[0-9a-fA-F]{40,128}")
        val JSON = Json { ignoreUnknownKeys = true }

        fun JsonObject.string(name: String): String? =
            this[name]?.jsonPrimitive?.contentOrNull
    }
}
