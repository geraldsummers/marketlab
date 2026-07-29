package dev.marketlab.data.storage

import dev.marketlab.contracts.ArtifactId
import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.SnapshotId
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.DataObjectManifest
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObjectProvenance
import dev.marketlab.contracts.data.SourceRequest
import dev.marketlab.data.json.CanonicalJson
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

data class RawObjectDescriptor(
    val source: DataSourceId,
    val request: SourceRequest,
    val retrievedAt: Instant,
    val schemaVersion: String,
    val adapterVersion: String,
    val rowCount: Long,
    val eventTimeRange: TimeRange,
    val availabilityTimeRange: TimeRange,
    val production: Boolean = true,
) {
    init {
        require(schemaVersion.isNotBlank()) { "schema version cannot be blank" }
        require(adapterVersion.isNotBlank()) { "adapter version cannot be blank" }
        require(rowCount > 0) { "raw object must describe at least one row" }
    }
}

/**
 * Immutable, content-addressed storage. Every partial file is created next to its
 * final target, forced to stable storage, hashed, and atomically renamed before a
 * manifest can reference it.
 */
class ContentAddressedDataStore(root: Path) {
    private val root = root.toAbsolutePath().normalize()
    private val objectsRoot = this.root.resolve("objects")
    private val objectManifestsRoot = this.root.resolve("manifests/objects")
    private val snapshotManifestsRoot = this.root.resolve("manifests/snapshots")
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    init {
        Files.createDirectories(objectsRoot)
        Files.createDirectories(objectManifestsRoot)
        Files.createDirectories(snapshotManifestsRoot)
    }

    fun putRaw(bytes: ByteArray, descriptor: RawObjectDescriptor): DataObjectManifest {
        require(bytes.isNotEmpty()) { "raw object cannot be empty" }
        val hash = Sha256Digest(CanonicalJson.sha256(bytes))
        val objectPath = objectPath(hash)
        writeImmutable(objectPath, bytes, expectedHash = hash)
        val manifest = DataObjectManifest(
            id = ArtifactId("data-${hash.hex}"),
            uri = objectPath.toUri().toASCIIString(),
            contentHash = hash,
            byteCount = bytes.size.toLong(),
            rowCount = descriptor.rowCount,
            eventTimeRange = descriptor.eventTimeRange,
            availabilityTimeRange = descriptor.availabilityTimeRange,
            provenance = ObjectProvenance(
                source = descriptor.source,
                request = descriptor.request,
                retrievedAt = MarketTimestamp(descriptor.retrievedAt.toEpochMilli()),
                schemaVersion = descriptor.schemaVersion,
                adapterVersion = descriptor.adapterVersion,
                production = descriptor.production,
            ),
        )
        val manifestBytes = CanonicalJson.encode(json.encodeToJsonElement(manifest))
        val manifestDigest = CanonicalJson.sha256(manifestBytes)
        writeImmutable(
            objectManifestsRoot.resolve("$manifestDigest.json"),
            manifestBytes,
            expectedHash = null,
        )
        return manifest
    }

    fun createSnapshot(
        createdAt: Instant,
        requirements: List<DataRequirement>,
        objects: List<DataObjectManifest>,
        quality: DataQualityReport,
    ): DataSnapshot {
        require(requirements.isNotEmpty()) { "snapshot requirements cannot be empty" }
        require(objects.isNotEmpty()) { "snapshot objects cannot be empty" }
        require(quality.usable) { "a snapshot cannot be created from fatally defective data" }
        require(requirements.map { it.key }.distinct().size == requirements.size) {
            "snapshot requirement keys must be unique"
        }
        require(objects.map { it.contentHash }.distinct().size == objects.size) {
            "snapshot cannot reference the same content more than once"
        }
        val canonicalRequirements = requirements.sortedBy { it.key }
        val canonicalObjects = objects.sortedBy { it.contentHash.hex }
        canonicalObjects.forEach(::verifyObject)
        val seed = SnapshotSeed(
            createdAtEpochMillis = createdAt.toEpochMilli(),
            requirements = canonicalRequirements,
            objects = canonicalObjects,
            quality = quality,
        )
        val seedBytes = CanonicalJson.encode(json.encodeToJsonElement(seed))
        val manifestHash = Sha256Digest(CanonicalJson.sha256(seedBytes))
        val snapshot = DataSnapshot(
            id = SnapshotId("snapshot-${manifestHash.hex.take(48)}"),
            createdAt = MarketTimestamp(createdAt.toEpochMilli()),
            requirements = canonicalRequirements,
            objects = canonicalObjects,
            quality = quality,
            manifestHash = manifestHash,
        )
        val snapshotBytes = CanonicalJson.encode(json.encodeToJsonElement(snapshot))
        writeImmutable(
            snapshotManifestsRoot.resolve("${manifestHash.hex}.json"),
            snapshotBytes,
            expectedHash = null,
        )
        return snapshot
    }

