package dev.marketlab.coordinator

import dev.marketlab.persistence.DataRepository
import dev.marketlab.persistence.ExperimentRunRow
import dev.marketlab.persistence.JobLease
import dev.marketlab.persistence.LeaseLostException
import dev.marketlab.persistence.PersistenceConflictException
import dev.marketlab.persistence.PromotionStatus
import dev.marketlab.persistence.RunRepository
import dev.marketlab.persistence.RunStatus
import dev.marketlab.persistence.SnapshotRow
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal data class ExperimentLeaseFence(
    val jobId: UUID,
    val attemptId: UUID,
    val workerId: String,
    val leaseToken: UUID,
    val runId: UUID,
) {
    companion object {
        fun from(
            lease: JobLease,
            runId: UUID,
        ): ExperimentLeaseFence {
            val job = lease.job
            return ExperimentLeaseFence(
                jobId = job.id,
                attemptId = lease.attemptId,
                workerId = requireNotNull(job.leaseOwner) { "leased job has no owner" },
                leaseToken = requireNotNull(job.leaseToken) { "leased job has no token" },
                runId = runId,
            )
        }
    }
}

internal data class ArtifactWrite(
    val id: UUID,
    val kind: String,
    val artifact: StoredExperimentArtifact,
    val manifest: JsonObject,
)

internal enum class TrialStatus {
    SUCCEEDED,
    FAILED,
    ABANDONED,
}

/**
 * A terminal trial result to persist as part of the fenced run completion.
 *
 * Successful trials carry metrics and no failure. Failed or abandoned trials
 * carry a failure and no metrics, so a persisted terminal row is never
 * ambiguous about its outcome.
 */
internal data class TrialWrite(
    val trialNumber: Int,
    val parameters: JsonObject,
    val status: TrialStatus,
    val metrics: JsonElement?,
    val failure: JsonElement?,
) {
    init {
        require(trialNumber >= 0) { "trialNumber cannot be negative" }
        when (status) {
            TrialStatus.SUCCEEDED -> {
                require(metrics != null && metrics !is JsonNull) {
                    "a succeeded trial requires metrics"
                }
                require(failure == null) { "a succeeded trial cannot have a failure" }
            }

            TrialStatus.FAILED,
            TrialStatus.ABANDONED,
            -> {
                require(metrics == null) { "a failed or abandoned trial cannot have metrics" }
                require(failure != null && failure !is JsonNull) {
                    "a failed or abandoned trial requires a failure"
                }
            }
        }
    }
}

internal data class FencedRunCompletion(
    val run: ExperimentRunRow,
    val artifactIds: List<UUID>,
)

internal interface ExperimentPersistence {
    fun getRun(id: UUID): ExperimentRunRow?

    fun markRunning(fence: ExperimentLeaseFence): ExperimentRunRow

    fun finishRun(
        fence: ExperimentLeaseFence,
        status: RunStatus,
        manifest: JsonElement?,
        metrics: JsonElement?,
        failure: JsonElement?,
        promotionStatus: PromotionStatus,
        artifacts: List<ArtifactWrite> = emptyList(),
        trials: List<TrialWrite> = emptyList(),
    ): FencedRunCompletion

    fun getSnapshot(id: UUID): SnapshotRow?
}

/**
 * Run and artifact mutations are fenced by the currently live job lease. The
 * job row is locked before mutation, so cancellation, expiry reaping, and lease
 * replacement cannot race a run transition inside the transaction.
 */
