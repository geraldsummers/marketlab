package dev.marketlab.collector

import dev.marketlab.contracts.market.Bbo
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.market.Trade
import dev.marketlab.data.hyperliquid.HyperliquidStreamItem
import dev.marketlab.data.hyperliquid.HyperliquidStreamSubscription
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class StreamEnvelope(
    val subscription: HyperliquidStreamSubscription,
    val item: HyperliquidStreamItem,
    val observedAt: Instant,
    val connectionOrdinal: Long?,
) {
    val recordTime: Instant
        get() =
            when (val value = item) {
                is HyperliquidStreamItem.Connected ->
                    Instant.ofEpochMilli(value.at.epochMillis)
                is HyperliquidStreamItem.SubscriptionAcknowledged ->
                    Instant.ofEpochMilli(value.at.epochMillis)
                is HyperliquidStreamItem.Observation ->
                    Instant.ofEpochMilli(value.receivedAt.epochMillis)
                is HyperliquidStreamItem.QualitySignal -> observedAt
            }
}

/**
 * Single-writer, append-only segment store.
 *
 * Completed bytes are fsynced and atomically published with a hard link. A
 * hard-link publish cannot replace an existing content-addressed object.
 * Manifests and index entries are published only after the segment exists, so
 * a crash can leave an unreferenced object but never a reference to partial
 * bytes. Stale `.partial` files are deliberately ignored and retained for
 * operator inspection.
 */
