package dev.marketlab.persistence

import kotlinx.serialization.json.JsonElement
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

data class IdempotencyRecord(
    val scope: String,
    val key: String,
    val requestHash: String,
    val resourceType: String,
    val resourceId: String,
    val responseStatus: Int,
    val responseBody: JsonElement,
    val createdAt: Instant,
    val expiresAt: Instant?,
)

class IdempotencyRepository(
    private val dataSource: DataSource,
) {
    fun find(scope: String, key: String): IdempotencyRecord? =
        dataSource.read { connection -> find(connection, scope, key) }

    internal fun lock(connection: Connection, scope: String, key: String) {
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        ).use { statement ->
            statement.setString(1, "${scope.length}:$scope$key")
            statement.executeQuery().use { result -> check(result.next()) }
        }
    }

    internal fun find(connection: Connection, scope: String, key: String): IdempotencyRecord? =
        connection.prepareStatement(
            """
            SELECT *
            FROM idempotency_records
            WHERE scope = ? AND idempotency_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, scope)
            statement.setString(2, key)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                IdempotencyRecord(
                    scope = result.getString("scope"),
                    key = result.getString("idempotency_key"),
                    requestHash = result.getString("request_hash"),
                    resourceType = result.getString("resource_type"),
                    resourceId = result.getString("resource_id"),
                    responseStatus = result.getInt("response_status"),
                    responseBody = result.json("response_body"),
                    createdAt = result.instant("created_at"),
                    expiresAt = result.instantOrNull("expires_at"),
                )
            }
        }

    internal fun insert(
        connection: Connection,
        scope: String,
        key: String,
        requestHash: String,
        resourceType: String,
        resourceId: String,
        responseStatus: Int,
        responseBody: JsonElement,
        expiresAt: Instant? = null,
    ): IdempotencyRecord {
        require(requestHash.matches(Regex("[0-9a-f]{64}"))) {
            "requestHash must be a lowercase SHA-256 digest"
        }
        connection.prepareStatement(
            """
            INSERT INTO idempotency_records (
                scope, idempotency_key, request_hash, resource_type, resource_id,
                response_status, response_body, expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            RETURNING *
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, scope)
            statement.setString(2, key)
            statement.setString(3, requestHash)
            statement.setString(4, resourceType)
            statement.setString(5, resourceId)
            statement.setInt(6, responseStatus)
            statement.setString(7, responseBody.toString())
            statement.setInstant(8, expiresAt)
            statement.executeQuery().use { result ->
                check(result.next())
                return IdempotencyRecord(
                    scope = result.getString("scope"),
                    key = result.getString("idempotency_key"),
                    requestHash = result.getString("request_hash"),
                    resourceType = result.getString("resource_type"),
                    resourceId = result.getString("resource_id"),
                    responseStatus = result.getInt("response_status"),
                    responseBody = result.json("response_body"),
                    createdAt = result.instant("created_at"),
                    expiresAt = result.instantOrNull("expires_at"),
                )
            }
        }
    }

    internal fun requireMatching(
        existing: IdempotencyRecord,
        requestHash: String,
    ): IdempotencyRecord {
        if (existing.requestHash != requestHash) {
            throw PersistenceConflictException(
                "Idempotency key '${existing.key}' was already used with a different request",
            )
        }
        return existing
    }
}
