package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class MastodonHashtagAdapter(
    private val client: HttpClient,
    private val endpoints: List<URI>,
    private val pollIntervalMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) : InformationSourceAdapter {
    override val source: String = "mastodon-public-sample"

    override suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing =
        coroutineScope {
            endpoints.forEach { endpoint ->
                launch { collectEndpoint(endpoint, emit) }
            }
            kotlinx.coroutines.awaitCancellation()
        }

    private suspend fun collectEndpoint(
        endpoint: URI,
        emit: suspend (SourceItem) -> Unit,
    ): Nothing {
        val seen = LinkedHashSet<String>()
        val order = ArrayDeque<String>()
        var ready = false
        var previousRawHash: String? = null
        while (true) {
            try {
                val bytes = client.get(endpoint.toASCIIString()).body<ByteArray>()
                if (!ready) {
                    emit(SourceItem.Ready(source, clock.instant(), endpoint))
                    ready = true
                }
                val receivedAt = clock.instant()
                val rawHash =
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                if (rawHash != previousRawHash) {
                    emit(
                        SourceItem.RawCapture(
                            source = source,
                            observedAt = receivedAt,
                            sourceUri = endpoint,
                            mediaType = "application/json",
                            rawBody = bytes,
                        ),
                    )
                    previousRawHash = rawHash
                }
                val statuses = JSON.parseToJsonElement(bytes.decodeToString()).jsonArray
                statuses.forEach { element ->
                    val status = element.jsonObject
                    val id = status["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (!seen.add(id)) return@forEach
                    order.addLast(id)
                    while (seen.size > 20_000) seen.remove(order.removeFirst())
                    val content =
                        status["content"]?.jsonPrimitive?.contentOrNull
                            ?.let(InformationText::htmlToText)
                            ?.takeIf(String::isNotBlank) ?: return@forEach
                    val created =
                        status["created_at"]?.jsonPrimitive?.contentOrNull
                            ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
                            ?: receivedAt
                    val account = status["account"]?.jsonObject
                    emit(
                        SourceItem.Event(
                            source = source,
                            observedAt = receivedAt,
                            sourceUri = endpoint,
                            sourceEventId = "${endpoint.host}:$id",
                            channel = InformationChannel.SOCIAL,
                            mutation = InformationMutation.CREATE,
                            eventTime = created,
                            authorId =
                                account?.get("acct")?.jsonPrimitive?.contentOrNull,
                            language = status["language"]?.jsonPrimitive?.contentOrNull,
                            text = content,
                            canonicalUrl =
                                status["url"]?.jsonPrimitive?.contentOrNull,
                            rawBody = element.toString().toByteArray(Charsets.UTF_8),
                        ),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = source,
                        observedAt = clock.instant(),
                        sourceUri = endpoint,
                        issueKind = "SOURCE_GAP",
                        severity = "INFO",
                        message = "Mastodon robustness feed unavailable: ${exception.javaClass.simpleName}",
                    ),
                )
            }
            delay(pollIntervalMillis)
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
