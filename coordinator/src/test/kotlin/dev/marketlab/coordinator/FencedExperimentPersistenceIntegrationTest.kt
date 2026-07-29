package dev.marketlab.coordinator

import dev.marketlab.persistence.DataRepository
import dev.marketlab.persistence.Database
import dev.marketlab.persistence.DatabaseConfig
import dev.marketlab.persistence.JobRepository
import dev.marketlab.persistence.LeaseLostException
import dev.marketlab.persistence.PersistenceConflictException
import dev.marketlab.persistence.PromotionStatus
import dev.marketlab.persistence.RunRepository
import dev.marketlab.persistence.RunStatus
import dev.marketlab.persistence.TheoryRepository
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue

class FencedExperimentPersistenceIntegrationTest {
    @Test
    fun `trials artifacts and terminal run commit under the same live lease fence`() {
        withDatabase { database ->
            val fixture = controlPlaneFixture(database)
            val jobs = JobRepository(database.dataSource)
            val lease =
                requireNotNull(
                    jobs.leaseNext(
                        workerId = "fenced-integration-worker",
                        leaseDuration = Duration.ofMinutes(1),
                        acceptedKinds = setOf("EXPERIMENT_RUN"),
                    ),
                )
            assertEquals(fixture.jobId, lease.job.id)
            val fence = ExperimentLeaseFence.from(lease, fixture.runId)
            val persistence = PostgreSqlExperimentPersistence(database.dataSource)
            persistence.markRunning(fence)
            val artifactId = UUID.randomUUID()
            val stored =
                StoredExperimentArtifact(
                    contentHash = sha256("observed-control-evidence:${fixture.runId}"),
                    uri = "file:///tmp/non-market-control-plane-evidence.json",
                    byteCount = 41,
                )
            val runManifest =
                buildJsonObject {
                    put(
                        "artifacts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", artifactId.toString())
                                    put("contentHash", stored.contentHash)
                                },
                            )
                        },
                    )
                    put("leaseToken", fence.leaseToken.toString())
                }
            val succeededTrial =
                TrialWrite(
                    trialNumber = 0,
                    parameters = buildJsonObject { put("lookbackDays", 30) },
                    status = TrialStatus.SUCCEEDED,
                    metrics = buildJsonObject { put("rmse", 0.0125) },
                    failure = null,
                )
            val failedTrial =
                TrialWrite(
                    trialNumber = 1,
                    parameters = buildJsonObject { put("lookbackDays", 60) },
                    status = TrialStatus.FAILED,
                    metrics = null,
                    failure = buildJsonObject { put("code", "CONTROL_PLANE_TEST_FAILURE") },
                )

            val completion =
                persistence.finishRun(
                    fence = fence,
                    status = RunStatus.SUCCEEDED,
                    manifest = runManifest,
                    metrics = buildJsonObject { put("controlPlaneOnly", true) },
                    failure = null,
                    promotionStatus = PromotionStatus.BLOCKED,
                    artifacts =
                        listOf(
                            ArtifactWrite(
                                id = artifactId,
                                kind = "REPORT",
                                artifact = stored,
                                manifest =
                                    buildJsonObject {
                                        put("id", artifactId.toString())
                                        put("leaseToken", fence.leaseToken.toString())
                                    },
                            ),
                        ),
                    trials = listOf(succeededTrial, failedTrial),
                )

            assertEquals(RunStatus.SUCCEEDED, completion.run.status)
            assertEquals(PromotionStatus.BLOCKED, completion.run.promotionStatus)
            assertEquals(listOf(artifactId), completion.artifactIds)
            assertEquals(
                artifactId,
                RunRepository(database.dataSource)
                    .listArtifacts(fixture.runId)
                    .single()
                    .id,
            )
            val persistedTrials = readTrials(database, fixture.runId)
            assertEquals(listOf(0, 1), persistedTrials.map(PersistedTrial::trialNumber))
            assertEquals(TrialStatus.SUCCEEDED.name, persistedTrials[0].status)
            assertEquals(succeededTrial.parameters, persistedTrials[0].parameters)
            assertEquals(succeededTrial.metrics, persistedTrials[0].metrics)
            assertEquals(null, persistedTrials[0].failure)
            assertNotNull(persistedTrials[0].startedAt)
            assertNotNull(persistedTrials[0].completedAt)
            assertEquals(TrialStatus.FAILED.name, persistedTrials[1].status)
            assertEquals(failedTrial.parameters, persistedTrials[1].parameters)
            assertEquals(null, persistedTrials[1].metrics)
            assertEquals(failedTrial.failure, persistedTrials[1].failure)
            assertNotNull(persistedTrials[1].startedAt)
            assertNotNull(persistedTrials[1].completedAt)
            jobs.complete(
                jobId = lease.job.id,
                workerId = requireNotNull(lease.job.leaseOwner),
                leaseToken = lease.token,
            )
        }
    }

    @Test
    fun `expired attempt cannot mutate a run after a replacement lease`() {
        withDatabase { database ->
            val fixture = controlPlaneFixture(database)
            val jobs = JobRepository(database.dataSource)
            val first =
                requireNotNull(
                    jobs.leaseNext(
                        workerId = "stale-integration-worker",
                        leaseDuration = Duration.ofMinutes(1),
                        acceptedKinds = setOf("EXPERIMENT_RUN"),
                    ),
                )
            assertEquals(fixture.jobId, first.job.id)
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET lease_until = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, fixture.jobId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val replacement =
                requireNotNull(
                    jobs.leaseNext(
                        workerId = "replacement-integration-worker",
                        leaseDuration = Duration.ofMinutes(1),
                        acceptedKinds = setOf("EXPERIMENT_RUN"),
                    ),
                )
            val persistence = PostgreSqlExperimentPersistence(database.dataSource)

            assertFailsWith<LeaseLostException> {
                persistence.markRunning(
                    ExperimentLeaseFence.from(first, fixture.runId),
                )
            }
            assertFailsWith<LeaseLostException> {
                persistence.finishRun(
                    fence = ExperimentLeaseFence.from(first, fixture.runId),
                    status = RunStatus.REJECTED,
                    manifest = null,
                    metrics = null,
                    failure = buildJsonObject { put("code", "STALE_ATTEMPT") },
                    promotionStatus = PromotionStatus.BLOCKED,
                    trials =
                        listOf(
                            TrialWrite(
                                trialNumber = 0,
                                parameters = buildJsonObject {},
                                status = TrialStatus.ABANDONED,
                                metrics = null,
                                failure = buildJsonObject { put("code", "STALE_ATTEMPT") },
                            ),
                        ),
                )
            }
            assertEquals(emptyList(), readTrials(database, fixture.runId))
            val replacementFence = ExperimentLeaseFence.from(replacement, fixture.runId)
            assertEquals(
                RunStatus.RUNNING,
                persistence.markRunning(replacementFence).status,
            )
            assertEquals(
                RunStatus.REJECTED,
                persistence.finishRun(
                    fence = replacementFence,
                    status = RunStatus.REJECTED,
                    manifest = buildJsonObject { put("leaseToken", replacement.token.toString()) },
                    metrics = null,
                    failure = buildJsonObject { put("code", "INTEGRATION_TEST_COMPLETE") },
                    promotionStatus = PromotionStatus.BLOCKED,
                ).run.status,
            )
            jobs.fail(
                jobId = replacement.job.id,
                workerId = requireNotNull(replacement.job.leaseOwner),
                leaseToken = replacement.token,
                error = buildJsonObject { put("code", "INTEGRATION_TEST_COMPLETE") },
                retryable = false,
            )
        }
    }

    @Test
    fun `running and identical terminal trials are recovery safe`() {
        withDatabase { database ->
            val fixture = controlPlaneFixture(database)
            val jobs = JobRepository(database.dataSource)
            val lease =
                requireNotNull(
                    jobs.leaseNext(
                        workerId = "trial-recovery-worker",
                        leaseDuration = Duration.ofMinutes(1),
                        acceptedKinds = setOf("EXPERIMENT_RUN"),
                    ),
                )
            val fence = ExperimentLeaseFence.from(lease, fixture.runId)
            val persistence = PostgreSqlExperimentPersistence(database.dataSource)
            persistence.markRunning(fence)
            val runningParameters = buildJsonObject { put("lookbackDays", 30) }
            val replayedParameters = buildJsonObject { put("lookbackDays", 60) }
            val replayedMetrics = buildJsonObject { put("rmse", 0.0125) }
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO run_trials (
                        run_id, trial_number, parameters, status, metrics,
                        failure, started_at, completed_at
                    )
                    VALUES
                        (?, 0, CAST(? AS jsonb), 'RUNNING', NULL, NULL,
                            TIMESTAMPTZ '2026-07-28 00:00:00+00', NULL),
                        (?, 1, CAST(? AS jsonb), 'SUCCEEDED', CAST(? AS jsonb), NULL,
                            TIMESTAMPTZ '2026-07-28 00:01:00+00',
                            TIMESTAMPTZ '2026-07-28 00:02:00+00')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, fixture.runId)
                    statement.setString(2, runningParameters.toString())
                    statement.setObject(3, fixture.runId)
                    statement.setString(4, replayedParameters.toString())
                    statement.setString(5, replayedMetrics.toString())
                    assertEquals(2, statement.executeUpdate())
                }
            }

            val completion =
                persistence.finishRun(
                    fence = fence,
                    status = RunStatus.SUCCEEDED,
                    manifest = buildJsonObject { put("recovered", true) },
                    metrics = buildJsonObject { put("trialCount", 2) },
                    failure = null,
                    promotionStatus = PromotionStatus.BLOCKED,
                    trials =
                        listOf(
                            TrialWrite(
                                trialNumber = 0,
                                parameters = runningParameters,
                                status = TrialStatus.SUCCEEDED,
                                metrics = buildJsonObject { put("rmse", 0.02) },
                                failure = null,
                            ),
                            TrialWrite(
                                trialNumber = 1,
                                parameters = replayedParameters,
                                status = TrialStatus.SUCCEEDED,
                                metrics = replayedMetrics,
                                failure = null,
                            ),
                        ),
                )

            assertEquals(RunStatus.SUCCEEDED, completion.run.status)
            val trials = readTrials(database, fixture.runId)
            assertEquals(
                listOf(TrialStatus.SUCCEEDED.name, TrialStatus.SUCCEEDED.name),
                trials.map(PersistedTrial::status),
            )
            assertEquals(buildJsonObject { put("rmse", 0.02) }, trials[0].metrics)
            assertEquals(replayedMetrics, trials[1].metrics)
            assertEquals("2026-07-28T00:00:00Z", trials[0].startedAt)
            assertEquals("2026-07-28T00:01:00Z", trials[1].startedAt)
            assertEquals("2026-07-28T00:02:00Z", trials[1].completedAt)
        }
    }

    @Test
    fun `conflicting terminal trial rolls back all completion writes`() {
        withDatabase { database ->
            val fixture = controlPlaneFixture(database)
            val jobs = JobRepository(database.dataSource)
            val lease =
                requireNotNull(
                    jobs.leaseNext(
                        workerId = "trial-conflict-worker",
                        leaseDuration = Duration.ofMinutes(1),
                        acceptedKinds = setOf("EXPERIMENT_RUN"),
                    ),
                )
            val fence = ExperimentLeaseFence.from(lease, fixture.runId)
            val persistence = PostgreSqlExperimentPersistence(database.dataSource)
            persistence.markRunning(fence)
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO run_trials (
                        run_id, trial_number, parameters, status, metrics,
                        started_at, completed_at
                    )
                    VALUES (
                        ?, 1, CAST(? AS jsonb), 'SUCCEEDED', CAST(? AS jsonb),
                        statement_timestamp(), statement_timestamp()
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, fixture.runId)
                    statement.setString(2, buildJsonObject { put("lookbackDays", 60) }.toString())
                    statement.setString(3, buildJsonObject { put("rmse", 0.25) }.toString())
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertFailsWith<PersistenceConflictException> {
                persistence.finishRun(
                    fence = fence,
                    status = RunStatus.SUCCEEDED,
                    manifest = buildJsonObject { put("shouldCommit", false) },
                    metrics = buildJsonObject { put("trialCount", 2) },
                    failure = null,
                    promotionStatus = PromotionStatus.BLOCKED,
                    trials =
                        listOf(
                            TrialWrite(
                                trialNumber = 0,
                                parameters = buildJsonObject { put("lookbackDays", 30) },
                                status = TrialStatus.SUCCEEDED,
                                metrics = buildJsonObject { put("rmse", 0.1) },
                                failure = null,
                            ),
                            TrialWrite(
                                trialNumber = 1,
                                parameters = buildJsonObject { put("lookbackDays", 60) },
                                status = TrialStatus.SUCCEEDED,
                                metrics = buildJsonObject { put("rmse", 0.125) },
                                failure = null,
                            ),
                        ),
                )
            }

            assertEquals(RunStatus.RUNNING, RunRepository(database.dataSource).get(fixture.runId)?.status)
            val trials = readTrials(database, fixture.runId)
            assertEquals(listOf(1), trials.map(PersistedTrial::trialNumber))
            assertEquals(buildJsonObject { put("rmse", 0.25) }, trials.single().metrics)
        }
    }

    private fun controlPlaneFixture(database: Database): ControlPlaneFixture {
        // This test contains no prices or market rows; it exercises only job/run fencing.
        val suffix = UUID.randomUUID().toString()
        val theory =
            TheoryRepository(database.dataSource).register(
                theoryId = "fenced-control-$suffix",
                version = "1",
                planHash = sha256("plan:$suffix"),
                descriptor = buildJsonObject { put("testKind", "control-plane-only") },
                plan = buildJsonObject { put("testKind", "control-plane-only") },
            )
        val snapshot =
            DataRepository(database.dataSource).createSnapshot(
                manifestHash = sha256("snapshot:$suffix"),
                requirements = emptyList(),
                objectIds = emptyList(),
            )
        val run =
            RunRepository(database.dataSource).create(
                theory = theory,
                snapshotId = snapshot.id,
                parameters = buildJsonObject {},
            )
        val job =
            JobRepository(database.dataSource).enqueue(
                kind = "EXPERIMENT_RUN",
                resourceType = "run",
                resourceId = run.id.toString(),
                payload = buildJsonObject { put("runId", run.id.toString()) },
                priority = 1_000,
            )
        return ControlPlaneFixture(run.id, job.id)
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

    private fun readTrials(
        database: Database,
        runId: UUID,
    ): List<PersistedTrial> =
        database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    trial_number,
                    parameters,
                    status,
                    metrics,
                    failure,
                    started_at,
                    completed_at
                FROM run_trials
                WHERE run_id = ?
                ORDER BY trial_number
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, runId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                PersistedTrial(
                                    trialNumber = result.getInt("trial_number"),
                                    parameters =
                                        JSON.parseToJsonElement(
                                            result.getString("parameters"),
                                        ).jsonObject,
                                    status = result.getString("status"),
                                    metrics =
                                        result.getString("metrics")
                                            ?.let(JSON::parseToJsonElement),
                                    failure =
                                        result.getString("failure")
                                            ?.let(JSON::parseToJsonElement),
                                    startedAt =
                                        result
                                            .getObject("started_at", OffsetDateTime::class.java)
                                            ?.toInstant()
                                            ?.toString(),
                                    completedAt =
                                        result
                                            .getObject("completed_at", OffsetDateTime::class.java)
                                            ?.toInstant()
                                            ?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class ControlPlaneFixture(
        val runId: UUID,
        val jobId: UUID,
    )

    private data class PersistedTrial(
        val trialNumber: Int,
        val parameters: JsonObject,
        val status: String,
        val metrics: JsonElement?,
        val failure: JsonElement?,
        val startedAt: String?,
        val completedAt: String?,
    )

    private companion object {
        val JSON = Json
    }
}
