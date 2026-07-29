package dev.marketlab.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.net.URI
import javax.sql.DataSource

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 1,
    val connectionTimeoutMillis: Long = 10_000,
    val migrateOnStart: Boolean = true,
) {
    init {
        require(jdbcUrl.startsWith("jdbc:postgresql:")) {
            "Only PostgreSQL JDBC URLs are accepted"
        }
        require(maximumPoolSize in 1..100)
        require(minimumIdle in 0..maximumPoolSize)
        require(connectionTimeoutMillis in 250..120_000)
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): DatabaseConfig {
            val rawUrl = environment["MARKETLAB_DATABASE_URL"]
                ?: "jdbc:postgresql://127.0.0.1:5432/marketlab"
            val parsed = parseDatabaseUrl(rawUrl)
            return DatabaseConfig(
                jdbcUrl = parsed.jdbcUrl,
                username = environment["MARKETLAB_DATABASE_USER"] ?: parsed.username,
                password = environment["MARKETLAB_DATABASE_PASSWORD"] ?: parsed.password,
                maximumPoolSize = environment["MARKETLAB_DATABASE_POOL_SIZE"]?.toIntOrNull() ?: 10,
                minimumIdle = environment["MARKETLAB_DATABASE_MIN_IDLE"]?.toIntOrNull() ?: 1,
                connectionTimeoutMillis =
                    environment["MARKETLAB_DATABASE_CONNECTION_TIMEOUT_MS"]?.toLongOrNull() ?: 10_000,
                migrateOnStart =
                    environment["MARKETLAB_DATABASE_MIGRATE"]?.toBooleanStrictOrNull() ?: true,
            )
        }

        private fun parseDatabaseUrl(raw: String): ParsedDatabaseUrl {
            if (raw.startsWith("jdbc:postgresql:")) {
                return ParsedDatabaseUrl(raw, null, null)
            }
            require(raw.startsWith("postgresql://") || raw.startsWith("postgres://")) {
                "MARKETLAB_DATABASE_URL must be a PostgreSQL or PostgreSQL JDBC URL"
            }
            val uri = URI(raw)
            val userInfo = uri.userInfo?.split(":", limit = 2)
            val authority = buildString {
                append(uri.host)
                if (uri.port >= 0) append(":${uri.port}")
            }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return ParsedDatabaseUrl(
                jdbcUrl = "jdbc:postgresql://$authority${uri.rawPath}$query",
                username = userInfo?.getOrNull(0),
                password = userInfo?.getOrNull(1),
            )
        }
    }
}

private data class ParsedDatabaseUrl(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
)

class Database private constructor(
    private val pool: HikariDataSource,
) : AutoCloseable {
    val dataSource: DataSource
        get() = pool

    override fun close() = pool.close()

    companion object {
        fun open(config: DatabaseConfig): Database {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                config.username?.let { username = it }
                config.password?.let { password = it }
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = config.maximumPoolSize
                minimumIdle = config.minimumIdle
                connectionTimeout = config.connectionTimeoutMillis
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                isAutoCommit = true
                poolName = "marketlab-control-plane"
                addDataSourceProperty("ApplicationName", "marketlab")
                addDataSourceProperty("tcpKeepAlive", "true")
            }
            val dataSource = HikariDataSource(hikariConfig)
            if (config.migrateOnStart) migrate(dataSource)
            return Database(dataSource)
        }

        fun migrate(dataSource: DataSource) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load()
                .migrate()
        }
    }
}
