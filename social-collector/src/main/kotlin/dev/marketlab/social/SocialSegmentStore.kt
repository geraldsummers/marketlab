package dev.marketlab.social

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.data.PublicInformationEvent
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val SOCIAL_RECORD_SCHEMA = "marketlab.public-information-record.v1"
internal const val SOCIAL_SEGMENT_SCHEMA = "marketlab.public-information-segment.v1"
internal const val SOCIAL_MANIFEST_SCHEMA = "marketlab.public-information-manifest.v1"

@Serializable
internal data class StoredInformationRecord(
    val recordType: String = "information",
    val schemaVersion: String = SOCIAL_RECORD_SCHEMA,
    val event: PublicInformationEvent,
    val sourceUri: String,
    val rawEncoding: String = "base64",
    val rawBodyBase64: String,
    val rawByteCount: Long,
)

@Serializable
internal data class StoredSourceStatusRecord(
    val recordType: String,
    val schemaVersion: String = SOCIAL_RECORD_SCHEMA,
    val source: String,
    val sourceUri: String,
    val observedAtEpochMillis: Long,
    val issueKind: String? = null,
    val severity: String? = null,
    val message: String? = null,
)

@Serializable
internal data class StoredRawCaptureRecord(
    val recordType: String = "raw_capture",
    val schemaVersion: String = SOCIAL_RECORD_SCHEMA,
    val source: String,
    val sourceUri: String,
    val observedAtEpochMillis: Long,
    val mediaType: String,
    val rawEncoding: String = "base64",
    val rawBodyBase64: String,
    val rawSha256: String,
    val rawByteCount: Long,
)

@Serializable
internal data class InformationSegmentManifest(
    val schemaVersion: String = SOCIAL_MANIFEST_SCHEMA,
    val segmentSchemaVersion: String = SOCIAL_SEGMENT_SCHEMA,
    val recordSchemaVersion: String = SOCIAL_RECORD_SCHEMA,
    val sourceRevision: String,
    val segmentUri: String,
    val segmentSha256: String,
    val byteCount: Long,
    val recordCount: Long,
    val informationCount: Long,
    val readyCount: Long,
    val qualityCount: Long,
    val rawCaptureCount: Long,
    val sources: List<String>,
    val openedAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long,
    val firstEventTimeEpochMillis: Long?,
    val lastEventTimeEpochMillis: Long?,
    val firstReceivedTimeEpochMillis: Long?,
    val lastReceivedTimeEpochMillis: Long?,
)

@Serializable
internal data class InformationIndexEntry(
    val schemaVersion: String = "marketlab.public-information-index.v1",
    val sourceRevision: String,
    val manifestUri: String,
    val manifestSha256: String,
    val segmentUri: String,
    val segmentSha256: String,
    val appendedAtEpochMillis: Long,
)

/**
 * Append-only, single-writer storage for public information.
 *
 * Raw source bytes and derived causal metadata are written into the same
 * content-addressed segment. Publication uses an atomic move only after fsync;
 * the index is appended last under the process lock.
 */
