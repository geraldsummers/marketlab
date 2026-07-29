package dev.marketlab.persistence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val SHA256_DIGEST = Regex("[0-9a-f]{64}")

data class TheoryVersionRow(
    val theoryId: String,
    val version: String,
    val planHash: String,
    val descriptor: JsonObject,
    val plan: JsonObject,
    val descriptorCanonical: String,
    val planCanonical: String,
    val enabled: Boolean,
    val registeredAt: Instant,
)

enum class RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED,
    INCONCLUSIVE,
}

enum class PromotionStatus {
    NOT_EVALUATED,
    PASSED,
    FAILED,
    BLOCKED,
}

data class ExperimentRunRow(
    val id: UUID,
    val theoryId: String,
    val theoryVersion: String,
    val theoryPlanHash: String,
    val snapshotId: UUID,
    val status: RunStatus,
    val promotionStatus: PromotionStatus,
    val parameters: JsonObject,
    val manifest: JsonElement?,
    val metrics: JsonElement?,
    val failure: JsonElement?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class ArtifactRow(
    val id: UUID,
    val runId: UUID?,
    val kind: String,
    val contentHash: String,
    val objectUri: String,
    val byteCount: Long,
    val mediaType: String,
    val manifest: JsonObject,
    val createdAt: Instant,
)

class TheoryRepository(
    private val dataSource: DataSource,
) {
    fun register(
        theoryId: String,
        version: String,
        planHash: String,
        descriptor: JsonObject,
        plan: JsonObject,
    ): TheoryVersionRow = dataSource.transaction { connection ->
        require(planHash.matches(SHA256_DIGEST)) { "planHash must be a lowercase SHA-256 digest" }
        connection.prepareStatement(
            """
            INSERT INTO theory_versions (
                theory_id, version, plan_hash, descriptor, plan,
                descriptor_canonical, plan_canonical
            )
            VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
            ON CONFLICT (theory_id, version) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, theoryId)
            statement.setString(2, version)
            statement.setString(3, planHash)
            statement.setString(4, descriptor.toString())
            statement.setString(5, plan.toString())
            statement.setString(6, descriptor.toString())
            statement.setString(7, plan.toString())
            statement.executeUpdate()
        }
        val stored = get(connection, theoryId, version)
            ?: error("Theory registration did not produce a row")
        if (stored.planHash != planHash) {
            throw PersistenceConflictException(
                "Theory $theoryId@$version is immutable and is already registered differently",
            )
        }
        stored
    }

    fun get(theoryId: String, version: String): TheoryVersionRow? =
        dataSource.read { connection -> get(connection, theoryId, version) }

    fun latest(theoryId: String): TheoryVersionRow? =
        dataSource.read { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM theory_versions
                WHERE theory_id = ? AND enabled = TRUE
                ORDER BY registered_at DESC, version DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, theoryId)
                statement.executeQuery().use { result ->
                    if (result.next()) mapTheory(result) else null
                }
            }
        }

    fun list(enabledOnly: Boolean = true, limit: Int = 500): List<TheoryVersionRow> {
        require(limit in 1..1_000)
        return dataSource.read { connection ->
            val sql =
                """
                SELECT *
                FROM theory_versions
                ${if (enabledOnly) "WHERE enabled = TRUE" else ""}
                ORDER BY theory_id, registered_at DESC, version DESC
                LIMIT ?
                """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapTheory(result))
                    }
                }
            }
        }
    }

    fun setEnabled(theoryId: String, version: String, enabled: Boolean) {
        dataSource.transaction { connection ->
            connection.prepareStatement(
                "UPDATE theory_versions SET enabled = ? WHERE theory_id = ? AND version = ?",
            ).use { statement ->
                statement.setBoolean(1, enabled)
                statement.setString(2, theoryId)
                statement.setString(3, version)
                if (statement.executeUpdate() != 1) {
                    throw PersistenceNotFoundException("Theory $theoryId@$version does not exist")
                }
            }
        }
    }

    internal fun get(
        connection: Connection,
        theoryId: String,
        version: String,
    ): TheoryVersionRow? =
        connection.prepareStatement(
            "SELECT * FROM theory_versions WHERE theory_id = ? AND version = ?",
        ).use { statement ->
            statement.setString(1, theoryId)
            statement.setString(2, version)
            statement.executeQuery().use { result ->
                if (result.next()) mapTheory(result) else null
            }
        }

    internal fun mapTheory(result: java.sql.ResultSet): TheoryVersionRow =
        TheoryVersionRow(
            theoryId = result.getString("theory_id"),
            version = result.getString("version"),
            planHash = result.getString("plan_hash"),
            descriptor = result.jsonObject("descriptor"),
            plan = result.jsonObject("plan"),
            descriptorCanonical = result.getString("descriptor_canonical"),
            planCanonical = result.getString("plan_canonical"),
            enabled = result.getBoolean("enabled"),
            registeredAt = result.instant("registered_at"),
        )
}

class RunRepository(
    private val dataSource: DataSource,
) {
    fun create(
        theory: TheoryVersionRow,
        snapshotId: UUID,
        parameters: JsonObject,
        id: UUID = UUID.randomUUID(),
    ): ExperimentRunRow = dataSource.transaction { connection ->
        insert(connection, theory, snapshotId, parameters, id)
        get(connection, id) ?: error("Run insertion did not produce a row")
    }

    internal fun insert(
        connection: Connection,
        theory: TheoryVersionRow,
        snapshotId: UUID,
        parameters: JsonObject,
        id: UUID,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO experiment_runs (
                id, theory_id, theory_version, theory_plan_hash,
                snapshot_id, status, parameters
            )
            VALUES (?, ?, ?, ?, ?, 'QUEUED', CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, theory.theoryId)
            statement.setString(3, theory.version)
            statement.setString(4, theory.planHash)
            statement.setObject(5, snapshotId)
            statement.setString(6, parameters.toString())
            statement.executeUpdate()
        }
    }

    fun get(id: UUID): ExperimentRunRow? =
        dataSource.read { connection -> get(connection, id) }

    fun list(limit: Int = 100, before: Instant? = null): List<ExperimentRunRow> {
        require(limit in 1..500)
        return dataSource.read { connection ->
            val sql = buildString {
                append("SELECT * FROM experiment_runs")
                if (before != null) append(" WHERE created_at < ?")
                append(" ORDER BY created_at DESC, id DESC LIMIT ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                if (before != null) statement.setInstant(index++, before)
                statement.setInt(index, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapRun(result))
                    }
                }
            }
        }
    }

    fun markRunning(id: UUID): ExperimentRunRow =
        updateState(id, setOf(RunStatus.QUEUED), RunStatus.RUNNING)

    fun finish(
        id: UUID,
        status: RunStatus,
        manifest: JsonElement?,
        metrics: JsonElement?,
        failure: JsonElement?,
        promotionStatus: PromotionStatus = PromotionStatus.NOT_EVALUATED,
    ): ExperimentRunRow {
        require(
            status in
                setOf(
                    RunStatus.SUCCEEDED,
                    RunStatus.FAILED,
                    RunStatus.CANCELLED,
                    RunStatus.REJECTED,
                    RunStatus.INCONCLUSIVE,
                ),
        )
        require(status == RunStatus.SUCCEEDED || promotionStatus != PromotionStatus.PASSED) {
            "only a succeeded run can pass promotion"
        }
        return dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE experiment_runs
                SET status = ?,
                    promotion_status = ?,
                    manifest = CAST(? AS jsonb),
                    metrics = CAST(? AS jsonb),
                    failure = CAST(? AS jsonb),
                    completed_at = clock_timestamp()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, promotionStatus.name)
                statement.setString(3, manifest?.toString())
                statement.setString(4, metrics?.toString())
                statement.setString(5, failure?.toString())
                statement.setObject(6, id)
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("Run $id is absent or already terminal")
                }
            }
            get(connection, id) ?: error("Updated run disappeared")
        }
    }

    fun addArtifact(
        runId: UUID?,
        kind: String,
        contentHash: String,
        objectUri: String,
        byteCount: Long,
        mediaType: String,
        manifest: JsonObject,
        id: UUID = UUID.randomUUID(),
    ): ArtifactRow = dataSource.transaction { connection ->
        require(contentHash.matches(SHA256_DIGEST)) { "contentHash must be a lowercase SHA-256 digest" }
        connection.prepareStatement(
            """
            INSERT INTO artifacts (
                id, run_id, kind, content_hash, object_uri, byte_count, media_type, manifest
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING *
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, runId)
            statement.setString(3, kind)
            statement.setString(4, contentHash)
            statement.setString(5, objectUri)
            statement.setLong(6, byteCount)
            statement.setString(7, mediaType)
            statement.setString(8, manifest.toString())
            statement.executeQuery().use { result ->
                check(result.next())
                ArtifactRow(
                    id = result.uuid("id"),
                    runId = result.getObject("run_id", UUID::class.java),
                    kind = result.getString("kind"),
                    contentHash = result.getString("content_hash"),
                    objectUri = result.getString("object_uri"),
                    byteCount = result.getLong("byte_count"),
                    mediaType = result.getString("media_type"),
                    manifest = result.jsonObject("manifest"),
                    createdAt = result.instant("created_at"),
                )
            }
        }
    }

    fun listArtifacts(runId: UUID, limit: Int = 500): List<ArtifactRow> {
        require(limit in 1..2_000)
        return dataSource.read { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM artifacts
                WHERE run_id = ?
                ORDER BY created_at, id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, runId)
                statement.setInt(2, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                ArtifactRow(
                                    id = result.uuid("id"),
                                    runId = result.getObject("run_id", UUID::class.java),
                                    kind = result.getString("kind"),
                                    contentHash = result.getString("content_hash"),
                                    objectUri = result.getString("object_uri"),
                                    byteCount = result.getLong("byte_count"),
                                    mediaType = result.getString("media_type"),
                                    manifest = result.jsonObject("manifest"),
                                    createdAt = result.instant("created_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateState(
        id: UUID,
        from: Set<RunStatus>,
        to: RunStatus,
    ): ExperimentRunRow = dataSource.transaction { connection ->
        val placeholders = from.joinToString(",") { "?" }
        val startedClause = if (to == RunStatus.RUNNING) ", started_at = clock_timestamp()" else ""
        connection.prepareStatement(
            "UPDATE experiment_runs SET status = ?$startedClause WHERE id = ? AND status IN ($placeholders)",
        ).use { statement ->
            statement.setString(1, to.name)
            statement.setObject(2, id)
            from.forEachIndexed { index, status -> statement.setString(index + 3, status.name) }
            if (statement.executeUpdate() != 1) {
                throw PersistenceConflictException("Run $id cannot transition to $to")
            }
        }
        get(connection, id) ?: error("Updated run disappeared")
    }

    internal fun get(connection: Connection, id: UUID): ExperimentRunRow? =
        connection.prepareStatement("SELECT * FROM experiment_runs WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) mapRun(result) else null
            }
        }

    private fun mapRun(result: java.sql.ResultSet): ExperimentRunRow =
        ExperimentRunRow(
            id = result.uuid("id"),
            theoryId = result.getString("theory_id"),
            theoryVersion = result.getString("theory_version"),
            theoryPlanHash = result.getString("theory_plan_hash"),
            snapshotId = result.uuid("snapshot_id"),
            status = RunStatus.valueOf(result.getString("status")),
            promotionStatus = PromotionStatus.valueOf(result.getString("promotion_status")),
            parameters = result.jsonObject("parameters"),
            manifest = result.jsonOrNull("manifest"),
            metrics = result.jsonOrNull("metrics"),
            failure = result.jsonOrNull("failure"),
            createdAt = result.instant("created_at"),
            startedAt = result.instantOrNull("started_at"),
            completedAt = result.instantOrNull("completed_at"),
        )

}
