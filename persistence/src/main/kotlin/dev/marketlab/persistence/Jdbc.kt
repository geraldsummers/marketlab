package dev.marketlab.persistence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal fun <T> DataSource.read(block: (Connection) -> T): T =
    connection.use(block)

internal fun <T> DataSource.transaction(block: (Connection) -> T): T =
    connection.use { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

internal fun PreparedStatement.setInstant(index: Int, instant: Instant?) {
    if (instant == null) {
        setObject(index, null)
    } else {
        setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
    }
}

internal fun ResultSet.instant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

internal fun ResultSet.json(column: String): JsonElement =
    PersistenceJson.instance.parseToJsonElement(getString(column))

internal fun ResultSet.jsonObject(column: String): JsonObject =
    json(column) as? JsonObject ?: buildJsonObject {}

internal fun ResultSet.jsonOrNull(column: String): JsonElement? =
    getString(column)?.let(PersistenceJson.instance::parseToJsonElement)

internal object PersistenceJson {
    val instance = kotlinx.serialization.json.Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
}

class PersistenceConflictException(message: String) : IllegalStateException(message)

class PersistenceNotFoundException(message: String) : NoSuchElementException(message)

class LeaseLostException(message: String) : IllegalStateException(message)

