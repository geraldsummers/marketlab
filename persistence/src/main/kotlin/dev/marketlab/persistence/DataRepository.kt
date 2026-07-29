package dev.marketlab.persistence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class DatasetRow(
    val id: UUID,
    val source: String,
    val venue: String,
    val instrument: String,
    val dataKind: String,
    val createdAt: Instant,
    val metadata: JsonObject,
)

data class DataObjectRow(
    val id: UUID,
    val datasetId: UUID,
    val contentHash: String,
    val objectUri: String,
    val retrievedAt: Instant,
    val eventTimeStart: Instant?,
    val eventTimeEnd: Instant?,
    val availableTimeStart: Instant?,
    val availableTimeEnd: Instant?,
    val rowCount: Long,
    val byteCount: Long,
    val schemaVersion: String,
    val sourceMetadata: JsonObject = buildJsonObject {},
)

enum class SnapshotStatus {
    BUILDING,
    READY,
    REJECTED,
}

data class SnapshotRow(
    val id: UUID,
    val manifestHash: String,
    val status: SnapshotStatus,
    val createdAt: Instant,
    val metadata: JsonObject,
    val objectCount: Int,
    val fatalFindingCount: Int,
)

enum class QualitySeverity {
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

data class QualityFindingRow(
    val id: Long,
    val snapshotId: UUID?,
    val dataObjectId: UUID?,
    val severity: QualitySeverity,
    val code: String,
    val eventTimeStart: Instant?,
    val eventTimeEnd: Instant?,
    val details: JsonElement,
    val detectedAt: Instant,
)

class DataRepository(
    private val dataSource: DataSource,
) {
    fun registerDataset(
        source: String,
        venue: String,
        instrument: String,
        dataKind: String,
        metadata: JsonObject = buildJsonObject {},
        id: UUID = UUID.randomUUID(),
    ): DatasetRow = dataSource.transaction { connection ->
        connection.prepareStatement(
            """
            INSERT INTO market_datasets (id, source, venue, instrument, data_kind, metadata)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (source, venue, instrument, data_kind) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, source)
            statement.setString(3, venue)
            statement.setString(4, instrument)
            statement.setString(5, dataKind)
            statement.setString(6, metadata.toString())
            statement.executeUpdate()
        }
        findDataset(connection, source, venue, instrument, dataKind)
            ?: error("Dataset registration did not produce a row")
    }

    fun registerObject(row: DataObjectRow): DataObjectRow = dataSource.transaction { connection ->
        require(row.contentHash.matches(SHA256)) { "contentHash must be a lowercase SHA-256 digest" }
        connection.prepareStatement(
            """
            INSERT INTO data_objects (
                id, dataset_id, content_hash, object_uri, retrieved_at,
                event_time_start, event_time_end, available_time_start, available_time_end,
                row_count, byte_count, schema_version, source_metadata
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (content_hash) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, row.id)
            statement.setObject(2, row.datasetId)
            statement.setString(3, row.contentHash)
            statement.setString(4, row.objectUri)
            statement.setInstant(5, row.retrievedAt)
            statement.setInstant(6, row.eventTimeStart)
            statement.setInstant(7, row.eventTimeEnd)
            statement.setInstant(8, row.availableTimeStart)
            statement.setInstant(9, row.availableTimeEnd)
            statement.setLong(10, row.rowCount)
            statement.setLong(11, row.byteCount)
            statement.setString(12, row.schemaVersion)
            statement.setString(13, row.sourceMetadata.toString())
            statement.executeUpdate()
        }
        findObjectByHash(connection, row.contentHash)
            ?: error("Data-object registration did not produce a row")
    }

    fun createSnapshot(
        manifestHash: String,
        requirements: List<JsonElement>,
        objectIds: List<UUID>,
        metadata: JsonObject = buildJsonObject {},
        id: UUID = UUID.randomUUID(),
        status: SnapshotStatus = SnapshotStatus.BUILDING,
    ): SnapshotRow = dataSource.transaction { connection ->
        require(manifestHash.matches(SHA256)) { "manifestHash must be a lowercase SHA-256 digest" }
        require(status != SnapshotStatus.READY || objectIds.isNotEmpty()) {
            "A READY snapshot must contain at least one immutable real-data object"
        }
        connection.prepareStatement(
            """
            INSERT INTO data_snapshots (id, manifest_hash, status, metadata)
            VALUES (?, ?, ?, CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, manifestHash)
            statement.setString(3, status.name)
            statement.setString(4, metadata.toString())
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO snapshot_requirements (snapshot_id, ordinal, requirement)
            VALUES (?, ?, CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            requirements.forEachIndexed { index, requirement ->
                statement.setObject(1, id)
                statement.setInt(2, index)
                statement.setString(3, requirement.toString())
                statement.addBatch()
            }
            if (requirements.isNotEmpty()) statement.executeBatch()
        }
        connection.prepareStatement(
            """
            INSERT INTO snapshot_objects (snapshot_id, data_object_id, ordinal)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            objectIds.forEachIndexed { index, objectId ->
                statement.setObject(1, id)
                statement.setObject(2, objectId)
                statement.setInt(3, index)
                statement.addBatch()
            }
            if (objectIds.isNotEmpty()) statement.executeBatch()
        }
        getSnapshot(connection, id) ?: error("Snapshot insertion did not produce a row")
    }

    fun setSnapshotStatus(id: UUID, status: SnapshotStatus): SnapshotRow =
        dataSource.transaction { connection ->
            if (status == SnapshotStatus.READY) {
                connection.prepareStatement(
                    """
                    SELECT
                        EXISTS (
                            SELECT 1 FROM snapshot_objects WHERE snapshot_id = ?
                        ) AS has_objects,
                        EXISTS (
                            SELECT 1
                            FROM data_quality_findings
                            WHERE snapshot_id = ? AND severity = 'FATAL'
                        ) AS has_fatal
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, id)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        if (!result.getBoolean("has_objects") || result.getBoolean("has_fatal")) {
                            throw PersistenceConflictException(
                                "Snapshot $id cannot become READY without real objects and a clean fatal-quality gate",
                            )
                        }
                    }
                }
            }
            connection.prepareStatement(
                "UPDATE data_snapshots SET status = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setObject(2, id)
                if (statement.executeUpdate() != 1) {
                    throw PersistenceNotFoundException("Snapshot $id does not exist")
                }
            }
            getSnapshot(connection, id) ?: error("Updated snapshot disappeared")
        }

    fun addQualityFinding(
        snapshotId: UUID?,
        dataObjectId: UUID?,
        severity: QualitySeverity,
        code: String,
        details: JsonElement,
        eventTimeStart: Instant? = null,
        eventTimeEnd: Instant? = null,
    ): QualityFindingRow {
        require(snapshotId != null || dataObjectId != null) {
            "A quality finding must identify a snapshot or data object"
        }
        return dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO data_quality_findings (
                    snapshot_id, data_object_id, severity, code,
                    event_time_start, event_time_end, details
                )
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                RETURNING *
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, snapshotId)
                statement.setObject(2, dataObjectId)
                statement.setString(3, severity.name)
                statement.setString(4, code)
                statement.setInstant(5, eventTimeStart)
                statement.setInstant(6, eventTimeEnd)
                statement.setString(7, details.toString())
                statement.executeQuery().use { result ->
                    check(result.next())
                    mapFinding(result)
                }
            }
        }
    }

    fun getSnapshot(id: UUID): SnapshotRow? =
        dataSource.read { connection -> getSnapshot(connection, id) }

    fun listSnapshots(limit: Int = 100, before: Instant? = null): List<SnapshotRow> {
        require(limit in 1..500)
        return dataSource.read { connection ->
            val sql = buildString {
                append(SNAPSHOT_SELECT)
                if (before != null) append(" WHERE s.created_at < ?")
                append(" ORDER BY s.created_at DESC, s.id DESC LIMIT ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                if (before != null) statement.setInstant(index++, before)
                statement.setInt(index, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapSnapshot(result))
                    }
                }
            }
        }
    }

    fun listQualityFindings(snapshotId: UUID, limit: Int = 500): List<QualityFindingRow> {
        require(limit in 1..2_000)
        return dataSource.read { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM data_quality_findings
                WHERE snapshot_id = ?
                ORDER BY id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, snapshotId)
                statement.setInt(2, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapFinding(result))
                    }
                }
            }
        }
    }

    private fun findDataset(
        connection: Connection,
        source: String,
        venue: String,
        instrument: String,
        dataKind: String,
    ): DatasetRow? =
        connection.prepareStatement(
            """
            SELECT *
            FROM market_datasets
            WHERE source = ? AND venue = ? AND instrument = ? AND data_kind = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source)
            statement.setString(2, venue)
            statement.setString(3, instrument)
            statement.setString(4, dataKind)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    DatasetRow(
                        id = result.uuid("id"),
                        source = result.getString("source"),
                        venue = result.getString("venue"),
                        instrument = result.getString("instrument"),
                        dataKind = result.getString("data_kind"),
                        createdAt = result.instant("created_at"),
                        metadata = result.jsonObject("metadata"),
                    )
                } else {
                    null
                }
            }
        }

