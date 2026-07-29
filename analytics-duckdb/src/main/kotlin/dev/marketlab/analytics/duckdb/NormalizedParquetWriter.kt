package dev.marketlab.analytics.duckdb

import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.Funding
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

data class NormalizedParquetArtifact(
    val path: Path,
    val contentHash: Sha256Digest,
    val rowCount: Long,
    val schemaVersion: String,
)

/**
 * Writes deterministic, content-addressed Parquet partitions from validated
 * market events. It preserves exchange, receipt, and semantic-availability
 * clocks as separate columns and never substitutes data for missing rows.
 */
class NormalizedParquetWriter(root: Path) {
    private val root = root.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.root)
    }

    fun candles(rows: List<Candle>): NormalizedParquetArtifact {
        require(rows.isNotEmpty()) { "cannot normalize an empty candle partition" }
        require(rows.all(Candle::closed)) { "unfinished candles cannot enter normalized storage" }
        requireUnique(rows.map { it.header.id.value })
        return write(
            kind = "candles",
            schemaVersion = CANDLE_SCHEMA,
            rowCount = rows.size.toLong(),
        ) { connection, output ->
            connection.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE normalized_candles (
                        event_id VARCHAR NOT NULL,
                        source VARCHAR NOT NULL,
                        instrument VARCHAR NOT NULL,
                        exchange_time_ms BIGINT NOT NULL,
                        received_at_ms BIGINT NOT NULL,
                        available_at_ms BIGINT NOT NULL,
                        interval_ms BIGINT NOT NULL,
                        open DECIMAL(38, 18) NOT NULL,
                        high DECIMAL(38, 18) NOT NULL,
                        low DECIMAL(38, 18) NOT NULL,
                        close DECIMAL(38, 18) NOT NULL,
                        base_volume DECIMAL(38, 18) NOT NULL,
                        trade_count BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                "INSERT INTO normalized_candles VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                rows.sortedWith(compareBy({ it.header.exchangeTime }, { it.header.id.value })).forEach { row ->
                    insert.setString(1, row.header.id.value)
                    insert.setString(2, row.header.source.value)
                    insert.setString(3, row.header.instrument.value)
                    insert.setLong(4, row.header.exchangeTime.epochMillis)
                    insert.setLong(5, row.header.receivedAt.epochMillis)
                    insert.setLong(6, row.header.availableAt.epochMillis)
                    insert.setLong(7, row.intervalMillis)
                    insert.setBigDecimal(8, row.open.toBigDecimal())
                    insert.setBigDecimal(9, row.high.toBigDecimal())
                    insert.setBigDecimal(10, row.low.toBigDecimal())
                    insert.setBigDecimal(11, row.close.toBigDecimal())
                    insert.setBigDecimal(12, row.baseVolume.toBigDecimal())
                    insert.setLong(13, row.tradeCount)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            copyToParquet(
                connection,
                """
                SELECT *
                FROM normalized_candles
                ORDER BY exchange_time_ms, event_id
                """.trimIndent(),
                output,
            )
        }
    }

    fun funding(rows: List<Funding>): NormalizedParquetArtifact {
        require(rows.isNotEmpty()) { "cannot normalize an empty funding partition" }
        requireUnique(rows.map { it.header.id.value })
        return write(
            kind = "funding",
            schemaVersion = FUNDING_SCHEMA,
            rowCount = rows.size.toLong(),
        ) { connection, output ->
            connection.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE normalized_funding (
                        event_id VARCHAR NOT NULL,
                        source VARCHAR NOT NULL,
                        instrument VARCHAR NOT NULL,
                        exchange_time_ms BIGINT NOT NULL,
                        received_at_ms BIGINT NOT NULL,
                        available_at_ms BIGINT NOT NULL,
                        interval_ms BIGINT NOT NULL,
                        rate DECIMAL(38, 18) NOT NULL,
                        premium DECIMAL(38, 18)
                    )
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                "INSERT INTO normalized_funding VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                rows.sortedWith(compareBy({ it.header.exchangeTime }, { it.header.id.value })).forEach { row ->
                    insert.setString(1, row.header.id.value)
                    insert.setString(2, row.header.source.value)
                    insert.setString(3, row.header.instrument.value)
                    insert.setLong(4, row.header.exchangeTime.epochMillis)
                    insert.setLong(5, row.header.receivedAt.epochMillis)
                    insert.setLong(6, row.header.availableAt.epochMillis)
                    insert.setLong(7, row.intervalMillis)
                    insert.setBigDecimal(8, row.rate.toBigDecimal())
                    insert.setBigDecimal(9, row.premium?.toBigDecimal())
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            copyToParquet(
                connection,
                """
                SELECT *
                FROM normalized_funding
                ORDER BY exchange_time_ms, event_id
                """.trimIndent(),
                output,
            )
        }
    }

    private fun write(
        kind: String,
        schemaVersion: String,
        rowCount: Long,
        block: (Connection, Path) -> Unit,
    ): NormalizedParquetArtifact {
        val kindRoot = root.resolve(kind).normalize()
        require(kindRoot.startsWith(root))
        Files.createDirectories(kindRoot)
        val partial = kindRoot.resolve(".${UUID.randomUUID()}.parquet.partial")
        try {
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                configure(connection)
                block(connection, partial)
            }
            forceFile(partial)
            val hash = Sha256Digest(sha256(partial))
            val destination = kindRoot.resolve("${hash.hex}.parquet")
            if (Files.exists(destination)) {
                check(Files.size(destination) == Files.size(partial) && sha256(destination) == hash.hex) {
                    "content-addressed Parquet target exists with different bytes"
                }
            } else {
                try {
                    Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
                } catch (exception: AtomicMoveNotSupportedException) {
                    throw IllegalStateException("normalized store requires atomic rename", exception)
                }
            }
            return NormalizedParquetArtifact(destination, hash, rowCount, schemaVersion)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun configure(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET threads = 1")
            statement.execute("SET TimeZone = 'UTC'")
            statement.execute("SET preserve_insertion_order = true")
        }
    }

    private fun copyToParquet(connection: Connection, query: String, output: Path) {
        val escaped = output.toAbsolutePath().toString().replace("'", "''")
        connection.createStatement().use { statement ->
            statement.execute(
                "COPY ($query) TO '$escaped' (FORMAT PARQUET, COMPRESSION ZSTD, ROW_GROUP_SIZE 122880)",
            )
        }
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
    }

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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireUnique(ids: List<String>) {
        require(ids.distinct().size == ids.size) { "normalized partition contains duplicate event ids" }
    }

    private companion object {
        const val CANDLE_SCHEMA = "marketlab-candles-v1"
        const val FUNDING_SCHEMA = "marketlab-funding-v1"
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
