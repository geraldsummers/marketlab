package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element

internal class RssNewsAdapter(
    private val client: HttpClient,
    private val feeds: List<URI>,
    private val pollIntervalMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) : InformationSourceAdapter {
    override val source: String = "official-crypto-rss"

    override suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing =
        coroutineScope {
            feeds.forEach { feed ->
                launch { collectFeed(feed, emit) }
            }
            kotlinx.coroutines.awaitCancellation()
        }

    private suspend fun collectFeed(feed: URI, emit: suspend (SourceItem) -> Unit): Nothing {
        val seen = BoundedIds(20_000)
        var previousRawHash: String? = null
        var ready = false
        while (true) {
            try {
                val bytes = client.get(feed.toASCIIString()).body<ByteArray>()
                if (!ready) {
                    emit(SourceItem.Ready(source, clock.instant(), feed))
                    ready = true
                }
                val receivedAt = clock.instant()
                val rawHash = sha256(bytes)
                if (rawHash != previousRawHash) {
                    emit(
                        SourceItem.RawCapture(
                            source = source,
                            observedAt = receivedAt,
                            sourceUri = feed,
                            mediaType = "application/xml",
                            rawBody = bytes,
                        ),
                    )
                    previousRawHash = rawHash
                }
                parse(bytes, feed, receivedAt)
                    .filter { seen.add(it.sourceEventId) }
                    .forEach { emit(it) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = source,
                        observedAt = clock.instant(),
                        sourceUri = feed,
                        issueKind = "SOURCE_GAP",
                        severity = "WARNING",
                        message = "RSS poll failed: ${exception.javaClass.simpleName}",
                    ),
                )
            }
            delay(pollIntervalMillis)
        }
    }

    internal fun parse(
        bytes: ByteArray,
        feed: URI,
        receivedAt: Instant,
    ): List<SourceItem.Event> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val document =
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val rssItems = document.getElementsByTagName("item")
        val atomEntries = document.getElementsByTagNameNS("*", "entry")
        val nodes = if (rssItems.length > 0) rssItems else atomEntries
        return (0 until nodes.length)
            .mapNotNull { index ->
                val element = nodes.item(index) as? Element ?: return@mapNotNull null
                val title = element.firstText("title") ?: return@mapNotNull null
                val description =
                    element.firstText("description")
                        ?: element.firstText("summary")
                        ?: element.firstText("content")
                val link =
                    element.firstText("link")
                        ?: element.getElementsByTagNameNS("*", "link")
                            .item(0)
                            ?.attributes
                            ?.getNamedItem("href")
                            ?.nodeValue
                val canonicalUrl = link?.takeIf { it.startsWith("https://") }
                val guid =
                    element.firstText("guid")
                        ?: element.firstText("id")
                        ?: canonicalUrl
                        ?: return@mapNotNull null
                val published =
                    sequenceOf(
                        element.firstText("pubDate"),
                        element.firstText("published"),
                        element.firstText("updated"),
                    ).filterNotNull().mapNotNull(::parseDate).firstOrNull() ?: receivedAt
                val text = InformationText.htmlToText("$title ${description.orEmpty()}").trim()
                val rawItem =
                    listOf(guid, title, description.orEmpty(), link.orEmpty(), published.toString())
                        .joinToString("\u001f")
                        .toByteArray(Charsets.UTF_8)
                SourceItem.Event(
                    source = source,
                    observedAt = receivedAt,
                    sourceUri = feed,
                    sourceEventId = sha256(guid.toByteArray(Charsets.UTF_8)),
                    channel = InformationChannel.NEWS,
                    mutation = InformationMutation.CREATE,
                    eventTime = published,
                    authorId = feed.host,
                    language = "en",
                    text = text,
                    canonicalUrl = canonicalUrl,
                    rawBody = rawItem,
                )
            }
    }

    private class BoundedIds(private val maximum: Int) {
        private val values = LinkedHashSet<String>()
        private val order = ArrayDeque<String>()

        fun add(value: String): Boolean {
            if (!values.add(value)) return false
            order.addLast(value)
            while (values.size > maximum) {
                values.remove(order.removeFirst())
            }
            return true
        }
    }

    private companion object {
        fun Element.firstText(name: String): String? {
            val direct = getElementsByTagName(name)
            val namespaced = getElementsByTagNameNS("*", name)
            val value =
                (if (direct.length > 0) direct.item(0) else namespaced.item(0))
                    ?.textContent
                    ?.trim()
            return value?.takeIf(String::isNotEmpty)
        }

        fun parseDate(value: String): Instant? =
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
                .recoverCatching { Instant.parse(value) }
                .getOrNull()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
