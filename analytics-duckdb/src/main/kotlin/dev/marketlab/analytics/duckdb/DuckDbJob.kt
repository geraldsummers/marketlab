package dev.marketlab.analytics.duckdb

import dev.marketlab.contracts.DecimalValue
import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.contracts.JobId
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class DuckDbJobConfig(
    val workspaceRoot: Path,
    val allowedInputRoots: List<Path>,
    val strictMode: Boolean = true,
)

data class DuckDbColumn(
    val name: String,
    val jdbcType: JDBCType,
    val databaseTypeName: String,
)

sealed interface DuckDbValue {
    data object Null : DuckDbValue
    data class Text(val value: String) : DuckDbValue
    data class Integral(val canonical: String) : DuckDbValue
    data class Decimal(val value: DecimalValue) : DuckDbValue
    data class Floating(val value: FiniteDouble) : DuckDbValue
    data class Logical(val value: Boolean) : DuckDbValue
    data class Temporal(val iso8601: String) : DuckDbValue
    data class Binary(val bytes: ByteArray) : DuckDbValue
}

sealed interface DuckDbParameter {
    data object Null : DuckDbParameter
    data class Text(val value: String) : DuckDbParameter
    data class Integral(val value: Long) : DuckDbParameter
    data class Decimal(val value: DecimalValue) : DuckDbParameter
    data class Floating(val value: FiniteDouble) : DuckDbParameter
    data class Logical(val value: Boolean) : DuckDbParameter
    data class Timestamp(val value: Instant) : DuckDbParameter
}

data class DuckDbResult(
    val columns: List<DuckDbColumn>,
    val rows: List<List<DuckDbValue>>,
)

data class DuckDbEnvironment(
    val version: String,
    val strictMode: Boolean,
    val threads: Int,
    val timeZone: String,
    val defaultOrder: String,
    val defaultNullOrder: String,
)

/**
 * A single-process, single-job DuckDB database. It never opens a database owned
 * by another job, and Parquet paths must resolve beneath an explicit immutable
 * input root.
 */
