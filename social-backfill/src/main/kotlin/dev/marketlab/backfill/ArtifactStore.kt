package dev.marketlab.backfill

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal class ArtifactStore(
    val root: Path,
) {
    private val objects = root.resolve("objects")

    init {
        Files.createDirectories(objects)
    }

    fun storeObject(bytes: ByteArray, suffix: String): StoredObject {
        val hash = sha256(bytes)
        val destination = objects.resolve(hash.take(2)).resolve("$hash.$suffix")
        publish(bytes, destination, replace = false)
        return StoredObject(hash, root.relativize(destination).toString(), bytes.size.toLong())
    }

    fun publish(bytes: ByteArray, destination: Path, replace: Boolean = false) {
        require(destination.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            "artifact destination escapes root"
        }
        Files.createDirectories(destination.parent)
        val temporary = destination.resolveSibling(".${UUID.randomUUID()}.partial")
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
            Files.move(
                temporary,
                destination,
                *if (replace) {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                },
            )
        } catch (_: java.nio.file.FileAlreadyExistsException) {
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
}

internal data class StoredObject(
    val sha256: String,
    val uri: String,
    val bytes: Long,
)
