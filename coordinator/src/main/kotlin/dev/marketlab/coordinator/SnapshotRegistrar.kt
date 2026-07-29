package dev.marketlab.coordinator

import dev.marketlab.contracts.data.DataObjectManifest
import dev.marketlab.contracts.data.DataQualityIssue
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.persistence.DataObjectRow
import dev.marketlab.persistence.DataRepository
import dev.marketlab.persistence.QualitySeverity as PersistenceQualitySeverity
import dev.marketlab.persistence.SnapshotStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Path
import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

data class RegisteredSnapshot(
    val id: UUID,
    val contractId: String,
    val manifestHash: String,
    val objectCount: Int,
    val rowCount: Long,
)

interface SnapshotRegistrar {
    suspend fun registerOrReuse(
        operation: IngestionOperation,
        fetch: suspend () -> DataSnapshot,
    ): RegisteredSnapshot
}

/**
 * Serializes a logical ingestion operation with a PostgreSQL advisory lock.
 * Snapshot metadata carries the full signed-by-hash data contract so a worker
 * can finish a BUILDING snapshot after a process crash without fetching or
 * inventing observations.
 */
class PostgreSqlSnapshotRegistrar(
    private val dataSource: DataSource,
    rawDataRoot: Path,
    private val data: DataRepository = DataRepository(dataSource),
) : SnapshotRegistrar {
    private val rawDataRoot = rawDataRoot.toAbsolutePath().normalize()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    override suspend fun registerOrReuse(
        operation: IngestionOperation,
        fetch: suspend () -> DataSnapshot,
    ): RegisteredSnapshot =
        withContext(Dispatchers.IO) {
            val lockKey = advisoryLockKey(operation.operationKey)
            val lockConnection = dataSource.connection
            try {
                lockConnection.acquireAdvisoryLock(lockKey)
                val existing = findOperationSnapshot(lockConnection, operation.operationKey)
                if (existing != null) {
                    return@withContext recoverOrReturn(existing)
                }
                val snapshot = fetch()
                validateProductionSnapshot(snapshot)
                persistNew(operation, snapshot)
            } finally {
                runCatching { lockConnection.releaseAdvisoryLock(lockKey) }
                lockConnection.close()
            }
        }

    private fun persistNew(
        operation: IngestionOperation,
        snapshot: DataSnapshot,
    ): RegisteredSnapshot {
        val objectRows =
            snapshot.objects.map { manifest ->
                val dataset =
                    data.registerDataset(
                        source = manifest.provenance.source.value,
                        venue = HYPERLIQUID_VENUE,
                        instrument = datasetInstrument(operation),
                        dataKind = datasetKind(operation.kind),
                        metadata =
                            buildJsonObject {
                                put("production", true)
                                put("transport", "REST")
                            },
                    )
                manifest to
                    data.registerObject(
                        DataObjectRow(
                            id = deterministicUuid("data-object:${manifest.contentHash.hex}"),
                            datasetId = dataset.id,
                            contentHash = manifest.contentHash.hex,
                            objectUri = manifest.uri,
                            retrievedAt = manifest.provenance.retrievedAt.toInstant(),
                            eventTimeStart = manifest.eventTimeRange.fromInclusive.toInstant(),
                            eventTimeEnd = manifest.eventTimeRange.toExclusive.toInstant(),
                            availableTimeStart = manifest.availabilityTimeRange.fromInclusive.toInstant(),
                            availableTimeEnd = manifest.availabilityTimeRange.toExclusive.toInstant(),
                            rowCount = manifest.rowCount,
                            byteCount = manifest.byteCount,
                            schemaVersion = manifest.provenance.schemaVersion,
                            sourceMetadata =
                                buildJsonObject {
                                    put("contractObjectId", manifest.id.value)
                                    put("adapterVersion", manifest.provenance.adapterVersion)
                                    put("production", manifest.provenance.production)
                                    put(
                                        "request",
                                        json.encodeToJsonElement(
                                            dev.marketlab.contracts.data.SourceRequest.serializer(),
                                            manifest.provenance.request,
                                        ),
                                    )
                                },
                        ),
                    )
            }
        val snapshotId = deterministicUuid("ingestion-operation:${operation.operationKey}")
        val metadata =
            buildJsonObject {
                put("ingestionOperationKey", operation.operationKey)
                put("ingestionJobId", operation.jobId.toString())
                put("operationOrdinal", operation.ordinal)
                put("source", HYPERLIQUID_SOURCE)
                put("transport", "REST")
                put("dataKind", operation.kind.name)
                put("instrument", operation.displayInstrument)
                put(
                    "instruments",
                    JsonArray(operation.instruments.map(::JsonPrimitive)),
                )
                put("contractSnapshotId", snapshot.id.value)
                put("rowCount", snapshot.objects.sumOf(DataObjectManifest::rowCount))
                put("contractSnapshot", json.encodeToJsonElement(DataSnapshot.serializer(), snapshot))
            }
        data.createSnapshot(
            manifestHash = snapshot.manifestHash.hex,
            requirements =
                snapshot.requirements.map {
                    json.encodeToJsonElement(
                        dev.marketlab.contracts.data.DataRequirement.serializer(),
                        it,
                    )
                },
            objectIds = objectRows.map { (_, row) -> row.id },
            metadata = metadata,
            id = snapshotId,
            status = SnapshotStatus.BUILDING,
        )
        finalizeSnapshot(
            snapshotId = snapshotId,
            snapshot = snapshot,
            rowsByContractId =
                objectRows.associate { (manifest, row) -> manifest.id.value to row.id },
        )
        return RegisteredSnapshot(
            id = snapshotId,
            contractId = snapshot.id.value,
            manifestHash = snapshot.manifestHash.hex,
            objectCount = objectRows.size,
            rowCount = snapshot.objects.sumOf(DataObjectManifest::rowCount),
        )
    }

    private fun recoverOrReturn(existing: ExistingOperationSnapshot): RegisteredSnapshot {
        if (existing.status == SnapshotStatus.REJECTED) {
            throw PermanentJobException(
                "INGESTION_SNAPSHOT_REJECTED",
                "The ingestion operation already produced a rejected snapshot",
            )
        }
        val contractSnapshot =
            json.decodeFromJsonElement(
                DataSnapshot.serializer(),
                existing.metadata.required("contractSnapshot"),
            )
        validateProductionSnapshot(contractSnapshot)
        if (existing.status == SnapshotStatus.BUILDING) {
            finalizeSnapshot(
                snapshotId = existing.id,
                snapshot = contractSnapshot,
                rowsByContractId = findContractObjectIds(existing.id),
            )
        }
        return RegisteredSnapshot(
            id = existing.id,
            contractId = contractSnapshot.id.value,
            manifestHash = existing.manifestHash,
            objectCount = existing.objectCount,
            rowCount = contractSnapshot.objects.sumOf(DataObjectManifest::rowCount),
        )
    }

    private fun finalizeSnapshot(
        snapshotId: UUID,
        snapshot: DataSnapshot,
        rowsByContractId: Map<String, UUID>,
    ) {
        snapshot.quality.issues.forEach { issue ->
            val details = issueDetails(issue)
            val objectId = issue.objectId?.value?.let(rowsByContractId::get)
            if (!findingExists(snapshotId, objectId, issue, details)) {
                data.addQualityFinding(
                    snapshotId = snapshotId,
                    dataObjectId = objectId,
                    severity = issue.severity.toPersistenceSeverity(),
                    code = issue.kind.name,
                    details = details,
                    eventTimeStart = issue.timeRange?.fromInclusive?.toInstant(),
                    eventTimeEnd = issue.timeRange?.toExclusive?.toInstant(),
                )
            }
        }
        val status =
            if (snapshot.quality.issues.any { it.severity == QualitySeverity.FATAL }) {
                SnapshotStatus.REJECTED
            } else {
                SnapshotStatus.READY
            }
        data.setSnapshotStatus(snapshotId, status)
        if (status != SnapshotStatus.READY) {
            throw PermanentJobException(
                "INGESTION_QUALITY_REJECTED",
                "Hyperliquid data failed quality validation",
            )
        }
    }

    private fun findContractObjectIds(snapshotId: UUID): Map<String, UUID> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT o.id, o.source_metadata ->> 'contractObjectId' AS contract_object_id
                FROM snapshot_objects so
                JOIN data_objects o ON o.id = so.data_object_id
                WHERE so.snapshot_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, snapshotId)
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            result.getString("contract_object_id")?.let { contractId ->
                                put(contractId, result.getObject("id", UUID::class.java))
                            }
                        }
                    }
                }
            }
        }

    private fun findingExists(
        snapshotId: UUID,
        objectId: UUID?,
        issue: DataQualityIssue,
        details: JsonObject,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM data_quality_findings
                    WHERE snapshot_id = ?
                      AND data_object_id IS NOT DISTINCT FROM ?
                      AND severity = ?
                      AND code = ?
                      AND event_time_start IS NOT DISTINCT FROM ?
                      AND event_time_end IS NOT DISTINCT FROM ?
                      AND details = CAST(? AS jsonb)
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, snapshotId)
                statement.setNullableUuid(2, objectId)
                statement.setString(3, issue.severity.toPersistenceSeverity().name)
                statement.setString(4, issue.kind.name)
                statement.setNullableInstant(5, issue.timeRange?.fromInclusive?.toInstant())
                statement.setNullableInstant(6, issue.timeRange?.toExclusive?.toInstant())
                statement.setString(7, details.toString())
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getBoolean(1)
                }
            }
        }

    private fun validateProductionSnapshot(snapshot: DataSnapshot) {
        require(snapshot.objects.isNotEmpty()) { "ingestion returned no immutable raw objects" }
        snapshot.objects.forEach { manifest ->
            require(manifest.provenance.production) { "non-production data is forbidden" }
            require(manifest.provenance.source.value == HYPERLIQUID_SOURCE) {
                "only Hyperliquid mainnet data may enter this handler"
            }
            require(manifest.provenance.request.method == "POST") {
                "unexpected Hyperliquid provenance method"
            }
            require(manifest.provenance.request.uri == HYPERLIQUID_INFO_URL) {
                "unexpected Hyperliquid provenance endpoint"
            }
            val objectPath = Path.of(URI(manifest.uri)).toAbsolutePath().normalize()
            require(objectPath.startsWith(rawDataRoot)) {
                "raw object URI escapes the configured content-addressed store"
            }
        }
    }

    private fun findOperationSnapshot(
        connection: Connection,
        operationKey: String,
    ): ExistingOperationSnapshot? =
        connection.prepareStatement(
            """
            SELECT
                s.id,
                s.manifest_hash,
                s.status,
                s.metadata,
                (SELECT count(*) FROM snapshot_objects so WHERE so.snapshot_id = s.id) AS object_count
            FROM data_snapshots s
            WHERE s.metadata ->> 'ingestionOperationKey' = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationKey)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    ExistingOperationSnapshot(
                        id = result.getObject("id", UUID::class.java),
                        manifestHash = result.getString("manifest_hash"),
                        status = SnapshotStatus.valueOf(result.getString("status")),
                        metadata = json.parseToJsonElement(result.getString("metadata")).jsonObject,
                        objectCount = result.getInt("object_count"),
                    )
                }
            }
        }

    private fun issueDetails(issue: DataQualityIssue): JsonObject =
        buildJsonObject {
            put("message", issue.message)
            put("qualityIssueKind", issue.kind.name)
            issue.objectId?.let { put("contractObjectId", it.value) }
        }

    private fun datasetInstrument(operation: IngestionOperation): String =
        when (operation.kind) {
            RestDataKind.OPEN_INTEREST, RestDataKind.MARK_ORACLE -> "ALL"
            else -> operation.instruments.single()
        }

    private fun datasetKind(kind: RestDataKind): String =
        when (kind) {
            RestDataKind.OPEN_INTEREST, RestDataKind.MARK_ORACLE -> "ASSET_CONTEXT"
            else -> kind.name
        }

    private fun QualitySeverity.toPersistenceSeverity(): PersistenceQualitySeverity =
        when (this) {
            QualitySeverity.INFO -> PersistenceQualitySeverity.INFO
            QualitySeverity.WARNING -> PersistenceQualitySeverity.WARNING
            QualitySeverity.FATAL -> PersistenceQualitySeverity.FATAL
        }

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: error("snapshot recovery metadata is missing $name")

    private fun dev.marketlab.contracts.MarketTimestamp.toInstant(): Instant =
        Instant.ofEpochMilli(epochMillis)

    private fun deterministicUuid(identity: String): UUID =
        UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))

    private fun advisoryLockKey(operationKey: String): Long =
        operationKey.take(16).toULong(16).toLong()

    private fun Connection.acquireAdvisoryLock(key: Long) {
        prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { result -> check(result.next()) }
        }
    }

    private fun Connection.releaseAdvisoryLock(key: Long) {
        prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { result ->
                check(result.next() && result.getBoolean(1))
            }
        }
    }

    private fun java.sql.PreparedStatement.setNullableUuid(index: Int, value: UUID?) {
        if (value == null) setNull(index, Types.OTHER) else setObject(index, value)
    }

    private fun java.sql.PreparedStatement.setNullableInstant(index: Int, value: Instant?) {
        if (value == null) {
            setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
            setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
        }
    }

    private data class ExistingOperationSnapshot(
        val id: UUID,
        val manifestHash: String,
        val status: SnapshotStatus,
        val metadata: JsonObject,
        val objectCount: Int,
    )

    private companion object {
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val HYPERLIQUID_VENUE = "HYPERLIQUID"
        const val HYPERLIQUID_INFO_URL = "https://api.hyperliquid.xyz/info"
    }
}