internal class PostgreSqlExperimentPersistence(
    private val dataSource: DataSource,
    private val runs: RunRepository = RunRepository(dataSource),
    private val data: DataRepository = DataRepository(dataSource),
) : ExperimentPersistence {
    override fun getRun(id: UUID): ExperimentRunRow? = runs.get(id)

    override fun markRunning(fence: ExperimentLeaseFence): ExperimentRunRow {
        transaction { connection ->
            connection.requireLiveFence(fence)
            connection.prepareStatement(
                """
                UPDATE experiment_runs
                SET status = 'RUNNING', started_at = COALESCE(started_at, clock_timestamp())
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, fence.runId)
                if (statement.executeUpdate() != 1) {
                    throw LeaseLostException("Experiment lease ${fence.jobId} was lost")
                }
            }
        }
        return runs.get(fence.runId)
            ?: error("Fenced experiment run disappeared after markRunning")
    }

    override fun finishRun(
        fence: ExperimentLeaseFence,
        status: RunStatus,
        manifest: JsonElement?,
        metrics: JsonElement?,
        failure: JsonElement?,
        promotionStatus: PromotionStatus,
        artifacts: List<ArtifactWrite>,
        trials: List<TrialWrite>,
    ): FencedRunCompletion {
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
        require(trials.map(TrialWrite::trialNumber).distinct().size == trials.size) {
            "trial numbers must be unique within a run completion"
        }
        val artifactIds =
            transaction { connection ->
                connection.requireLiveFence(fence)
                trials.forEach { trial ->
                    connection.upsertTerminalTrial(fence.runId, trial)
                }
                val ids =
                    artifacts.map { write ->
                        connection.prepareStatement(
                            """
                            INSERT INTO artifacts (
                                id, run_id, kind, content_hash, object_uri,
                                byte_count, media_type, manifest
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                            RETURNING id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, write.id)
                            statement.setObject(2, fence.runId)
                            statement.setString(3, write.kind)
                            statement.setString(4, write.artifact.contentHash)
                            statement.setString(5, write.artifact.uri)
                            statement.setLong(6, write.artifact.byteCount)
                            statement.setString(7, JSON_MEDIA_TYPE)
                            statement.setString(8, write.manifest.toString())
                            statement.executeQuery().use { result ->
                                check(result.next()) { "Artifact insertion returned no identifier" }
                                result.getObject("id", UUID::class.java)
                            }
                        }
                    }
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
                    statement.setObject(6, fence.runId)
                    if (statement.executeUpdate() != 1) {
                        throw LeaseLostException("Experiment lease ${fence.jobId} was lost")
                    }
                }
                ids
            }
        val run =
            runs.get(fence.runId)
                ?: error("Fenced experiment run disappeared after completion")
        return FencedRunCompletion(run, artifactIds)
    }

    override fun getSnapshot(id: UUID): SnapshotRow? = data.getSnapshot(id)

    private fun Connection.requireLiveFence(fence: ExperimentLeaseFence) {
        prepareStatement(
            """
            SELECT 1
            FROM jobs
            WHERE id = ?
              AND kind = 'EXPERIMENT_RUN'
              AND resource_type = 'run'
              AND resource_id = ?
              AND status = 'LEASED'
              AND lease_owner = ?
              AND lease_token = ?
              AND lease_until > clock_timestamp()
              AND cancellation_requested = FALSE
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, fence.jobId)
            statement.setString(2, fence.runId.toString())
            statement.setString(3, fence.workerId)
            statement.setObject(4, fence.leaseToken)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw LeaseLostException("Experiment lease ${fence.jobId} was lost")
                }
            }
        }
    }

    /**
     * Advances a queued/running trial to its terminal state, or accepts an
     * identical terminal replay. A conflicting replay returns no row and is
     * rejected so recovery cannot silently replace evidence from another
     * attempt.
     */
    private fun Connection.upsertTerminalTrial(
        runId: UUID,
        trial: TrialWrite,
    ) {
        prepareStatement(
            """
            INSERT INTO run_trials (
                run_id,
                trial_number,
                parameters,
                status,
                metrics,
                failure,
                started_at,
                completed_at
            )
            VALUES (
                ?,
                ?,
                CAST(? AS jsonb),
                ?,
                CAST(? AS jsonb),
                CAST(? AS jsonb),
                statement_timestamp(),
                statement_timestamp()
            )
            ON CONFLICT (run_id, trial_number) DO UPDATE
            SET status = EXCLUDED.status,
                metrics = EXCLUDED.metrics,
                failure = EXCLUDED.failure,
                started_at = COALESCE(run_trials.started_at, EXCLUDED.started_at),
                completed_at = COALESCE(run_trials.completed_at, EXCLUDED.completed_at)
            WHERE run_trials.parameters = EXCLUDED.parameters
              AND (
                  run_trials.status IN ('QUEUED', 'RUNNING')
                  OR (
                      run_trials.status = EXCLUDED.status
                      AND run_trials.metrics IS NOT DISTINCT FROM EXCLUDED.metrics
                      AND run_trials.failure IS NOT DISTINCT FROM EXCLUDED.failure
                  )
              )
            RETURNING trial_number
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, runId)
            statement.setInt(2, trial.trialNumber)
            statement.setString(3, trial.parameters.toString())
            statement.setString(4, trial.status.name)
            statement.setString(5, trial.metrics?.toString())
            statement.setString(6, trial.failure?.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw PersistenceConflictException(
                        "Trial ${trial.trialNumber} for run $runId conflicts with persisted evidence",
                    )
                }
            }
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val value = block(connection)
                connection.commit()
                value
            } catch (exception: Throwable) {
                runCatching { connection.rollback() }
                throw exception
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
    }
}