class DuckDbJob private constructor(
    private val connection: Connection,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val allowedInputRoots: List<Path>,
    private val strictMode: Boolean,
) : Closeable {
    fun registerParquetView(viewName: String, parquetFiles: List<Path>) {
        require(IDENTIFIER.matches(viewName)) { "invalid DuckDB view name" }
        require(parquetFiles.isNotEmpty()) { "at least one Parquet file is required" }
        val files = parquetFiles
            .map(::validateInput)
            .distinct()
            .sortedBy { it.toString() }
        val literals = files.joinToString(separator = ",") { path -> "'${sqlLiteral(path.toString())}'" }
        val identifier = quoteIdentifier(viewName)
        val sql = """
            CREATE OR REPLACE VIEW $identifier AS
            SELECT *
            FROM read_parquet(
                [$literals],
                union_by_name = false,
                hive_partitioning = false
            )
        """.trimIndent()
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    fun query(
        sql: String,
        parameters: List<DuckDbParameter> = emptyList(),
    ): DuckDbResult {
        if (strictMode) validateStrictQuery(sql)
        connection.prepareStatement(sql).use { statement ->
            bind(statement, parameters)
            statement.executeQuery().use { resultSet ->
                val metadata = resultSet.metaData
                val columns = (1..metadata.columnCount).map { index ->
                    DuckDbColumn(
                        name = metadata.getColumnLabel(index),
                        jdbcType = JDBCType.valueOf(metadata.getColumnType(index)),
                        databaseTypeName = metadata.getColumnTypeName(index),
                    )
                }
                val rows = buildList {
                    while (resultSet.next()) {
                        add(columns.indices.map { offset -> readValue(resultSet, offset + 1, columns[offset]) })
                    }
                }
                return DuckDbResult(columns, rows)
            }
        }
    }

    fun environment(): DuckDbEnvironment {
        val version = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version()").use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
        return DuckDbEnvironment(
            version = version,
            strictMode = strictMode,
            threads = if (strictMode) 1 else DEFAULT_NON_STRICT_THREADS,
            timeZone = "UTC",
            defaultOrder = "ASCENDING",
            defaultNullOrder = "NULLS_LAST",
        )
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            connection.close()
        } catch (exception: Throwable) {
            failure = exception
        }
        try {
            lock.release()
        } catch (exception: Throwable) {
            failure?.addSuppressed(exception) ?: run { failure = exception }
        }
        try {
            lockChannel.close()
        } catch (exception: Throwable) {
            failure?.addSuppressed(exception) ?: run { failure = exception }
        }
        failure?.let { throw it }
    }

    private fun validateInput(path: Path): Path {
        require(path.fileName.toString().endsWith(".parquet", ignoreCase = true)) {
            "only Parquet inputs are accepted"
        }
        require(Files.isRegularFile(path)) { "Parquet input does not exist: $path" }
        val real = path.toRealPath()
        require(allowedInputRoots.any(real::startsWith)) {
            "Parquet input is outside the allowlisted roots: $real"
        }
        return real
    }

    private fun validateStrictQuery(sql: String) {
        val normalized = sql.trim()
        require(normalized.isNotEmpty()) { "query cannot be empty" }
        require(!normalized.contains(';')) { "strict queries must contain exactly one statement" }
        require(!SQL_COMMENT.containsMatchIn(normalized)) { "SQL comments are not accepted in strict mode" }
        require(READ_QUERY_PREFIX.containsMatchIn(normalized)) { "strict queries must begin with SELECT or WITH" }
        require(ORDER_BY.containsMatchIn(normalized)) {
            "strict queries require an explicit ORDER BY for deterministic row order"
        }
        require(!MUTATING_OR_EXTERNAL_SQL.containsMatchIn(normalized)) {
            "strict queries cannot mutate state or access external resources"
        }
        require(!NONDETERMINISTIC_SQL.containsMatchIn(normalized)) {
            "strict queries cannot call nondeterministic functions"
        }
    }

    private fun bind(statement: PreparedStatement, parameters: List<DuckDbParameter>) {
        parameters.forEachIndexed { index, parameter ->
            val jdbcIndex = index + 1
            when (parameter) {
                DuckDbParameter.Null -> statement.setNull(jdbcIndex, Types.NULL)
                is DuckDbParameter.Text -> statement.setString(jdbcIndex, parameter.value)
                is DuckDbParameter.Integral -> statement.setLong(jdbcIndex, parameter.value)
                is DuckDbParameter.Decimal ->
                    statement.setBigDecimal(jdbcIndex, parameter.value.toBigDecimal())
                is DuckDbParameter.Floating -> statement.setDouble(jdbcIndex, parameter.value.value)
                is DuckDbParameter.Logical -> statement.setBoolean(jdbcIndex, parameter.value)
                is DuckDbParameter.Timestamp ->
                    statement.setObject(
                        jdbcIndex,
                        OffsetDateTime.ofInstant(parameter.value, ZoneOffset.UTC),
                    )
            }
        }
    }

    private fun readValue(
        resultSet: ResultSet,
        index: Int,
        column: DuckDbColumn,
    ): DuckDbValue {
        val raw = resultSet.getObject(index) ?: return DuckDbValue.Null
        return when (column.jdbcType) {
            JDBCType.TINYINT,
            JDBCType.SMALLINT,
            JDBCType.INTEGER,
            JDBCType.BIGINT,
            -> DuckDbValue.Integral(raw.toString())
            JDBCType.NUMERIC,
            JDBCType.DECIMAL,
            -> DuckDbValue.Decimal(DecimalValue.of(resultSet.getBigDecimal(index)))
            JDBCType.REAL,
            JDBCType.FLOAT,
            JDBCType.DOUBLE,
            -> DuckDbValue.Floating(FiniteDouble(resultSet.getDouble(index)))
            JDBCType.BOOLEAN,
            JDBCType.BIT,
            -> DuckDbValue.Logical(resultSet.getBoolean(index))
            JDBCType.DATE -> DuckDbValue.Temporal(resultSet.getObject(index, LocalDate::class.java).toString())
            JDBCType.TIME,
            JDBCType.TIME_WITH_TIMEZONE,
            -> DuckDbValue.Temporal(readTemporal(raw))
            JDBCType.TIMESTAMP,
            JDBCType.TIMESTAMP_WITH_TIMEZONE,
            -> DuckDbValue.Temporal(readTemporal(raw))
            JDBCType.BINARY,
            JDBCType.VARBINARY,
            JDBCType.LONGVARBINARY,
            JDBCType.BLOB,
            -> DuckDbValue.Binary(resultSet.getBytes(index))
            else -> DuckDbValue.Text(resultSet.getString(index))
        }
    }

    private fun readTemporal(value: Any): String =
        when (value) {
            is Instant -> value.toString()
            is OffsetDateTime -> value.toString()
            is LocalDateTime -> value.toString()
            is LocalDate -> value.toString()
            is LocalTime -> value.toString()
            else -> value.toString()
        }

    companion object {
        fun open(config: DuckDbJobConfig, jobId: JobId): DuckDbJob {
            Class.forName("org.duckdb.DuckDBDriver")
            val workspace = config.workspaceRoot.toAbsolutePath().normalize()
            Files.createDirectories(workspace)
            val jobsRoot = workspace.resolve("jobs")
            Files.createDirectories(jobsRoot)
            val jobRoot = jobsRoot.resolve(jobId.value).normalize()
            require(jobRoot.startsWith(jobsRoot)) { "job path escapes workspace" }
            Files.createDirectories(jobRoot)
            val lockChannel = FileChannel.open(
                jobRoot.resolve(".duckdb.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                lockChannel.tryLock() ?: error("DuckDB job is already open: ${jobId.value}")
            } catch (exception: OverlappingFileLockException) {
                lockChannel.close()
                throw IllegalStateException("DuckDB job is already open: ${jobId.value}", exception)
            }
            try {
                val inputRoots = config.allowedInputRoots.map { root ->
                    require(Files.isDirectory(root)) { "allowed input root does not exist: $root" }
                    root.toRealPath()
                }
                val database = jobRoot.resolve("analytics.duckdb")
                val connection = DriverManager.getConnection("jdbc:duckdb:${database.toAbsolutePath()}")
                configure(connection, config.strictMode)
                return DuckDbJob(
                    connection = connection,
                    lockChannel = lockChannel,
                    lock = lock,
                    allowedInputRoots = inputRoots,
                    strictMode = config.strictMode,
                )
            } catch (exception: Throwable) {
                lock.release()
                lockChannel.close()
                throw exception
            }
        }

        private fun configure(connection: Connection, strictMode: Boolean) {
            val statements = buildList {
                add("SET TimeZone = 'UTC'")
                add("SET default_order = 'ASCENDING'")
                add("SET default_null_order = 'NULLS_LAST'")
                add("SET preserve_insertion_order = true")
                add("SET enable_progress_bar = false")
                if (strictMode) add("SET threads = 1")
            }
            connection.createStatement().use { statement ->
                statements.forEach(statement::execute)
            }
        }

        private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

        private fun sqlLiteral(value: String): String {
            require('\u0000' !in value) { "NUL is not allowed in a SQL path" }
            return value.replace("'", "''")
        }

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
        private val READ_QUERY_PREFIX = Regex("""(?is)^\s*(SELECT|WITH)\b""")
        private val ORDER_BY = Regex("""(?is)\bORDER\s+BY\b""")
        private val SQL_COMMENT = Regex("""(?s)(--|/\*)""")
        private val MUTATING_OR_EXTERNAL_SQL = Regex(
            """(?is)\b(INSERT|UPDATE|DELETE|MERGE|CREATE|DROP|ALTER|COPY|ATTACH|DETACH|INSTALL|LOAD|CALL|PRAGMA|EXPORT|IMPORT|VACUUM|CHECKPOINT|read_csv|read_json|read_parquet|glob|httpfs|sqlite_scan|postgres_scan|query_table)\b""",
        )
        private val NONDETERMINISTIC_SQL = Regex(
            """(?is)\b(random|uuid|gen_random_uuid|now|current_timestamp|current_date|current_time|transaction_timestamp|nextval)\s*(\(|\b)""",
        )
        private const val DEFAULT_NON_STRICT_THREADS = 0
    }
}