internal class SocialSegmentStore(
    private val config: SocialCollectorConfig,
    private val resolver: CryptoEntityResolver,
    private val clock: Clock = Clock.systemUTC(),
) : Closeable {
    private val root = config.rawRoot.toAbsolutePath().normalize()
    private val partialRoot = root.resolve(".partial")
    private val objectsRoot = root.resolve("objects")
    private val manifestsRoot = root.resolve("manifests")
    private val indexRoot = root.resolve("index")
    private val processChannel: FileChannel
    private val processLock: FileLock
    private var active: ActiveSegment? = null
    private var closed = false

    init {
        listOf(root, partialRoot, objectsRoot, manifestsRoot, indexRoot).forEach {
            Files.createDirectories(it)
        }
        processChannel =
            FileChannel.open(
                root.resolve(".social-collector.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        processLock =
            try {
                processChannel.tryLock()
            } catch (exception: OverlappingFileLockException) {
                processChannel.close()
                throw IllegalStateException("another social collector owns $root", exception)
            } ?: run {
                processChannel.close()
                error("another social collector owns $root")
            }
    }

    fun append(item: SourceItem) {
        check(!closed) { "social segment store is closed" }
        val bytes = encode(item)
        var segment = active
        if (segment != null &&
            (DurationMath.elapsedMillis(segment.openedAt, item.observedAt) >=
                config.maximumSegmentDuration.toMillis() ||
                segment.byteCount + bytes.size > config.maximumSegmentBytes)
        ) {
            finalizeActive()
            segment = null
        }
        if (segment == null) {
            segment = open(item.observedAt)
            active = segment
        }
        require(bytes.size.toLong() <= config.maximumSegmentBytes) {
            "one source record exceeds the segment limit"
        }
        segment.write(bytes)
        segment.include(item)
    }

    fun rotateIfDue(at: Instant) {
        val segment = active ?: return
        if (DurationMath.elapsedMillis(segment.openedAt, at) >=
            config.maximumSegmentDuration.toMillis()
        ) {
            finalizeActive()
        }
    }

    fun markReady(sources: Set<String>, readyAt: Instant) {
        require(sources.isNotEmpty()) { "ready marker requires at least one source" }
        val bytes =
            buildJsonObject {
                put("schemaVersion", "marketlab.public-information-ready.v1")
                put("sourceRevision", config.sourceRevision)
                put("readyAtEpochMillis", readyAt.toEpochMilli())
                put("sources", sources.sorted().joinToString(","))
            }.toString().toByteArray(Charsets.UTF_8)
        val destination = root.resolve(".social-collector-ready.json")
        val partial = partialRoot.resolve("${UUID.randomUUID()}.ready.partial")
        FileChannel.open(
            partial,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.writeFully(bytes)
            channel.force(true)
        }
        Files.move(
            partial,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        try {
            finalizeActive()
        } catch (exception: Throwable) {
            failure = exception
        }
        try {
            processLock.release()
        } catch (exception: Throwable) {
            failure?.addSuppressed(exception) ?: run { failure = exception }
        }
        try {
            processChannel.close()
        } catch (exception: Throwable) {
            failure?.addSuppressed(exception) ?: run { failure = exception }
        }
        closed = true
        failure?.let { throw it }
    }

    private fun encode(item: SourceItem): ByteArray {
        val text =
            when (item) {
                is SourceItem.Ready ->
                    JSON.encodeToString(
                        StoredSourceStatusRecord(
                            recordType = "source_ready",
                            source = item.source,
                            sourceUri = item.sourceUri.toASCIIString(),
                            observedAtEpochMillis = item.observedAt.toEpochMilli(),
                        ),
                    )
                is SourceItem.Quality ->
                    JSON.encodeToString(
                        StoredSourceStatusRecord(
                            recordType = "quality_signal",
                            source = item.source,
                            sourceUri = item.sourceUri.toASCIIString(),
                            observedAtEpochMillis = item.observedAt.toEpochMilli(),
                            issueKind = item.issueKind,
                            severity = item.severity,
                            message = item.message,
                        ),
                    )
                is SourceItem.RawCapture -> {
                    require(item.rawBody.isNotEmpty()) { "raw capture body cannot be empty" }
                    JSON.encodeToString(
                        StoredRawCaptureRecord(
                            source = item.source,
                            sourceUri = item.sourceUri.toASCIIString(),
                            observedAtEpochMillis = item.observedAt.toEpochMilli(),
                            mediaType = item.mediaType,
                            rawBodyBase64 = Base64.getEncoder().encodeToString(item.rawBody),
                            rawSha256 = sha256(item.rawBody),
                            rawByteCount = item.rawBody.size.toLong(),
                        ),
                    )
                }
                is SourceItem.Event -> encodeEvent(item)
            }
        return "$text\n".toByteArray(Charsets.UTF_8)
    }

    private fun encodeEvent(item: SourceItem.Event): String {
        require(item.rawBody.isNotEmpty()) { "source raw body cannot be empty" }
        require(item.observedAt.toEpochMilli() > 0L) { "receive clock must be positive" }
        val rawHash = sha256(item.rawBody)
        val matches = item.text?.let(resolver::resolve).orEmpty()
        val authorHash = item.authorId?.toByteArray(Charsets.UTF_8)?.let(::sha256)
        val event =
            PublicInformationEvent(
                source = DataSourceId(item.source),
                sourceEventId = item.sourceEventId,
                channel = item.channel,
                mutation = item.mutation,
                eventTime = MarketTimestamp(item.eventTime.toEpochMilli()),
                receivedAt = MarketTimestamp(item.observedAt.toEpochMilli()),
                availableAt = MarketTimestamp(item.observedAt.toEpochMilli()),
                authorIdHash = authorHash?.let(::Sha256Digest),
                language = normalizeLanguage(item.language),
                text = item.text,
                canonicalUrl = item.canonicalUrl?.takeIf { it.startsWith("https://") },
                matchedInstruments =
                    matches.map(InstrumentId::value).distinct().sorted().map(::InstrumentId),
                sourceMetrics = item.sourceMetrics,
                rawContentHash = Sha256Digest(rawHash),
                adapterRevision = Sha256Digest(config.sourceRevision),
                production = true,
            )
        return JSON.encodeToString(
            StoredInformationRecord(
                event = event,
                sourceUri = item.sourceUri.toASCIIString(),
                rawBodyBase64 = Base64.getEncoder().encodeToString(item.rawBody),
                rawByteCount = item.rawBody.size.toLong(),
            ),
        )
    }

    private fun open(at: Instant): ActiveSegment {
        val id = UUID.randomUUID().toString()
        val partial = partialRoot.resolve("$id.jsonl.partial")
        val channel =
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        return ActiveSegment(id, partial, channel, at)
    }

    private fun finalizeActive() {
        val segment = active ?: return
        active = null
        var channelClosed = false
        try {
            segment.channel.force(true)
            segment.channel.close()
            channelClosed = true
            val hash = sha256(segment.partial)
            val objectDirectory = objectsRoot.resolve(hash.take(2))
            Files.createDirectories(objectDirectory)
            val objectPath = objectDirectory.resolve("$hash.jsonl")
            publishContent(segment.partial, objectPath)
            val finalizedAt = clock.instant()
            val manifest =
                InformationSegmentManifest(
                    sourceRevision = config.sourceRevision,
                    segmentUri = root.relativize(objectPath).toString(),
                    segmentSha256 = hash,
                    byteCount = segment.byteCount,
                    recordCount = segment.recordCount,
                    informationCount = segment.informationCount,
                    readyCount = segment.readyCount,
                    qualityCount = segment.qualityCount,
                    rawCaptureCount = segment.rawCaptureCount,
                    sources = segment.sources.sorted(),
                    openedAtEpochMillis = segment.openedAt.toEpochMilli(),
                    finalizedAtEpochMillis = finalizedAt.toEpochMilli(),
                    firstEventTimeEpochMillis = segment.firstEventTime?.toEpochMilli(),
                    lastEventTimeEpochMillis = segment.lastEventTime?.toEpochMilli(),
                    firstReceivedTimeEpochMillis = segment.firstReceivedTime?.toEpochMilli(),
                    lastReceivedTimeEpochMillis = segment.lastReceivedTime?.toEpochMilli(),
                )
            val manifestBytes = JSON.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val manifestHash = sha256(manifestBytes)
            val day =
                DateTimeFormatter.ISO_LOCAL_DATE
                    .withZone(ZoneOffset.UTC)
                    .format(segment.openedAt)
            val manifestDirectory = manifestsRoot.resolve(day)
            Files.createDirectories(manifestDirectory)
            val manifestPath = manifestDirectory.resolve("$manifestHash.json")
            publishBytes(manifestBytes, manifestPath)
            val index =
                InformationIndexEntry(
                    sourceRevision = config.sourceRevision,
                    manifestUri = root.relativize(manifestPath).toString(),
                    manifestSha256 = manifestHash,
                    segmentUri = root.relativize(objectPath).toString(),
                    segmentSha256 = hash,
                    appendedAtEpochMillis = finalizedAt.toEpochMilli(),
                )
            appendIndex(day, JSON.encodeToString(index).toByteArray(Charsets.UTF_8))
        } finally {
            if (!channelClosed) segment.channel.close()
            Files.deleteIfExists(segment.partial)
        }
    }

    private fun publishContent(partial: Path, destination: Path) {
        if (!Files.exists(destination)) {
            try {
                Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                Files.deleteIfExists(partial)
            }
        } else {
            Files.deleteIfExists(partial)
        }
    }

    private fun publishBytes(bytes: ByteArray, destination: Path) {
        if (Files.exists(destination)) return
        val partial = partialRoot.resolve("${UUID.randomUUID()}.publish.partial")
        FileChannel.open(
            partial,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.writeFully(bytes)
            channel.force(true)
        }
        try {
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(partial)
        }
    }

    private fun appendIndex(day: String, bytes: ByteArray) {
        val path = indexRoot.resolve("$day.jsonl")
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            channel.writeFully(bytes)
            channel.writeFully(byteArrayOf('\n'.code.toByte()))
            channel.force(true)
        }
    }

    private class ActiveSegment(
        val id: String,
        val partial: Path,
        val channel: FileChannel,
        val openedAt: Instant,
    ) {
        var byteCount = 0L
        var recordCount = 0L
        var informationCount = 0L
        var readyCount = 0L
        var qualityCount = 0L
        var rawCaptureCount = 0L
        val sources = mutableSetOf<String>()
        var firstEventTime: Instant? = null
        var lastEventTime: Instant? = null
        var firstReceivedTime: Instant? = null
        var lastReceivedTime: Instant? = null

        fun write(bytes: ByteArray) {
            channel.writeFully(bytes)
            byteCount = Math.addExact(byteCount, bytes.size.toLong())
            recordCount = Math.addExact(recordCount, 1L)
        }

        fun include(item: SourceItem) {
            sources += item.source
            when (item) {
                is SourceItem.Event -> {
                    informationCount++
                    firstEventTime = minimum(firstEventTime, item.eventTime)
                    lastEventTime = maximum(lastEventTime, item.eventTime)
                    firstReceivedTime = minimum(firstReceivedTime, item.observedAt)
                    lastReceivedTime = maximum(lastReceivedTime, item.observedAt)
                }
                is SourceItem.Ready -> readyCount++
                is SourceItem.Quality -> qualityCount++
                is SourceItem.RawCapture -> rawCaptureCount++
            }
        }

        private fun minimum(current: Instant?, value: Instant): Instant =
            if (current == null || value.isBefore(current)) value else current

        private fun maximum(current: Instant?, value: Instant): Instant =
            if (current == null || value.isAfter(current)) value else current
    }

    private object DurationMath {
        fun elapsedMillis(from: Instant, to: Instant): Long =
            if (to.isBefore(from)) 0L else java.time.Duration.between(from, to).toMillis()
    }

    private companion object {
        val JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        fun normalizeLanguage(value: String?): String? {
            val raw = value?.trim()?.replace('_', '-') ?: return null
            val parts = raw.split('-')
            val language = parts.firstOrNull()?.lowercase()?.takeIf { it.length in 2..3 } ?: return null
            val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.length == 2 }
            return if (region == null) language else "$language-$region"
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun FileChannel.writeFully(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) write(buffer)
        }
    }
}
