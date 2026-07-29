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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

internal class GdeltGkgAdapter(
    private val client: HttpClient,
    private val lastUpdateUri: URI,
    private val pollIntervalMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) : InformationSourceAdapter {
    override val source: String = "gdelt-gkg-2.1"

    override suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing {
        var previousUrl: String? = null
        var ready = false
        while (true) {
            try {
                val updateText = client.get(lastUpdateUri.toASCIIString()).body<String>()
                val gkgUrl =
                    updateText
                        .lineSequence()
                        .map(String::trim)
                        .firstOrNull { it.endsWith(".gkg.csv.zip") }
                        ?.split(Regex("\\s+"))
                        ?.lastOrNull()
                        ?: error("GDELT lastupdate has no GKG archive")
                if (!ready) {
                    emit(SourceItem.Ready(source, clock.instant(), lastUpdateUri))
                    ready = true
                }
                if (gkgUrl != previousUrl) {
                    val bytes = client.get(gkgUrl).body<ByteArray>()
                    val receivedAt = clock.instant()
                    emit(
                        SourceItem.RawCapture(
                            source = source,
                            observedAt = receivedAt,
                            sourceUri = URI.create(gkgUrl),
                            mediaType = "application/zip",
                            rawBody = bytes,
                        ),
                    )
                    parseArchive(bytes, URI.create(gkgUrl), receivedAt, emit)
                    previousUrl = gkgUrl
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                emit(
                    SourceItem.Quality(
                        source = source,
                        observedAt = clock.instant(),
                        sourceUri = lastUpdateUri,
                        issueKind = "SOURCE_GAP",
                        severity = "WARNING",
                        message = "GDELT poll failed: ${exception.javaClass.simpleName}",
                    ),
                )
            }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun parseArchive(
        bytes: ByteArray,
        archiveUri: URI,
        receivedAt: Instant,
        emit: suspend (SourceItem) -> Unit,
    ) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry ?: error("GDELT archive is empty")
            require(!entry.isDirectory) { "GDELT archive contains no data file" }
            val reader = zip.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                parseLine(line, archiveUri, receivedAt)?.let { event -> emit(event) }
            }
        }
    }

    internal fun parseLine(
        line: String,
        archiveUri: URI,
        receivedAt: Instant,
    ): SourceItem.Event? {
        val columns = line.split('\t')
        if (columns.size < MINIMUM_COLUMNS) return null
        val eventTime =
            runCatching { Instant.from(GDELT_TIME.parse(columns[0])) }
                .getOrNull() ?: return null
        val canonicalUrl = columns[3].takeIf { it.startsWith("https://") }
        val sourceName = columns[2]
        val themes = columns[7].replace(';', ' ')
        val organizations = columns[13].replace(';', ' ')
        val allNames = columns.getOrNull(22).orEmpty().replace(';', ' ')
        val searchableText =
            listOf(sourceName, canonicalUrl.orEmpty(), themes, organizations, allNames)
                .joinToString(" ")
                .trim()
        if (!CRYPTO_HINT.containsMatchIn(searchableText)) return null
        val tone = columns[14].substringBefore(',').toDoubleOrNull()
        val rowBytes = line.toByteArray(Charsets.UTF_8)
        return SourceItem.Event(
            source = source,
            observedAt = receivedAt,
            sourceUri = archiveUri,
            sourceEventId = sha256(rowBytes),
            channel = InformationChannel.NEWS,
            mutation = InformationMutation.CREATE,
            eventTime = eventTime,
            authorId = sourceName,
            language = null,
            text = searchableText,
            canonicalUrl = canonicalUrl,
            sourceMetrics = tone?.let { mapOf("gdelt_tone" to it) }.orEmpty(),
            rawBody = rowBytes,
        )
    }

    private companion object {
        const val MINIMUM_COLUMNS = 15
        val GDELT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC)
        val CRYPTO_HINT =
            Regex(
                "(?i)(bitcoin|ethereum|crypto(?:currency)?|blockchain|hyperliquid|" +
                    "\\bBTC\\b|\\bETH\\b|\\bSOL\\b|\\bXRP\\b|\\bDOGE\\b)",
            )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
