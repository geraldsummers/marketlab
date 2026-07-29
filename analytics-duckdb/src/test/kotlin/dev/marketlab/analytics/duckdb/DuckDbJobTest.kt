package dev.marketlab.analytics.duckdb

import dev.marketlab.contracts.JobId
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DuckDbJobTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `strict job returns explicitly ordered deterministic rows`() {
        open("ordered").use { job ->
            val result = job.query(
                """
                    SELECT ordinal
                    FROM (VALUES (?), (?)) AS observations(ordinal)
                    ORDER BY ordinal
                """.trimIndent(),
                listOf(DuckDbParameter.Integral(2L), DuckDbParameter.Integral(1L)),
            )

            assertEquals(
                listOf(
                    listOf(DuckDbValue.Integral("1")),
                    listOf(DuckDbValue.Integral("2")),
                ),
                result.rows,
            )
            assertTrue(job.environment().version.isNotBlank())
            assertEquals(1, job.environment().threads)
        }
    }

    @Test
    fun `strict mode rejects ambiguous order and nondeterministic functions`() {
        open("strict-rejections").use { job ->
            assertFailsWith<IllegalArgumentException> {
                job.query("SELECT value FROM (VALUES (1)) AS observations(value)")
            }
            assertFailsWith<IllegalArgumentException> {
                job.query("SELECT random() AS value ORDER BY value")
            }
            assertFailsWith<IllegalArgumentException> {
                job.query("SELECT * FROM read_parquet('/tmp/untrusted.parquet') ORDER BY 1")
            }
        }
    }

    @Test
    fun `one job database cannot be opened concurrently`() {
        val first = open("exclusive")
        try {
            assertFailsWith<IllegalStateException> { open("exclusive") }
        } finally {
            first.close()
        }
    }

    @Test
    fun `registered Parquet view is confined to allowlisted input roots`() {
        val parquet = temporaryDirectory.resolve("production-provenance.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val escaped = parquet.toAbsolutePath().toString().replace("'", "''")
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                        COPY (
                            SELECT 'source' AS field, 'hyperliquid-mainnet' AS value
                            UNION ALL
                            SELECT 'environment', 'production'
                        ) TO '$escaped' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }
        }

        open("parquet").use { job ->
            job.registerParquetView("provenance", listOf(parquet))
            val result = job.query(
                "SELECT field, value FROM provenance ORDER BY field, value",
            )
            assertEquals(2, result.rows.size)
        }
    }

    private fun open(id: String): DuckDbJob =
        DuckDbJob.open(
            DuckDbJobConfig(
                workspaceRoot = temporaryDirectory.resolve("workspace"),
                allowedInputRoots = listOf(temporaryDirectory),
                strictMode = true,
            ),
            JobId(id),
        )
}