internal class StreamSegmentStore(
    private val config: CollectorConfig,
    private val subscriptions: List<HyperliquidStreamSubscription>,
    private val clock: Clock = Clock.systemUTC(),
) : Closeable {
    private val root = config.rawRoot.toAbsolutePath().normalize()
    private val partialRoot = root.resolve(".partial")
    private val objectsRoot = root.resolve("objects")
    private val manifestsRoot = root.resolve("manifests")
    private val indexRoot = root.resolve("index")
    private val readyMarker = root.resolve(".collector-ready.json")
    private val startedAt = clock.instant()
    private val lockChannel: FileChannel
    private val processLock: FileLock
    private val descriptors =
        subscriptions
            .map {
                SubscriptionDescriptor(
                    channel = it.channel,
                    coin = it.coin(),
                    expectedMaximumGapMillis = it.expectedMaximumGap.toMillis(),
                )
            }
            .sortedBy(SubscriptionDescriptor::channel)
    private var active: ActiveSegment? = null
    private var closed = false

    init {
        require(subscriptions.isNotEmpty()) { "at least one stream subscription is required" }
        require(subscriptions.map(HyperliquidStreamSubscription::channel).distinct().size == subscriptions.size) {
            "stream subscriptions must have unique channels"
        }
        require(subscriptions.all { it.coin() == config.coin }) {
            "all subscriptions must match the configured coin"
        }
        createOwnedDirectory(root)
        createOwnedDirectory(partialRoot)
        createOwnedDirectory(objectsRoot)
        createOwnedDirectory(manifestsRoot)
        createOwnedDirectory(indexRoot)

        val channel =
            FileChannel.open(
                root.resolve(".collector.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        val acquired =
            try {
                channel.tryLock()
            } catch (exception: OverlappingFileLockException) {
                channel.close()
                throw IllegalStateException("another collector owns $root", exception)
            }
        if (acquired == null) {
            channel.close()
            error("another collector owns $root")
        }
        lockChannel = channel
        processLock = acquired
        Files.deleteIfExists(readyMarker)
        forceDirectory(root)
    }

    fun append(envelope: StreamEnvelope) {
        check(!closed) { "segment store is closed" }
        validateEnvelope(envelope)
        val encoded = encode(envelope)
        var segment = active
        if (segment != null && segment.shouldRotateForTime(envelope.recordTime)) {
            finalizeActive()
            segment = null
        }
        if (segment == null) {
            segment = openSegment(envelope.recordTime)
            active = segment
        }
        if (segment.byteCount + encoded.bytes.size > config.maximumSegmentBytes &&
            segment.recordCount > 0L
        ) {
            finalizeActive()
            segment = openSegment(envelope.recordTime)
            active = segment
        }
        check(segment.byteCount + encoded.bytes.size <= config.maximumSegmentBytes) {
            "one stream record exceeds the configured segment byte limit"
        }
        segment.write(encoded.bytes)
        segment.include(envelope)
    }

    fun rotateIfDue(at: Instant) {
        check(!closed) { "segment store is closed" }
        if (active?.shouldRotateForTime(at) == true) {
            finalizeActive()
        }
    }

    fun markReady(acknowledgedSubscriptions: Set<String>, readyAt: Instant) {
        check(!closed) { "segment store is closed" }
        val expectedSubscriptions = descriptors.map(SubscriptionDescriptor::channel).toSet()
        require(acknowledgedSubscriptions == expectedSubscriptions) {
            "readiness requires acknowledgements for every configured subscription"
        }
        require(!readyAt.isBefore(startedAt)) { "readiness cannot precede collector startup" }

        active?.channel?.force(false)
        val bytes =
            JSON.encodeToString(
                CollectorReadyMarker(
                    sourceRevision = config.sourceRevision,
                    coin = config.coin,
                    subscriptions = acknowledgedSubscriptions.sorted(),
                    startedAtEpochMillis = startedAt.toEpochMilli(),
                    readyAtEpochMillis = readyAt.toEpochMilli(),
                ),
            ).toByteArray(Charsets.UTF_8)
        val partial = partialRoot.resolve("${UUID.randomUUID()}.ready.partial")
        try {
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
                readyMarker,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectory(root)
        } finally {
            Files.deleteIfExists(partial)
        }
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
            lockChannel.close()
        } catch (exception: Throwable) {
            failure?.addSuppressed(exception) ?: run { failure = exception }
        }
        closed = true
        failure?.let { throw it }
    }

    private fun validateEnvelope(envelope: StreamEnvelope) {
        require(envelope.subscription in subscriptions) { "unknown stream subscription" }
        require(envelope.subscription.coin() == config.coin) { "stream item coin mismatch" }
        when (val item = envelope.item) {
            is HyperliquidStreamItem.Connected -> {
                requireNotNull(envelope.connectionOrdinal) {
                    "connection records require a connection ordinal"
                }
                require(envelope.connectionOrdinal > 0L) {
                    "connection ordinal must be positive"
                }
            }
            is HyperliquidStreamItem.QualitySignal -> {
                require(envelope.connectionOrdinal == null) {
                    "quality records cannot carry a connection ordinal"
                }
            }
            is HyperliquidStreamItem.SubscriptionAcknowledged -> {
                require(envelope.connectionOrdinal == null) {
                    "subscription acknowledgements cannot carry a connection ordinal"
                }
                require(sha256(item.rawBody) == item.contentHash.hex) {
                    "subscription acknowledgement raw-body hash mismatch"
                }
            }
            is HyperliquidStreamItem.Observation -> {
                require(envelope.connectionOrdinal == null) {
                    "observation records cannot carry a connection ordinal"
                }
                val header = item.event.header
                require(header.source.value == HYPERLIQUID_SOURCE) {
                    "collector refuses non-mainnet observations"
                }
                require(header.instrument.value == "hyperliquid:perpetual:${config.coin}") {
                    "collector observation instrument is outside its configured scope"
                }
                require(header.receivedAt == item.receivedAt) {
                    "stream item receive clock disagrees with its event header"
                }
                require(sha256(item.rawBody) == item.contentHash.hex) {
                    "stream raw-body hash mismatch"
                }
                when (envelope.subscription) {
                    is HyperliquidStreamSubscription.Trades ->
                        require(item.event is Trade) { "trade subscription emitted a non-trade event" }
                    is HyperliquidStreamSubscription.Bbo ->
                        require(item.event is Bbo) { "BBO subscription emitted a non-BBO event" }
                    is HyperliquidStreamSubscription.L2Book ->
                        require(item.event is L2Book) { "L2 subscription emitted a non-L2 event" }
                    else -> error("collector supports only trades, BBO, and L2 subscriptions")
                }
            }
        }
    }

    private fun encode(envelope: StreamEnvelope): EncodedRecord {
        val text =
            when (val item = envelope.item) {
                is HyperliquidStreamItem.Connected ->
                    JSON.encodeToString(
                        ConnectionRecord(
                            sourceRevision = config.sourceRevision,
                            subscription = envelope.subscription.channel,
                            coin = config.coin,
                            connectionOrdinal = checkNotNull(envelope.connectionOrdinal),
                            connectedAtEpochMillis = item.at.epochMillis,
                        ),
                    )
                is HyperliquidStreamItem.SubscriptionAcknowledged ->
                    JSON.encodeToString(
                        SubscriptionAcknowledgementRecord(
                            sourceRevision = config.sourceRevision,
                            subscription = envelope.subscription.channel,
                            coin = config.coin,
                            acknowledgedAtEpochMillis = item.at.epochMillis,
                            rawBodyBase64 = Base64.getEncoder().encodeToString(item.rawBody),
                            rawSha256 = item.contentHash.hex,
                            rawByteCount = item.rawBody.size.toLong(),
                        ),
                    )
                is HyperliquidStreamItem.QualitySignal -> {
                    val range = item.issue.timeRange
                    JSON.encodeToString(
                        QualityRecord(
                            sourceRevision = config.sourceRevision,
                            subscription = envelope.subscription.channel,
                            coin = config.coin,
                            observedAtEpochMillis = envelope.observedAt.toEpochMilli(),
                            issueKind = item.issue.kind.name,
                            severity = item.issue.severity.name,
                            message = item.issue.message,
                            rangeFromInclusiveEpochMillis = range?.fromInclusive?.epochMillis,
                            rangeToExclusiveEpochMillis = range?.toExclusive?.epochMillis,
                            objectId = item.issue.objectId?.value,
                        ),
                    )
                }
                is HyperliquidStreamItem.Observation -> {
                    val header = item.event.header
                    JSON.encodeToString(
                        ObservationRecord(
                            sourceRevision = config.sourceRevision,
                            subscription = envelope.subscription.channel,
                            coin = config.coin,
                            eventKind = item.event.eventKind(),
                            eventId = header.id.value,
                            instrument = header.instrument.value,
                            exchangeTimeEpochMillis = header.exchangeTime.epochMillis,
                            receivedAtEpochMillis = header.receivedAt.epochMillis,
                            availableAtEpochMillis = header.availableAt.epochMillis,
                            sequence = header.sequence,
                            rawBodyBase64 = Base64.getEncoder().encodeToString(item.rawBody),
                            rawSha256 = item.contentHash.hex,
                            rawByteCount = item.rawBody.size.toLong(),
                        ),
                    )
                }
            }
        return EncodedRecord((text + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun openSegment(openedAt: Instant): ActiveSegment {
        val partial =
            partialRoot.resolve(
                "${openedAt.toEpochMilli()}-${UUID.randomUUID()}.segment.partial",
            )
        val channel =
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        val segment =
            ActiveSegment(
                partialPath = partial,
                channel = channel,
                openedAt = openedAt,
            )
        val header =
            SegmentHeaderRecord(
                sourceRevision = config.sourceRevision,
                coin = config.coin,
                subscriptions = descriptors,
                openedAtEpochMillis = openedAt.toEpochMilli(),
            )
        segment.write((JSON.encodeToString(header) + "\n").toByteArray(Charsets.UTF_8))
        check(segment.byteCount < config.maximumSegmentBytes) {
            "segment header exceeds configured segment byte limit"
        }
        return segment
    }

    private fun finalizeActive() {
        val segment = active ?: return
        active = null
        var channelClosed = false
        try {
            segment.channel.force(true)
            segment.channel.close()
            channelClosed = true
            val segmentHash = segment.digest.digest().hex()
            check(Files.size(segment.partialPath) == segment.byteCount) {
                "segment byte count changed before publication"
            }
            check(sha256(segment.partialPath) == segmentHash) {
                "segment content hash changed before publication"
            }

            val objectPath =
                objectsRoot
                    .resolve(segmentHash.take(2))
                    .resolve("$segmentHash.jsonl")
            publishExistingPartial(segment.partialPath, objectPath, segmentHash)
            val finalizedAt = clock.instant()
            val manifest =
                segment.manifest(
                    finalizedAt = finalizedAt,
                    objectPath = objectPath,
                    segmentHash = segmentHash,
                )
            val manifestBytes = JSON.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val manifestHash = sha256(manifestBytes)
            val manifestPath =
                manifestsRoot
                    .resolve(manifestHash.take(2))
                    .resolve("$manifestHash.json")
            writeImmutable(manifestPath, manifestBytes, manifestHash)

            val indexEntry =
                SegmentIndexEntry(
                    sourceRevision = config.sourceRevision,
                    coin = config.coin,
                    manifestUri = manifestPath.toUri().toASCIIString(),
                    manifestSha256 = manifestHash,
                    segmentUri = objectPath.toUri().toASCIIString(),
                    segmentSha256 = segmentHash,
                    appendedAtEpochMillis = finalizedAt.toEpochMilli(),
                )
            val indexBytes = JSON.encodeToString(indexEntry).toByteArray(Charsets.UTF_8)
            val datePartition = DATE_FORMAT.format(finalizedAt)
            val indexPath =
                indexRoot
                    .resolve(datePartition)
                    .resolve("${finalizedAt.toEpochMilli()}-$manifestHash.json")
            writeImmutable(indexPath, indexBytes, sha256(indexBytes))
        } finally {
            if (!channelClosed) {
                segment.channel.close()
            }
        }
    }

    private fun writeImmutable(target: Path, bytes: ByteArray, expectedHash: String) {
        requireInsideRoot(target)
        createOwnedDirectory(target.parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExisting(target, expectedHash, bytes.size.toLong())
            return
        }
        val partial = partialRoot.resolve("${UUID.randomUUID()}.object.partial")
        try {
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.writeFully(bytes)
                channel.force(true)
            }
            check(sha256(partial) == expectedHash) { "immutable partial hash mismatch" }
            publishExistingPartial(partial, target, expectedHash)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun publishExistingPartial(partial: Path, target: Path, expectedHash: String) {
        requireInsideRoot(partial)
        requireInsideRoot(target)
        createOwnedDirectory(target.parent)
        try {
            Files.createLink(target, partial)
        } catch (exception: FileAlreadyExistsException) {
            verifyExisting(target, expectedHash, Files.size(partial))
        } catch (exception: UnsupportedOperationException) {
            throw IOException("raw-data filesystem must support atomic hard-link publication", exception)
        }
        forceDirectory(target.parent)
        Files.deleteIfExists(partial)
        forceDirectory(partialRoot)
    }

    private fun verifyExisting(target: Path, expectedHash: String, expectedSize: Long) {
        check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            "immutable target is not a regular file: $target"
        }
        check(Files.size(target) == expectedSize) {
            "immutable target already exists with a different size: $target"
        }
        check(sha256(target) == expectedHash) {
            "immutable target already exists with different content: $target"
        }
    }

    private fun createOwnedDirectory(path: Path) {
        requireInsideRoot(path)
        Files.createDirectories(path)
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "collector path is not a real directory: $path"
        }
        check(!Files.isSymbolicLink(path)) { "collector refuses symbolic-link directories: $path" }
    }

    private fun requireInsideRoot(path: Path) {
        require(path.toAbsolutePath().normalize().startsWith(root)) {
            "collector path escapes configured raw root"
        }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private inner class ActiveSegment(
        val partialPath: Path,
        val channel: FileChannel,
        val openedAt: Instant,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
    ) {
        var byteCount = 0L
        var recordCount = 0L
        var observationCount = 0L
        var connectionCount = 0L
        var subscriptionAcknowledgementCount = 0L
        var qualitySignalCount = 0L
        val rawHashes = mutableSetOf<String>()
        var firstEventTime: Long? = null
        var lastEventTime: Long? = null
        var firstReceivedTime: Long? = null
        var lastReceivedTime: Long? = null
        var firstAvailabilityTime: Long? = null
        var lastAvailabilityTime: Long? = null

        fun write(bytes: ByteArray) {
            channel.writeFully(bytes)
            digest.update(bytes)
            byteCount = Math.addExact(byteCount, bytes.size.toLong())
        }

        fun include(envelope: StreamEnvelope) {
            recordCount = Math.addExact(recordCount, 1L)
            when (val item = envelope.item) {
                is HyperliquidStreamItem.Connected ->
                    connectionCount = Math.addExact(connectionCount, 1L)
                is HyperliquidStreamItem.SubscriptionAcknowledged -> {
                    subscriptionAcknowledgementCount =
                        Math.addExact(subscriptionAcknowledgementCount, 1L)
                    rawHashes.add(item.contentHash.hex)
                }
                is HyperliquidStreamItem.QualitySignal ->
                    qualitySignalCount = Math.addExact(qualitySignalCount, 1L)
                is HyperliquidStreamItem.Observation -> {
                    observationCount = Math.addExact(observationCount, 1L)
                    rawHashes.add(item.contentHash.hex)
                    val header = item.event.header
                    firstEventTime = minimum(firstEventTime, header.exchangeTime.epochMillis)
                    lastEventTime = maximum(lastEventTime, header.exchangeTime.epochMillis)
                    firstReceivedTime = minimum(firstReceivedTime, header.receivedAt.epochMillis)
                    lastReceivedTime = maximum(lastReceivedTime, header.receivedAt.epochMillis)
                    firstAvailabilityTime =
                        minimum(firstAvailabilityTime, header.availableAt.epochMillis)
                    lastAvailabilityTime =
                        maximum(lastAvailabilityTime, header.availableAt.epochMillis)
                }
            }
        }

        fun shouldRotateForTime(at: Instant): Boolean =
            !at.isBefore(openedAt) &&
                java.time.Duration.between(openedAt, at) >= config.maximumSegmentDuration

        fun manifest(
            finalizedAt: Instant,
            objectPath: Path,
            segmentHash: String,
        ): StreamSegmentManifest =
            StreamSegmentManifest(
                sourceRevision = config.sourceRevision,
                coin = config.coin,
                subscriptions = descriptors,
                segmentUri = objectPath.toUri().toASCIIString(),
                segmentSha256 = segmentHash,
                byteCount = byteCount,
                recordCount = recordCount,
                observationCount = observationCount,
                distinctRawMessageCount = rawHashes.size.toLong(),
                connectionCount = connectionCount,
                subscriptionAcknowledgementCount = subscriptionAcknowledgementCount,
                qualitySignalCount = qualitySignalCount,
                openedAtEpochMillis = openedAt.toEpochMilli(),
                finalizedAtEpochMillis = finalizedAt.toEpochMilli(),
                firstEventTimeEpochMillis = firstEventTime,
                lastEventTimeEpochMillis = lastEventTime,
                firstReceivedTimeEpochMillis = firstReceivedTime,
                lastReceivedTimeEpochMillis = lastReceivedTime,
                firstAvailabilityTimeEpochMillis = firstAvailabilityTime,
                lastAvailabilityTimeEpochMillis = lastAvailabilityTime,
            )
    }

    private data class EncodedRecord(val bytes: ByteArray)

    private companion object {
        val JSON: Json =
            Json {
                encodeDefaults = true
                explicitNulls = true
                prettyPrint = false
            }
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withZone(ZoneOffset.UTC)
    }
}

private fun HyperliquidStreamSubscription.coin(): String =
    when (this) {
        is HyperliquidStreamSubscription.Trades -> coin
        is HyperliquidStreamSubscription.Bbo -> coin
        is HyperliquidStreamSubscription.L2Book -> coin
        is HyperliquidStreamSubscription.Candles -> coin
        is HyperliquidStreamSubscription.ActiveAssetContext -> coin
    }

private fun dev.marketlab.contracts.market.MarketEvent.eventKind(): String =
    when (this) {
        is Trade -> "trade"
        is Bbo -> "bbo"
        is L2Book -> "l2_book"
        else -> error("collector supports only trade, BBO, and L2 events")
    }

private fun FileChannel.writeFully(bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        write(buffer)
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).hex()

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().hex()
}

private fun ByteArray.hex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun minimum(current: Long?, value: Long): Long =
    current?.let { minOf(it, value) } ?: value

private fun maximum(current: Long?, value: Long): Long =
    current?.let { maxOf(it, value) } ?: value
