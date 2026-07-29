package dev.marketlab.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class AsyncSubmission(
    val resourceType: String,
    val resourceId: String,
    val job: JobRow,
    val replayed: Boolean,
)

class SubmissionRepository(
    private val dataSource: DataSource,
    private val idempotency: IdempotencyRepository = IdempotencyRepository(dataSource),
    private val jobs: JobRepository = JobRepository(dataSource),
    private val theories: TheoryRepository = TheoryRepository(dataSource),
    private val runs: RunRepository = RunRepository(dataSource),
) {
    fun cancelJob(
        scope: String,
        idempotencyKey: String,
        requestHash: String,
        jobId: UUID,
    ): Pair<JobRow, Boolean> = dataSource.transaction { connection ->
        idempotency.lock(connection, scope, idempotencyKey)
        idempotency.find(connection, scope, idempotencyKey)?.let { existing ->
            idempotency.requireMatching(existing, requestHash)
            val existingId = UUID.fromString(existing.resourceId)
            val existingJob = jobs.get(connection, existingId)
                ?: error("Idempotency record points to missing job $existingId")
            return@transaction existingJob to true
        }
        val cancelled = jobs.requestCancellation(connection, jobId)
        idempotency.insert(
            connection = connection,
            scope = scope,
            key = idempotencyKey,
            requestHash = requestHash,
            resourceType = "job",
            resourceId = jobId.toString(),
            responseStatus = 202,
            responseBody =
                buildJsonObject {
                    put("jobId", jobId.toString())
                    put("status", cancelled.status.name)
                },
        )
        cancelled to false
    }

    fun submitIngestion(
        scope: String,
        idempotencyKey: String,
        requestHash: String,
        payload: JsonObject,
        priority: Int = 0,
    ): AsyncSubmission = dataSource.transaction { connection ->
        idempotency.lock(connection, scope, idempotencyKey)
        idempotency.find(connection, scope, idempotencyKey)?.let { existing ->
            idempotency.requireMatching(existing, requestHash)
            val jobId = UUID.fromString(existing.resourceId)
            val job = jobs.get(connection, jobId)
                ?: error("Idempotency record points to missing job $jobId")
            return@transaction AsyncSubmission("ingestion", jobId.toString(), job, replayed = true)
        }

        val jobId = UUID.randomUUID()
        jobs.insert(
            connection = connection,
            id = jobId,
            kind = "INGESTION",
            resourceType = "ingestion",
            resourceId = jobId.toString(),
            payload = payload,
            priority = priority,
        )
        val response = submissionResponse("ingestion", jobId, jobId)
        idempotency.insert(
            connection = connection,
            scope = scope,
            key = idempotencyKey,
            requestHash = requestHash,
            resourceType = "ingestion",
            resourceId = jobId.toString(),
            responseStatus = 202,
            responseBody = response,
        )
        AsyncSubmission(
            resourceType = "ingestion",
            resourceId = jobId.toString(),
            job = jobs.get(connection, jobId) ?: error("Inserted job disappeared"),
            replayed = false,
        )
    }

    fun submitRun(
        scope: String,
        idempotencyKey: String,
        requestHash: String,
        theoryId: String,
        theoryVersion: String,
        snapshotId: UUID,
        parameters: JsonObject,
        priority: Int = 0,
    ): AsyncSubmission = dataSource.transaction { connection ->
        idempotency.lock(connection, scope, idempotencyKey)
        idempotency.find(connection, scope, idempotencyKey)?.let { existing ->
            idempotency.requireMatching(existing, requestHash)
            val runId = UUID.fromString(existing.resourceId)
            val job = findResourceJob(connection, "run", runId.toString())
            return@transaction AsyncSubmission("run", runId.toString(), job, replayed = true)
        }

        val theory = theories.get(connection, theoryId, theoryVersion)
            ?: throw PersistenceNotFoundException("Theory $theoryId@$theoryVersion is not registered")
        if (!theory.enabled) {
            throw PersistenceConflictException("Theory $theoryId@$theoryVersion is disabled")
        }
        requireReadySnapshot(connection, snapshotId)

        val runId = UUID.randomUUID()
        runs.insert(connection, theory, snapshotId, parameters, runId)
        val jobId = UUID.randomUUID()
        val payload =
            buildJsonObject {
                put("runId", runId.toString())
                put("theoryId", theoryId)
                put("theoryVersion", theoryVersion)
                put("theoryPlanHash", theory.planHash)
                put("snapshotId", snapshotId.toString())
                put("parameters", parameters)
            }
        jobs.insert(
            connection = connection,
            id = jobId,
            kind = "EXPERIMENT_RUN",
            resourceType = "run",
            resourceId = runId.toString(),
            payload = payload,
            priority = priority,
        )
        idempotency.insert(
            connection = connection,
            scope = scope,
            key = idempotencyKey,
            requestHash = requestHash,
            resourceType = "run",
            resourceId = runId.toString(),
            responseStatus = 202,
            responseBody = submissionResponse("run", runId, jobId),
        )
        AsyncSubmission(
            resourceType = "run",
            resourceId = runId.toString(),
            job = jobs.get(connection, jobId) ?: error("Inserted job disappeared"),
            replayed = false,
        )
    }

    private fun findResourceJob(
        connection: java.sql.Connection,
        resourceType: String,
        resourceId: String,
    ): JobRow =
        connection.prepareStatement(
            """
            SELECT id
            FROM jobs
            WHERE resource_type = ? AND resource_id = ?
            ORDER BY created_at
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, resourceType)
            statement.setString(2, resourceId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    error("Idempotency record points to a resource without a job")
                }
                val jobId = result.uuid("id")
                jobs.get(connection, jobId) ?: error("Resource job $jobId disappeared")
            }
        }

    private fun requireReadySnapshot(connection: java.sql.Connection, snapshotId: UUID) {
        connection.prepareStatement(
            """
            SELECT
                s.status,
                EXISTS (
                    SELECT 1
                    FROM data_quality_findings q
                    WHERE q.snapshot_id = s.id AND q.severity = 'FATAL'
                ) AS has_fatal,
                EXISTS (
                    SELECT 1
                    FROM snapshot_objects so
                    WHERE so.snapshot_id = s.id
                ) AS has_objects
            FROM data_snapshots s
            WHERE s.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, snapshotId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw PersistenceNotFoundException("Snapshot $snapshotId does not exist")
                }
                if (
                    result.getString("status") != "READY" ||
                    result.getBoolean("has_fatal") ||
                    !result.getBoolean("has_objects")
                ) {
                    throw PersistenceConflictException("Snapshot $snapshotId is not eligible for a run")
                }
            }
        }
    }

    private fun submissionResponse(resourceType: String, resourceId: UUID, jobId: UUID): JsonObject =
        buildJsonObject {
            put("resourceType", resourceType)
            put("resourceId", resourceId.toString())
            put("jobId", jobId.toString())
            put("acceptedAt", Instant.now().toString())
        }
}
