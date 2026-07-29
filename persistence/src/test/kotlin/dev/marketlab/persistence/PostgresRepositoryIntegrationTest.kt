package dev.marketlab.persistence

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PostgresRepositoryIntegrationTest {
    @Test
    fun `expired jobs are leased at least once with fencing tokens`() {
        withDatabase { database ->
            val jobs = JobRepository(database.dataSource)
            val kind = "TEST_${UUID.randomUUID().toString().replace("-", "").uppercase()}"
            val job =
                jobs.enqueue(
                    kind = kind,
                    resourceType = "test-control",
                    resourceId = UUID.randomUUID().toString(),
                    payload = buildJsonObject { put("purpose", "lease-test") },
                )

            val first =
                requireNotNull(
                    jobs.leaseNext(
                        "integration-worker-1",
                        Duration.ofMinutes(1),
                        setOf(kind),
                    ),
                )
            assertEquals(job.id, first.job.id)
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, job.id)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val second =
                requireNotNull(
                    jobs.leaseNext(
                        "integration-worker-2",
                        Duration.ofMinutes(1),
                        setOf(kind),
                    ),
                )

            assertNotEquals(first.token, second.token)
            assertEquals(2, second.job.attemptCount)
            assertFailsWith<LeaseLostException> {
                jobs.complete(job.id, "integration-worker-1", first.token)
            }
            assertEquals(
                JobStatus.SUCCEEDED,
                jobs.complete(job.id, "integration-worker-2", second.token).status,
            )
        }
    }

    @Test
    fun `ingestion submission is atomic and idempotent without market observations`() {
        withDatabase { database ->
            val submissions = SubmissionRepository(database.dataSource)
            val key = "integration-${UUID.randomUUID()}"
            val first =
                submissions.submitIngestion(
                    scope = "integration-ingestion",
                    idempotencyKey = key,
                    requestHash = sha256ForTest("same-request"),
                    payload = buildJsonObject { put("source", "HYPERLIQUID_REST") },
                )
            val replay =
                submissions.submitIngestion(
                    scope = "integration-ingestion",
                    idempotencyKey = key,
                    requestHash = sha256ForTest("same-request"),
                    payload = buildJsonObject { put("source", "HYPERLIQUID_REST") },
                )

            assertEquals(first.resourceId, replay.resourceId)
            assertEquals(first.job.id, replay.job.id)
            assertTrue(replay.replayed)
            assertFailsWith<PersistenceConflictException> {
                submissions.submitIngestion(
                    scope = "integration-ingestion",
                    idempotencyKey = key,
                    requestHash = sha256ForTest("different-request"),
                    payload = buildJsonObject { put("source", "HYPERLIQUID_REST") },
                )
            }
        }
    }

    @Test
    fun `experiment cancellation overrides a raced successful run and blocks promotion`() {
        withDatabase { database ->
            val suffix = UUID.randomUUID().toString()
            val theory =
                TheoryRepository(database.dataSource).register(
                    theoryId = "cancellation-$suffix",
                    version = "1",
                    planHash = sha256ForTest("plan-$suffix"),
                    descriptor = buildJsonObject { put("purpose", "control-plane-test") },
                    plan = buildJsonObject { put("purpose", "control-plane-test") },
                )
            val snapshot =
                DataRepository(database.dataSource).createSnapshot(
                    manifestHash = sha256ForTest("snapshot-$suffix"),
                    requirements = emptyList(),
                    objectIds = emptyList(),
                )
            val runs = RunRepository(database.dataSource)
            val run =
                runs.create(
                    theory = theory,
                    snapshotId = snapshot.id,
                    parameters = buildJsonObject {},
                )
            val jobs = JobRepository(database.dataSource)
            val job =
                jobs.enqueue(
                    kind = "EXPERIMENT_RUN",
                    resourceType = "run",
                    resourceId = run.id.toString(),
                    payload = buildJsonObject { put("runId", run.id.toString()) },
                    priority = 1_000,
                )
            val lease =
                requireNotNull(
                    jobs.leaseNext(
                        workerId = "cancellation-worker",
                        leaseDuration = Duration.ofMinutes(1),
                        acceptedKinds = setOf("EXPERIMENT_RUN"),
                    ),
                )
            assertEquals(job.id, lease.job.id)
            runs.markRunning(run.id)
            runs.finish(
                id = run.id,
                status = RunStatus.SUCCEEDED,
                manifest = buildJsonObject { put("result", "persisted-before-cancel") },
                metrics = buildJsonObject {},
                failure = null,
                promotionStatus = PromotionStatus.PASSED,
            )

            val cancelledJob = jobs.requestCancellation(job.id)
            val cancelledRun = requireNotNull(runs.get(run.id))

            assertTrue(cancelledJob.cancellationRequested)
            assertEquals(RunStatus.CANCELLED, cancelledRun.status)
            assertEquals(PromotionStatus.BLOCKED, cancelledRun.promotionStatus)
            assertEquals(
                JobStatus.CANCELLED,
                jobs.fail(
                    jobId = job.id,
                    workerId = "cancellation-worker",
                    leaseToken = lease.token,
                    error = buildJsonObject { put("code", "CANCELLED_BY_TEST") },
                    retryable = false,
                ).status,
            )
        }
    }

    private fun withDatabase(block: (Database) -> Unit) {
        val url = System.getenv("MARKETLAB_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "MARKETLAB_TEST_DATABASE_URL is not configured")
        val config =
            DatabaseConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_DATABASE_URL" to requireNotNull(url),
                    "MARKETLAB_DATABASE_USER" to
                        (System.getenv("MARKETLAB_TEST_DATABASE_USER") ?: "marketlab"),
                    "MARKETLAB_DATABASE_PASSWORD" to
                        (System.getenv("MARKETLAB_TEST_DATABASE_PASSWORD") ?: "marketlab"),
                    "MARKETLAB_DATABASE_POOL_SIZE" to "2",
                ),
            )
        Database.open(config).use(block)
    }

    private fun sha256ForTest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
