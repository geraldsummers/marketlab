package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class NostrRelayAdapter(
    private val client: HttpClient,
    private val relays: List<URI>,
    private val clock: Clock = Clock.systemUTC(),
) : InformationSourceAdapter {
    override val source: String = "nostr-public-relays"

    override suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing =
        coroutineScope {
            relays.forEach { relay ->
                launch { collectRelay(relay, emit) }
            }
            kotlinx.coroutines.awaitCancellation()
        }

    private suspend fun collectRelay(relay: URI, emit: suspend (SourceItem) -> Unit): Nothing {
        var backoffMillis = 1_000L
        while (true) {
            try {
                client.webSocket(urlString = relay.toASCIIString()) {
                    val subscription = "marketlab-${UUID.randomUUID().toString().take(12)}"
                    val since = clock.instant().epochSecond
                    val request =
                        buildJsonArray {
                            add(JsonPrimitive("REQ"))
                            add(JsonPrimitive(subscription))
                            add(
                                buildJsonObject {
                                    put(
                                        "kinds",
                                        buildJsonArray {
                                            add(JsonPrimitive(1))
                                            add(JsonPrimitive(5))
                                        },
                                    )
                                    put("since", since)
                                },
                            )
                        }.toString()
                    send(Frame.Text(request))
                    emit(SourceItem.Ready(source, clock.instant(), relay))
                    backoffMillis = 1_000L
                    for (frame in incoming) {
                        val bytes =
                            when (frame) {
                                is Frame.Text -> frame.readText().toByteArray(Charsets.UTF_8)
                                is Frame.Binary -> frame.readBytes()
                                else -> continue
                            }
                        val receivedAt = clock.instant()
                        when (val parsed = parse(bytes, relay, receivedAt)) {
                            is NostrParse.Event -> emit(parsed.value)
                            is NostrParse.Invalid ->
                                emit(
                                    SourceItem.Quality(
                                        source = source,
                                        observedAt = receivedAt,
                                        sourceUri = relay,
                                        issueKind = parsed.issueKind,
                                        severity = "WARNING",
                                        message = parsed.message,
                                    ),
                                )
                            null -> Unit
                        }
                    }
                    error("Nostr relay closed normally")
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = source,
                        observedAt = clock.instant(),
                        sourceUri = relay,
                        issueKind = "SOURCE_GAP",
                        severity = "WARNING",
                        message = "Nostr relay disconnected: ${exception.javaClass.simpleName}",
                    ),
                )
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2L).coerceAtMost(30_000L)
            }
        }
    }

    internal fun parse(bytes: ByteArray, relay: URI, receivedAt: Instant): NostrParse? {
        val root =
            runCatching { JSON.parseToJsonElement(bytes.decodeToString()).jsonArray }
                .getOrNull() ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr frame is not a JSON array")
        if (root.firstOrNull()?.jsonPrimitive?.contentOrNull != "EVENT") return null
        val event = root.getOrNull(2)?.jsonObject
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr EVENT has no event object")
        val id = event.string("id")
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr event id is missing")
        val pubkey = event.string("pubkey")
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr pubkey is missing")
        val signature = event.string("sig")
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr signature is missing")
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr created_at is missing")
        val kind = event["kind"]?.jsonPrimitive?.intOrNull
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr kind is missing")
        val tags = event["tags"]?.jsonArray
            ?: return NostrParse.Invalid("SCHEMA_DRIFT", "Nostr tags are missing")
        val content = event.string("content").orEmpty()
        if (!HEX_64.matches(id) || !HEX_64.matches(pubkey)) {
            return NostrParse.Invalid("INVALID_SIGNATURE", "Nostr id/pubkey encoding is invalid")
        }
        val canonical =
            buildJsonArray {
                add(JsonPrimitive(0))
                add(JsonPrimitive(pubkey))
                add(JsonPrimitive(createdAt))
                add(JsonPrimitive(kind))
                add(tags)
                add(JsonPrimitive(content))
            }.toString().toByteArray(Charsets.UTF_8)
        if (sha256(canonical) != id) {
            return NostrParse.Invalid("INVALID_SIGNATURE", "Nostr event id does not match canonical content")
        }
        if (!Bip340Verifier.verify(pubkey, id, signature)) {
            return NostrParse.Invalid("INVALID_SIGNATURE", "Nostr BIP-340 signature is invalid")
        }
        return when (kind) {
            1 ->
                NostrParse.Event(
                    SourceItem.Event(
                        source = source,
                        observedAt = receivedAt,
                        sourceUri = relay,
                        sourceEventId = id,
                        channel = InformationChannel.SOCIAL,
                        mutation = InformationMutation.CREATE,
                        eventTime = Instant.ofEpochSecond(createdAt),
                        authorId = pubkey,
                        language = languageTag(tags),
                        text = content,
                        canonicalUrl = null,
                        rawBody = bytes,
                    ),
                )
            5 -> {
                val deleted =
                    tags
                        .mapNotNull { tag ->
                            val values = tag as? JsonArray ?: return@mapNotNull null
                            if (values.getOrNull(0)?.jsonPrimitive?.contentOrNull == "e") {
                                values.getOrNull(1)?.jsonPrimitive?.contentOrNull
                            } else {
                                null
                            }
                        }
                        .firstOrNull()
                        ?: return NostrParse.Invalid(
                            "SCHEMA_DRIFT",
                            "Nostr deletion has no referenced event",
                        )
                NostrParse.Event(
                    SourceItem.Event(
                        source = source,
                        observedAt = receivedAt,
                        sourceUri = relay,
                        sourceEventId = deleted,
                        channel = InformationChannel.SOCIAL,
                        mutation = InformationMutation.DELETE,
                        eventTime = Instant.ofEpochSecond(createdAt),
                        authorId = pubkey,
                        language = null,
                        text = null,
                        canonicalUrl = null,
                        rawBody = bytes,
                    ),
                )
            }
            else -> null
        }
    }

    private fun languageTag(tags: JsonArray): String? =
        tags
            .asSequence()
            .mapNotNull { it as? JsonArray }
            .firstOrNull {
                it.getOrNull(0)?.jsonPrimitive?.contentOrNull == "l"
            }
            ?.getOrNull(1)
            ?.jsonPrimitive
            ?.contentOrNull

    internal sealed interface NostrParse {
        data class Event(val value: SourceItem.Event) : NostrParse

        data class Invalid(val issueKind: String, val message: String) : NostrParse
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val HEX_64 = Regex("[0-9a-f]{64}")

        fun JsonObject.string(key: String): String? =
            get(key)?.jsonPrimitive?.contentOrNull

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
