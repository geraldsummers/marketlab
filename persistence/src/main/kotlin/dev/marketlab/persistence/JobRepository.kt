package dev.marketlab.persistence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class JobStatus {
    QUEUED,
    LEASED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class JobRow(
    val id: UUID,
    val kind: String,
    val resourceType: String,
    val resourceId: String,
    val payload: JsonObject,
    val status: JobStatus,
    val priority: Int,
    val availableAt: Instant,
    val maxAttempts: Int,
    val attemptCount: Int,
    val leaseOwner: String?,
    val leaseToken: UUID?,
    val leaseUntil: Instant?,
    val cancellationRequested: Boolean,
    val result: JsonElement?,
    val lastError: JsonElement?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

data class JobLease(
    val job: JobRow,
    val attemptId: UUID,
) {
    val token: UUID
        get() = requireNotNull(job.leaseToken)
}

data class JobScheduleRow(
    val id: UUID,
    val name: String,
    val jobKind: String,
    val resourceType: String,
    val payload: JsonObject,
    val cronUtc: String,
    val enabled: Boolean,
    val nextFireAt: Instant,
    val lastFireAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class JobRepository(
    private val dataSource: DataSource,
) {
    fun enqueue(
        kind: String,
        resourceType: String,
        resourceId: String,
        payload: JsonObject,
        priority: Int = 0,
        availableAt: Instant = Instant.now(),
        maxAttempts: Int = 3,
        id: UUID = UUID.randomUUID(),
    ): JobRow = dataSource.transaction { connection ->
        insert(
            connection = connection,
            id = id,
            kind = kind,
            resourceType = resourceType,
            resourceId = resourceId,
            payload = payload,
            priority = priority,
            availableAt = availableAt,
            maxAttempts = maxAttempts,
        )
        get(connection, id) ?: error("Job insertion did not produce a row")
    }

    internal fun insert(
        connection: Connection,
        id: UUID,
        kind: String,
        resourceType: String,
        resourceId: String,
        payload: JsonObject,
        priority: Int = 0,
        availableAt: Instant = Instant.now(),
        maxAttempts: Int = 3,
    ) {
        require(kind.matches(IDENTIFIER))
        require(resourceType.matches(RESOURCE_TYPE))
        require(priority in -1_000..1_000)
        require(maxAttempts in 1..100)
        connection.prepareStatement(
            """
            INSERT INTO jobs (
                id, kind, resource_type, resource_id, payload,
                priority, available_at, max_attempts
            )
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, kind)
            statement.setString(3, resourceType)
            statement.setString(4, resourceId)
            statement.setString(5, payload.toString())
            statement.setInt(6, priority)
            statement.setInstant(7, availableAt)
            statement.setInt(8, maxAttempts)
            statement.executeUpdate()
        }
    }

    fun get(id: UUID): JobRow? =
        dataSource.read { connection -> get(connection, id) }

    fun list(
        limit: Int = 100,
        statuses: Set<JobStatus> = emptySet(),
        resourceType: String? = null,
        resourceId: String? = null,
    ): List<JobRow> {
        require(limit in 1..500)
        require((resourceType == null) == (resourceId == null)) {
            "resourceType and resourceId must be supplied together"
        }
        return dataSource.read { connection ->
            val predicates = mutableListOf<String>()
            if (statuses.isNotEmpty()) {
                predicates += "status IN (${statuses.joinToString(",") { "?" }})"
            }
            if (resourceType != null) predicates += "resource_type = ? AND resource_id = ?"
            val sql = buildString {
                append("SELECT * FROM jobs")
                if (predicates.isNotEmpty()) append(" WHERE ${predicates.joinToString(" AND ")}")
                append(" ORDER BY created_at DESC, id DESC LIMIT ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                statuses.forEach { statement.setString(index++, it.name) }
                if (resourceType != null && resourceId != null) {
                    statement.setString(index++, resourceType)
                    statement.setString(index++, resourceId)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapJob(result))
                    }
                }
            }
        }
    }

    /**
     * Claims one eligible job using PostgreSQL row locking. Expired leases are
     * deliberately eligible again, so a worker must make side effects
     * idempotent and finish with the lease token it received.
     */
    fun leaseNext(
        workerId: String,
        leaseDuration: Duration,
        acceptedKinds: Set<String> = emptySet(),
    ): JobLease? {
        require(workerId.matches(WORKER_ID))
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        require(leaseDuration <= Duration.ofHours(24))
        require(acceptedKinds.all { it.matches(IDENTIFIER) })
        return dataSource.transaction { connection ->
            val kindFilter =
                if (acceptedKinds.isEmpty()) {
                    ""
                } else {
                    "AND kind IN (${acceptedKinds.joinToString(",") { "?" }})"
                }
            val candidate =
                connection.prepareStatement(
                    """
                    SELECT *
                    FROM jobs
                    WHERE cancellation_requested = FALSE
                      AND attempt_count < max_attempts
                      AND (
                          status = 'QUEUED'
                          OR (status = 'LEASED' AND lease_until <= clock_timestamp())
                      )
                      AND available_at <= clock_timestamp()
                      $kindFilter
                    ORDER BY priority DESC, available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    acceptedKinds.forEachIndexed { index, kind -> statement.setString(index + 1, kind) }
                    statement.executeQuery().use { result ->
                        if (result.next()) mapJob(result) else null
                    }
                } ?: return@transaction null

            candidate.leaseToken?.let { expiredToken ->
                connection.prepareStatement(
                    """
                    UPDATE job_attempts
                    SET finished_at = clock_timestamp(), outcome = 'LEASE_EXPIRED'
                    WHERE lease_token = ? AND finished_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, expiredToken)
                    statement.executeUpdate()
                }
            }

            val leaseToken = UUID.randomUUID()
            connection.prepareStatement(
                """
                UPDATE jobs
                SET status = 'LEASED',
                    attempt_count = attempt_count + 1,
                    lease_owner = ?,
                    lease_token = ?,
                    lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, workerId)
                statement.setObject(2, leaseToken)
                statement.setLong(3, leaseDuration.toMillis())
                statement.setObject(4, candidate.id)
                check(statement.executeUpdate() == 1)
            }
            val leased = get(connection, candidate.id) ?: error("Leased job disappeared")
            val attemptId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO job_attempts (
                    id, job_id, attempt_number, worker_id, lease_token, leased_at, lease_until
                )
                VALUES (?, ?, ?, ?, ?, clock_timestamp(), ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, attemptId)
                statement.setObject(2, leased.id)
                statement.setInt(3, leased.attemptCount)
                statement.setString(4, workerId)
                statement.setObject(5, leaseToken)
                statement.setInstant(6, requireNotNull(leased.leaseUntil))
                statement.executeUpdate()
            }
            JobLease(leased, attemptId)
        }
    }

    fun extendLease(
        jobId: UUID,
        workerId: String,
        leaseToken: UUID,
        leaseDuration: Duration,
    ): JobRow {
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        require(leaseDuration <= Duration.ofHours(24))
        return dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE jobs
                SET lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'LEASED'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND lease_until > clock_timestamp()
                  AND cancellation_requested = FALSE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, leaseDuration.toMillis())
                statement.setObject(2, jobId)
                statement.setString(3, workerId)
                statement.setObject(4, leaseToken)
                if (statement.executeUpdate() != 1) throw leaseLost(jobId)
            }
            val updated = get(connection, jobId) ?: error("Extended job disappeared")
            connection.prepareStatement(
                "UPDATE job_attempts SET lease_until = ? WHERE lease_token = ? AND finished_at IS NULL",
            ).use { statement ->
                statement.setInstant(1, requireNotNull(updated.leaseUntil))
                statement.setObject(2, leaseToken)
                statement.executeUpdate()
            }
            updated
        }
    }

    fun complete(
        jobId: UUID,
        workerId: String,
        leaseToken: UUID,
        result: JsonElement = buildJsonObject {},
    ): JobRow = finishLease(
        jobId = jobId,
        workerId = workerId,
        leaseToken = leaseToken,
        succeeded = true,
        retryable = false,
        details = result,
    )

    fun fail(
        jobId: UUID,
        workerId: String,
        leaseToken: UUID,
        error: JsonElement,
        retryable: Boolean,
        retryDelay: Duration = Duration.ZERO,
    ): JobRow {
        require(!retryDelay.isNegative)
        require(retryDelay <= Duration.ofDays(7))
        return finishLease(
            jobId = jobId,
            workerId = workerId,
            leaseToken = leaseToken,
            succeeded = false,
            retryable = retryable,
            details = error,
            retryDelay = retryDelay,
        )
    }

    fun requestCancellation(id: UUID): JobRow =
        dataSource.transaction { connection -> requestCancellation(connection, id) }

    internal fun requestCancellation(connection: Connection, id: UUID): JobRow {
            connection.prepareStatement("SELECT * FROM jobs WHERE id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw PersistenceNotFoundException("Job $id does not exist")
                }
            }
            connection.prepareStatement(
                """
                UPDATE jobs
                SET cancellation_requested = CASE
                        WHEN status IN ('QUEUED', 'LEASED') THEN TRUE
                        ELSE cancellation_requested
                    END,
                    status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END,
                    completed_at = CASE
                        WHEN status = 'QUEUED' THEN clock_timestamp()
                        ELSE completed_at
                    END,
                    updated_at = clock_timestamp()
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                check(statement.executeUpdate() == 1)
            }
            return get(connection, id) ?: error("Cancelled job disappeared")
    }

    /**
     * Marks exhausted expired leases terminal and immediately cancels leased
     * jobs whose cooperative cancellation deadline has passed.
     */
    fun reapExpiredLeases(): Int =
        dataSource.transaction { connection ->
            val expired =
                connection.prepareStatement(
                    """
                    SELECT id, lease_token
                    FROM jobs
                    WHERE status = 'LEASED' AND lease_until <= clock_timestamp()
                    ORDER BY lease_until, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1000
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(result.uuid("id") to result.uuid("lease_token"))
                            }
                        }
                    }
                }
            connection.prepareStatement(
                """
                UPDATE job_attempts
                SET finished_at = clock_timestamp(), outcome = 'LEASE_EXPIRED'
                WHERE lease_token = ? AND finished_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                expired.forEach { (_, token) ->
                    statement.setObject(1, token)
                    statement.addBatch()
                }
                if (expired.isNotEmpty()) statement.executeBatch()
            }
            connection.prepareStatement(
                """
                UPDATE jobs
                SET status = CASE
                        WHEN cancellation_requested THEN 'CANCELLED'
                        WHEN attempt_count >= max_attempts THEN 'FAILED'
                        ELSE 'QUEUED'
                    END,
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    completed_at = CASE
                        WHEN cancellation_requested OR attempt_count >= max_attempts
                            THEN clock_timestamp()
                        ELSE NULL
                    END,
                    updated_at = clock_timestamp()
                WHERE id = ? AND lease_token = ?
                """.trimIndent(),
            ).use { statement ->
                expired.forEach { (id, token) ->
                    statement.setObject(1, id)
                    statement.setObject(2, token)
                    statement.addBatch()
                }
                if (expired.isNotEmpty()) statement.executeBatch()
            }
            expired.size
        }

    private fun finishLease(
        jobId: UUID,
        workerId: String,
        leaseToken: UUID,
        succeeded: Boolean,
        retryable: Boolean,
        details: JsonElement,
        retryDelay: Duration = Duration.ZERO,
    ): JobRow = dataSource.transaction { connection ->
        val current =
            connection.prepareStatement(
                """
                SELECT *
                FROM jobs
                WHERE id = ?
                  AND status = 'LEASED'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND lease_until > clock_timestamp()
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, jobId)
                statement.setString(2, workerId)
                statement.setObject(3, leaseToken)
                statement.executeQuery().use { result ->
                    if (result.next()) mapJob(result) else throw leaseLost(jobId)
                }
            }
        val cancelled = current.cancellationRequested
        val requeue = !succeeded && retryable && !cancelled && current.attemptCount < current.maxAttempts
        val status =
            when {
                cancelled -> JobStatus.CANCELLED
                succeeded -> JobStatus.SUCCEEDED
                requeue -> JobStatus.QUEUED
                else -> JobStatus.FAILED
            }
        connection.prepareStatement(
            """
            UPDATE jobs
            SET status = ?,
                available_at = CASE
                    WHEN ? THEN clock_timestamp() + (? * INTERVAL '1 millisecond')
                    ELSE available_at
                END,
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                result = CAST(? AS jsonb),
                last_error = CAST(? AS jsonb),
                completed_at = CASE
                    WHEN ? THEN NULL
                    ELSE clock_timestamp()
                END,
                updated_at = clock_timestamp()
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status.name)
            statement.setBoolean(2, requeue)
            statement.setLong(3, retryDelay.toMillis())
            statement.setString(4, if (succeeded) details.toString() else null)
            statement.setString(5, if (succeeded) null else details.toString())
            statement.setBoolean(6, requeue)
            statement.setObject(7, jobId)
            check(statement.executeUpdate() == 1)
        }
        val attemptOutcome =
            when {
                cancelled -> "CANCELLED"
                succeeded -> "SUCCEEDED"
                requeue -> "RETRYABLE_FAILURE"
                else -> "FINAL_FAILURE"
            }
        connection.prepareStatement(
            """
            UPDATE job_attempts
            SET finished_at = clock_timestamp(), outcome = ?, details = CAST(? AS jsonb)
            WHERE lease_token = ? AND finished_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, attemptOutcome)
            statement.setString(2, details.toString())
            statement.setObject(3, leaseToken)
            check(statement.executeUpdate() == 1)
        }
        get(connection, jobId) ?: error("Finished job disappeared")
    }

    internal fun get(connection: Connection, id: UUID): JobRow? =
        connection.prepareStatement("SELECT * FROM jobs WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) mapJob(result) else null
            }
        }

    private fun mapJob(result: java.sql.ResultSet): JobRow =
        JobRow(
            id = result.uuid("id"),
            kind = result.getString("kind"),
            resourceType = result.getString("resource_type"),
            resourceId = result.getString("resource_id"),
            payload = result.jsonObject("payload"),
            status = JobStatus.valueOf(result.getString("status")),
            priority = result.getInt("priority"),
            availableAt = result.instant("available_at"),
            maxAttempts = result.getInt("max_attempts"),
            attemptCount = result.getInt("attempt_count"),
            leaseOwner = result.getString("lease_owner"),
            leaseToken = result.getObject("lease_token", UUID::class.java),
            leaseUntil = result.instantOrNull("lease_until"),
            cancellationRequested = result.getBoolean("cancellation_requested"),
            result = result.jsonOrNull("result"),
            lastError = result.jsonOrNull("last_error"),
            createdAt = result.instant("created_at"),
            updatedAt = result.instant("updated_at"),
            completedAt = result.instantOrNull("completed_at"),
        )

    private fun leaseLost(jobId: UUID): LeaseLostException =
        LeaseLostException("Lease for job $jobId is absent, expired, cancelled, or owned by another worker")

    private companion object {
        val IDENTIFIER = Regex("[A-Z][A-Z0-9_]{1,63}")
        val RESOURCE_TYPE = Regex("[a-z][a-z0-9-]{1,63}")
        val WORKER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

class ScheduleRepository(
    private val dataSource: DataSource,
) {
    fun upsert(
        name: String,
        jobKind: String,
        resourceType: String,
        payload: JsonObject,
        cronUtc: String,
        nextFireAt: Instant,
        enabled: Boolean = true,
        id: UUID = UUID.randomUUID(),
    ): JobScheduleRow = dataSource.transaction { connection ->
        connection.prepareStatement(
            """
            INSERT INTO job_schedules (
                id, name, job_kind, resource_type, payload, cron_utc, enabled, next_fire_at
            )
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            ON CONFLICT (name) DO UPDATE
            SET job_kind = EXCLUDED.job_kind,
                resource_type = EXCLUDED.resource_type,
                payload = EXCLUDED.payload,
                cron_utc = EXCLUDED.cron_utc,
                enabled = EXCLUDED.enabled,
                next_fire_at = EXCLUDED.next_fire_at,
                updated_at = clock_timestamp()
            RETURNING *
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, name)
            statement.setString(3, jobKind)
            statement.setString(4, resourceType)
            statement.setString(5, payload.toString())
            statement.setString(6, cronUtc)
            statement.setBoolean(7, enabled)
            statement.setInstant(8, nextFireAt)
            statement.executeQuery().use { result ->
                check(result.next())
                mapSchedule(result)
            }
        }
    }

    fun list(limit: Int = 500): List<JobScheduleRow> {
        require(limit in 1..1_000)
        return dataSource.read { connection ->
            connection.prepareStatement(
                "SELECT * FROM job_schedules ORDER BY name LIMIT ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapSchedule(result))
                    }
                }
            }
        }
    }

    /**
     * Locks one due schedule and advances it atomically. The caller supplies
     * the next UTC occurrence calculated by its pinned cron implementation.
     */
    fun advanceDue(id: UUID, expectedFireAt: Instant, nextFireAt: Instant): JobScheduleRow {
        require(nextFireAt > expectedFireAt)
        return dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE job_schedules
                SET last_fire_at = next_fire_at,
                    next_fire_at = ?,
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND enabled = TRUE
                  AND next_fire_at = ?
                  AND next_fire_at <= clock_timestamp()
                RETURNING *
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(1, nextFireAt)
                statement.setObject(2, id)
                statement.setInstant(3, expectedFireAt)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        throw PersistenceConflictException("Schedule $id is not due at $expectedFireAt")
                    }
                    mapSchedule(result)
                }
            }
        }
    }

    private fun mapSchedule(result: java.sql.ResultSet): JobScheduleRow =
        JobScheduleRow(
            id = result.uuid("id"),
            name = result.getString("name"),
            jobKind = result.getString("job_kind"),
            resourceType = result.getString("resource_type"),
            payload = result.jsonObject("payload"),
            cronUtc = result.getString("cron_utc"),
            enabled = result.getBoolean("enabled"),
            nextFireAt = result.instant("next_fire_at"),
            lastFireAt = result.instantOrNull("last_fire_at"),
            createdAt = result.instant("created_at"),
            updatedAt = result.instant("updated_at"),
        )
}
