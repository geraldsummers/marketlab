package dev.marketlab.coordinator

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class StoredExperimentArtifact(
    val contentHash: String,
    val uri: String,
    val byteCount: Long,
)

/**
 * Deterministic, immutable storage for experiment evidence. JSON keys are
 * canonicalized before hashing, and a database row is never allowed to point
 * at a partially written file.
 */
internal class ExperimentArtifactStore(root: Path) {
    private val root = root.toAbsolutePath().normalize()
    private val objectsRoot = this.root.resolve("objects")
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    init {
        require(this.root != this.root.root) { "artifact root cannot be a filesystem root" }
        Files.createDirectories(objectsRoot)
    }

    fun putJson(value: JsonElement): StoredExperimentArtifact {
        val bytes =
            json
                .encodeToString(JsonElement.serializer(), canonicalize(value))
                .toByteArray(Charsets.UTF_8)
        val contentHash = sha256(bytes)
        val target = objectsRoot.resolve(contentHash.take(2)).resolve("$contentHash.json")
        require(target.normalize().startsWith(root)) { "artifact path escaped its configured root" }
        writeImmutable(target, bytes, contentHash)
        return StoredExperimentArtifact(
            contentHash = contentHash,
            uri = target.toUri().toASCIIString(),
            byteCount = bytes.size.toLong(),
        )
    }

    private fun writeImmutable(
        target: Path,
        bytes: ByteArray,
        contentHash: String,
    ) {
        Files.createDirectories(target.parent)
        if (Files.exists(target)) {
            verifyExisting(target, bytes.size.toLong(), contentHash)
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
            check(sha256(partial) == contentHash) { "partial artifact hash mismatch" }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                verifyExisting(target, bytes.size.toLong(), contentHash)
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IOException("artifact filesystem does not support atomic rename", exception)
            }
            forceDirectory(target.parent)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun verifyExisting(
        target: Path,
        byteCount: Long,
        contentHash: String,
    ) {
        check(Files.isRegularFile(target)) { "immutable artifact target is not a regular file" }
        check(Files.size(target) == byteCount) {
            "immutable artifact target already exists with another size"
        }
        check(sha256(target) == contentHash) {
            "immutable artifact target already exists with other content"
        }
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
        } catch (_: IOException) {
            // Atomic rename remains mandatory; some filesystems disallow fsync on directories.
        }
    }

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.entries
                        .sortedBy { it.key }
                        .associate { (key, value) -> key to canonicalize(value) },
                )
            is JsonArray -> JsonArray(element.map(::canonicalize))
            is JsonPrimitive -> element
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
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

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