    private fun findObjectByHash(connection: Connection, hash: String): DataObjectRow? =
        connection.prepareStatement("SELECT * FROM data_objects WHERE content_hash = ?").use { statement ->
            statement.setString(1, hash)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                DataObjectRow(
                    id = result.uuid("id"),
                    datasetId = result.uuid("dataset_id"),
                    contentHash = result.getString("content_hash"),
                    objectUri = result.getString("object_uri"),
                    retrievedAt = result.instant("retrieved_at"),
                    eventTimeStart = result.instantOrNull("event_time_start"),
                    eventTimeEnd = result.instantOrNull("event_time_end"),
                    availableTimeStart = result.instantOrNull("available_time_start"),
                    availableTimeEnd = result.instantOrNull("available_time_end"),
                    rowCount = result.getLong("row_count"),
                    byteCount = result.getLong("byte_count"),
                    schemaVersion = result.getString("schema_version"),
                    sourceMetadata = result.jsonObject("source_metadata"),
                )
            }
        }

    private fun getSnapshot(connection: Connection, id: UUID): SnapshotRow? =
        connection.prepareStatement("$SNAPSHOT_SELECT WHERE s.id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) mapSnapshot(result) else null
            }
        }

    private fun mapSnapshot(result: java.sql.ResultSet): SnapshotRow =
        SnapshotRow(
            id = result.uuid("id"),
            manifestHash = result.getString("manifest_hash"),
            status = SnapshotStatus.valueOf(result.getString("status")),
            createdAt = result.instant("created_at"),
            metadata = result.jsonObject("metadata"),
            objectCount = result.getInt("object_count"),
            fatalFindingCount = result.getInt("fatal_finding_count"),
        )

    private fun mapFinding(result: java.sql.ResultSet): QualityFindingRow =
        QualityFindingRow(
            id = result.getLong("id"),
            snapshotId = result.getObject("snapshot_id", UUID::class.java),
            dataObjectId = result.getObject("data_object_id", UUID::class.java),
            severity = QualitySeverity.valueOf(result.getString("severity")),
            code = result.getString("code"),
            eventTimeStart = result.instantOrNull("event_time_start"),
            eventTimeEnd = result.instantOrNull("event_time_end"),
            details = result.json("details"),
            detectedAt = result.instant("detected_at"),
        )

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val SNAPSHOT_SELECT =
            """
            SELECT
                s.*,
                (SELECT count(*) FROM snapshot_objects so WHERE so.snapshot_id = s.id) AS object_count,
                (
                    SELECT count(*)
                    FROM data_quality_findings q
                    WHERE q.snapshot_id = s.id AND q.severity = 'FATAL'
                ) AS fatal_finding_count
            FROM data_snapshots s
            """.trimIndent()
    }
}