    fun readRaw(hash: Sha256Digest): ByteArray {
        val path = objectPath(hash)
        require(Files.isRegularFile(path)) { "unknown raw object ${hash.hex}" }
        val bytes = Files.readAllBytes(path)
        check(CanonicalJson.sha256(bytes) == hash.hex) { "raw object hash mismatch for ${hash.hex}" }
        return bytes
    }

    fun verifyObject(manifest: DataObjectManifest) {
        val path = objectPath(manifest.contentHash)
        check(path.toUri().toASCIIString() == manifest.uri) { "manifest URI is outside this data store" }
        check(Files.isRegularFile(path)) { "missing raw object ${manifest.contentHash.hex}" }
        check(Files.size(path) == manifest.byteCount) { "raw object size mismatch" }
        check(sha256(path) == manifest.contentHash.hex) { "raw object hash mismatch" }
    }

    /**
     * Recomputes the snapshot seed identity and verifies the exact immutable
     * on-disk snapshot manifest. Checking only the embedded manifestHash field
     * would allow mutable database metadata to alter requirements or
     * provenance without detection.
     */
    fun verifySnapshot(snapshot: DataSnapshot) {
        val canonicalRequirements = snapshot.requirements.sortedBy { it.key }
        val canonicalObjects = snapshot.objects.sortedBy { it.contentHash.hex }
        check(snapshot.requirements == canonicalRequirements) {
            "snapshot requirements are not in canonical order"
        }
        check(snapshot.objects == canonicalObjects) {
            "snapshot objects are not in canonical order"
        }
        canonicalObjects.forEach(::verifyObject)
        val seed =
            SnapshotSeed(
                createdAtEpochMillis = snapshot.createdAt.epochMillis,
                requirements = canonicalRequirements,
                objects = canonicalObjects,
                quality = snapshot.quality,
            )
        val expectedManifestHash =
            CanonicalJson.sha256(
                CanonicalJson.encode(json.encodeToJsonElement(seed)),
            )
        check(snapshot.manifestHash.hex == expectedManifestHash) {
            "snapshot manifest hash does not match its canonical seed"
        }
        check(snapshot.id.value == "snapshot-${expectedManifestHash.take(48)}") {
            "snapshot id does not match its canonical manifest hash"
        }
        val manifestPath = snapshotManifestsRoot.resolve("$expectedManifestHash.json")
        check(Files.isRegularFile(manifestPath)) {
            "missing immutable snapshot manifest $expectedManifestHash"
        }
        val stored =
            json.decodeFromString<DataSnapshot>(
                Files.readString(manifestPath, Charsets.UTF_8),
            )
        check(stored == snapshot) {
            "immutable snapshot manifest differs from the supplied contract"
        }
    }

    private fun objectPath(hash: Sha256Digest): Path =
        objectsRoot.resolve(hash.hex.take(2)).resolve(hash.hex)

    private fun writeImmutable(
        target: Path,
        bytes: ByteArray,
        expectedHash: Sha256Digest?,
    ) {
        require(target.toAbsolutePath().normalize().startsWith(root)) { "target escapes data-store root" }
        Files.createDirectories(target.parent)
        if (Files.exists(target)) {
            verifyExisting(target, bytes, expectedHash)
            return
        }
        val partial = target.parent.resolve(".${target.fileName}.${UUID.randomUUID()}.partial")
        try {
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            expectedHash?.let { hash ->
                check(sha256(partial) == hash.hex) { "partial-file hash mismatch" }
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (exception: FileAlreadyExistsException) {
                verifyExisting(target, bytes, expectedHash)
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IOException("data-store filesystem does not support atomic rename", exception)
            }
            forceDirectory(target.parent)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun verifyExisting(
        target: Path,
        expectedBytes: ByteArray,
        expectedHash: Sha256Digest?,
    ) {
        check(Files.size(target) == expectedBytes.size.toLong()) {
            "immutable target already exists with a different size: $target"
        }
        val actualHash = sha256(target)
        val intendedHash = expectedHash?.hex ?: CanonicalJson.sha256(expectedBytes)
        check(actualHash == intendedHash) {
            "immutable target already exists with different content: $target"
        }
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
        } catch (_: IOException) {
            // Atomic rename is still required. Some filesystems do not allow opening directories.
        }
    }

    @Serializable
    private data class SnapshotSeed(
        val createdAtEpochMillis: Long,
        val requirements: List<DataRequirement>,
        val objects: List<DataObjectManifest>,
        val quality: DataQualityReport,
    )

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
