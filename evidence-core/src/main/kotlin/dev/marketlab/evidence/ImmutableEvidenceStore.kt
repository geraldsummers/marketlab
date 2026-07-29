package dev.marketlab.evidence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class ImmutableEvidenceStore(
    root: Path,
) {
    val root: Path = root.toAbsolutePath().normalize()
    private val objects = this.root.resolve("objects")

    init {
        require(this.root != this.root.root) { "evidence root cannot be a filesystem root" }
        Files.createDirectories(objects)
    }

    fun storeObject(bytes: ByteArray, suffix: String): StoredEvidenceObject {
        require(bytes.isNotEmpty()) { "evidence object cannot be empty" }
        require(SUFFIX.matches(suffix)) { "unsafe evidence suffix" }
        val hash = sha256(bytes)
        val destination = objects.resolve(hash.take(2)).resolve("$hash.$suffix")
        publish(bytes, destination)
        return StoredEvidenceObject(hash, root.relativize(destination).toString(), bytes.size.toLong())
    }

    fun publish(bytes: ByteArray, destination: Path) {
        val target = destination.toAbsolutePath().normalize()
        require(target.startsWith(root)) {
            "artifact destination escapes root"
        }
        Files.createDirectories(target.parent)
        if (Files.exists(target)) {
            verifyExisting(target, bytes)
            return
        }
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.partial")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                verifyExisting(target, bytes)
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IOException("evidence filesystem does not support atomic rename", exception)
            }
            forceDirectory(target.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
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

    private fun verifyExisting(target: Path, expected: ByteArray) {
        check(Files.isRegularFile(target)) { "immutable evidence target is not a regular file" }
        check(Files.size(target) == expected.size.toLong()) {
            "immutable evidence target already exists with another size"
        }
        check(sha256(target) == sha256(expected)) {
            "immutable evidence target already exists with other content"
        }
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Atomic rename remains mandatory; some filesystems disallow directory fsync.
        }
    }

    private companion object {
        val SUFFIX = Regex("[a-z0-9][a-z0-9.-]{0,15}")
    }
}

data class StoredEvidenceObject(
    val sha256: String,
    val uri: String,
    val bytes: Long,
)
