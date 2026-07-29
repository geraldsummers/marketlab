package dev.marketlab.coordinator

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.DataQualityIssue
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.data.SourceRequest
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.data.hyperliquid.StoredHyperliquidSnapshotReader
import dev.marketlab.data.storage.ContentAddressedDataStore
import dev.marketlab.data.storage.RawObjectDescriptor
import dev.marketlab.persistence.ExperimentRunRow
import dev.marketlab.persistence.JobLease
import dev.marketlab.persistence.JobRow
import dev.marketlab.persistence.JobStatus
import dev.marketlab.persistence.PromotionStatus
import dev.marketlab.persistence.RunStatus
import dev.marketlab.persistence.SnapshotRow
import dev.marketlab.persistence.SnapshotStatus
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class ControlExperimentJobHandlerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `all registered controls use immutable observed candles and persist blocked evidence`() =
        runBlocking {
            val fixture = realSnapshotFixture()
            assertEquals(REAL_CAPTURE_SHA256, fixture.snapshot.objects.single().contentHash.hex)
            val theoryIds =
                listOf(
                    "control-random-walk",
                    "control-historical-mean",
                    "control-persistence",
                )
            theoryIds.forEachIndexed { index, theoryId ->
                val run = runRow(theoryId, fixture.snapshotRow.id, index)
                val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
                val handler =
                    ControlExperimentJobHandler(
                        persistence = persistence,
                        snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                        artifacts =
                            ExperimentArtifactStore(
                                temporaryDirectory.resolve("artifacts-$index"),
                            ),
                        sourceRevision = TEST_SOURCE_REVISION,
                        config = TEST_CONFIG,
                    )

                val outcome = handler.execute(leaseFor(run, index))

                assertTrue(outcome is JobExecutionResult.Succeeded, outcome.toString())
                val succeeded = assertIs<JobExecutionResult.Succeeded>(outcome)
                assertEquals("true", succeeded.result.getValue("realDataOnly").jsonPrimitive.content)
                assertEquals(PromotionStatus.BLOCKED.name, succeeded.result
                    .getValue("promotionStatus").jsonPrimitive.content)
                assertEquals(RunStatus.SUCCEEDED, persistence.run.status)
                assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
                assertEquals(2, persistence.artifacts.size)
                assertEquals(setOf("PREDICTIONS", "REPORT"), persistence.artifacts.map { it.kind }.toSet())

                val predictionWrite =
                    persistence.artifacts.single { it.kind == "PREDICTIONS" }
                val predictionJson = readJson(predictionWrite.artifact.uri)
                assertEquals(true, predictionJson.getValue("realDataOnly").jsonPrimitive.content.toBoolean())
                assertEquals("hyperliquid-mainnet", predictionJson.getValue("source").jsonPrimitive.content)
                val observations = predictionJson.getValue("observations").jsonArray
                assertEquals(2, observations.size)
                val actual = observations.map {
                    it.jsonObject.getValue("actualLogReturn").jsonPrimitive.double
                }
                assertEquals(
                    listOf(
                        ln(64_361.0 / 64_123.0),
                        ln(65_366.0 / 64_361.0),
                    ),
                    actual,
                )
                if (theoryId == "control-random-walk") {
                    assertTrue(
                        observations.all {
                            it.jsonObject
                                .getValue("candidateForecast")
                                .jsonPrimitive
                                .double == 0.0
                        },
                    )
                }
                persistence.artifacts.forEach { write ->
                    val path = Path.of(URI(write.artifact.uri))
                    assertTrue(Files.isRegularFile(path))
                    assertEquals(write.artifact.byteCount, Files.size(path))
                }
            }
        }

    @Test
    fun `tampered embedded snapshot cannot inherit a trusted manifest hash`() =
        runBlocking {
            val fixture = realSnapshotFixture()
            val tamperedContract =
                fixture.snapshot.copy(
                    quality =
                        DataQualityReport(
                            checkedAt = fixture.snapshot.quality.checkedAt,
                            issues =
                                listOf(
                                    DataQualityIssue(
                                        kind = QualityIssueKind.SOURCE_REVISION,
                                        severity = QualitySeverity.WARNING,
                                        message = "database metadata was altered",
                                    ),
                                ),
                        ),
                )
            val tamperedRow =
                fixture.snapshotRow.copy(
                    metadata =
                        snapshotMetadata(
                            tamperedContract,
                        ),
                )
            val run = runRow("control-random-walk", tamperedRow.id, 99)
            val persistence = FakeExperimentPersistence(run, tamperedRow)
            val handler =
                ControlExperimentJobHandler(
                    persistence = persistence,
                    snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                    artifacts = ExperimentArtifactStore(temporaryDirectory.resolve("tamper-artifacts")),
                    sourceRevision = TEST_SOURCE_REVISION,
                    config = TEST_CONFIG,
                )

            val outcome = handler.execute(leaseFor(run, 99))

            val failed = assertIs<JobExecutionResult.Failed>(outcome)
            assertEquals("IMMUTABLE_DATA_REPLAY_FAILED", failed.code)
            assertEquals(RunStatus.FAILED, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertTrue(persistence.artifacts.isEmpty())
        }

    private fun realSnapshotFixture(): RealSnapshotFixture {
        val store = ContentAddressedDataStore(temporaryDirectory.resolve("raw"))
        val raw = REAL_CANDLES_JSON.toByteArray(Charsets.UTF_8)
        val manifest =
            store.putRaw(
                raw,
                RawObjectDescriptor(
                    source = DataSourceId("hyperliquid-mainnet"),
                    request =
                        SourceRequest(
                            method = "POST",
                            uri = "https://api.hyperliquid.xyz/info",
                            parameters = mapOf("body" to REAL_REQUEST),
                        ),
                    retrievedAt = RETRIEVED_AT,
                    schemaVersion = "hyperliquid-info-v1",
                    adapterVersion = "hyperliquid-rest-v1",
                    rowCount = 10,
                    eventTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_332_800_000L),
                            MarketTimestamp(1_785_196_800_000L),
                        ),
                    availabilityTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_419_200_000L),
                            MarketTimestamp(1_785_196_800_001L),
                        ),
                    production = true,
                ),
            )
        val snapshot =
            store.createSnapshot(
                createdAt = RETRIEVED_AT,
                requirements =
                    listOf(
                        DataRequirement(
                            key = "hyperliquid_candles",
                            observation = ObservationKind.CANDLE,
                            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                            sampling = Sampling.FixedDuration(DAY_MILLIS),
                            requiredFields = listOf("close"),
                            minimumHistoryMillis = 10L * DAY_MILLIS,
                        ),
                    ),
                objects = listOf(manifest),
                quality =
                    DataQualityReport(
                        checkedAt = MarketTimestamp(RETRIEVED_AT.toEpochMilli()),
                        issues = emptyList(),
                    ),
            )
        val row =
            SnapshotRow(
                id = UUID.fromString("00000000-0000-0000-0000-000000000123"),
                manifestHash = snapshot.manifestHash.hex,
                status = SnapshotStatus.READY,
                createdAt = RETRIEVED_AT,
                metadata = snapshotMetadata(snapshot),
                objectCount = snapshot.objects.size,
                fatalFindingCount = 0,
            )
        return RealSnapshotFixture(store, snapshot, row)
    }

    private fun snapshotMetadata(snapshot: DataSnapshot): JsonObject =
        buildJsonObject {
            put("contractSnapshotId", snapshot.id.value)
            put(
                "contractSnapshot",
                JSON.encodeToJsonElement(DataSnapshot.serializer(), snapshot),
            )
        }

    private fun runRow(
        theoryId: String,
        snapshotId: UUID,
        ordinal: Int,
    ): ExperimentRunRow =
        ExperimentRunRow(
            id = UUID.nameUUIDFromBytes("$theoryId:$ordinal".toByteArray()),
            theoryId = theoryId,
            theoryVersion = "1.0.0",
            theoryPlanHash = "a".repeat(64),
            snapshotId = snapshotId,
            status = RunStatus.QUEUED,
            promotionStatus = PromotionStatus.NOT_EVALUATED,
            parameters = buildJsonObject {},
            manifest = null,
            metrics = null,
            failure = null,
            createdAt = RETRIEVED_AT,
            startedAt = null,
            completedAt = null,
        )

    private fun leaseFor(
        run: ExperimentRunRow,
        ordinal: Int,
    ): JobLease {
        val jobId = UUID.nameUUIDFromBytes("job:$ordinal".toByteArray())
        val leaseToken = UUID.nameUUIDFromBytes("lease:$ordinal".toByteArray())
        val job =
            JobRow(
                id = jobId,
                kind = "EXPERIMENT_RUN",
                resourceType = "run",
                resourceId = run.id.toString(),
                payload =
                    buildJsonObject {
                        put("runId", run.id.toString())
                        put("theoryId", run.theoryId)
                        put("theoryVersion", run.theoryVersion)
                        put("theoryPlanHash", run.theoryPlanHash)
                        put("snapshotId", run.snapshotId.toString())
                        put("parameters", run.parameters)
                    },
                status = JobStatus.LEASED,
                priority = 0,
                availableAt = RETRIEVED_AT,
                maxAttempts = 3,
                attemptCount = 1,
                leaseOwner = "control-test-worker",
                leaseToken = leaseToken,
                leaseUntil = RETRIEVED_AT.plusSeconds(120),
                cancellationRequested = false,
                result = null,
                lastError = null,
                createdAt = RETRIEVED_AT,
                updatedAt = RETRIEVED_AT,
                completedAt = null,
            )
        return JobLease(
            job = job,
            attemptId = UUID.nameUUIDFromBytes("attempt:$ordinal".toByteArray()),
        )
    }

    private fun readJson(uri: String): JsonObject =
        JSON.parseToJsonElement(
            Files.readString(
                Path.of(URI(uri)),
                Charsets.UTF_8,
            ),
        ).jsonObject

    private data class RealSnapshotFixture(
        val store: ContentAddressedDataStore,
        val snapshot: DataSnapshot,
        val snapshotRow: SnapshotRow,
    )

    private class FakeExperimentPersistence(
        initialRun: ExperimentRunRow,
        private val snapshot: SnapshotRow,
    ) : ExperimentPersistence {
        var run = initialRun
        val artifacts = mutableListOf<ArtifactWrite>()

        override fun getRun(id: UUID): ExperimentRunRow? = run.takeIf { it.id == id }

        override fun markRunning(fence: ExperimentLeaseFence): ExperimentRunRow {
            assertEquals(run.id, fence.runId)
            run = run.copy(status = RunStatus.RUNNING, startedAt = RETRIEVED_AT)
            return run
        }

        override fun finishRun(
            fence: ExperimentLeaseFence,
            status: RunStatus,
            manifest: kotlinx.serialization.json.JsonElement?,
            metrics: kotlinx.serialization.json.JsonElement?,
            failure: kotlinx.serialization.json.JsonElement?,
            promotionStatus: PromotionStatus,
            artifacts: List<ArtifactWrite>,
            trials: List<TrialWrite>,
        ): FencedRunCompletion {
            assertEquals(run.id, fence.runId)
            assertEquals(emptyList(), trials)
            val manifestIds =
                manifest
                    ?.jsonObject
                    ?.get("artifacts")
                    ?.jsonArray
                    ?.map { UUID.fromString(it.jsonObject.getValue("id").jsonPrimitive.content) }
                    .orEmpty()
            if (artifacts.isNotEmpty()) {
                assertEquals(artifacts.map(ArtifactWrite::id), manifestIds)
            }
            this.artifacts += artifacts
            run =
                run.copy(
                    status = status,
                    promotionStatus = promotionStatus,
                    manifest = manifest,
                    metrics = metrics,
                    failure = failure,
                    completedAt = RETRIEVED_AT,
                )
            return FencedRunCompletion(run, artifacts.map(ArtifactWrite::id))
        }

        override fun getSnapshot(id: UUID): SnapshotRow? = snapshot.takeIf { it.id == id }
    }

    private companion object {
        val JSON =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }
        val RETRIEVED_AT: Instant = Instant.parse("2026-07-28T12:00:00Z")
        val TEST_CONFIG =
            ControlExperimentConfig(
                minimumTrainingRows = 4,
                testRows = 1,
                stepRows = 1,
                foldCount = 2,
                purgeMillis = DAY_MILLIS,
                embargoMillis = DAY_MILLIS,
                sealedHoldoutRows = 1,
                hacLag = 1,
            )
        const val DAY_MILLIS = 86_400_000L
        const val REAL_CAPTURE_SHA256 =
            "cc20c6d5e221f5f3831fcf7b74cf46f2f6fb6ce09219b5bf07fb47f07a5c28d2"
        const val TEST_SOURCE_REVISION =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val REAL_REQUEST =
            """{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1d","startTime":1784332800000,"endTime":1785283200000}}"""

        /*
         * Exact response bytes from Hyperliquid mainnet /info candleSnapshot,
         * requested for BTC 1d on 2026-07-28. Values are observations, not
         * generated fixtures.
         */
        const val REAL_CANDLES_JSON =
            """[{"t":1784332800000,"T":1784419199999,"s":"BTC","i":"1d","o":"63927.0","c":"64827.0","h":"64873.0","l":"63873.0","v":"12085.92978","n":147009},{"t":1784419200000,"T":1784505599999,"s":"BTC","i":"1d","o":"64828.0","c":"64718.0","h":"64957.0","l":"64275.0","v":"14070.03176","n":160779},{"t":1784505600000,"T":1784591999999,"s":"BTC","i":"1d","o":"64719.0","c":"65226.0","h":"65778.0","l":"63730.0","v":"42735.9526","n":360891},{"t":1784592000000,"T":1784678399999,"s":"BTC","i":"1d","o":"65227.0","c":"66527.0","h":"66918.0","l":"65124.0","v":"31474.47357","n":333726},{"t":1784678400000,"T":1784764799999,"s":"BTC","i":"1d","o":"66526.0","c":"66086.0","h":"66714.0","l":"65534.0","v":"29966.73257","n":332005},{"t":1784764800000,"T":1784851199999,"s":"BTC","i":"1d","o":"66086.0","c":"65069.0","h":"66295.0","l":"64637.0","v":"26441.77955","n":306098},{"t":1784851200000,"T":1784937599999,"s":"BTC","i":"1d","o":"65070.0","c":"64123.0","h":"65795.0","l":"63730.0","v":"33123.68641","n":311224},{"t":1784937600000,"T":1785023999999,"s":"BTC","i":"1d","o":"64122.0","c":"64361.0","h":"64434.0","l":"63736.0","v":"10980.43849","n":126469},{"t":1785024000000,"T":1785110399999,"s":"BTC","i":"1d","o":"64361.0","c":"65366.0","h":"65561.0","l":"64262.0","v":"11484.93585","n":148676},{"t":1785110400000,"T":1785196799999,"s":"BTC","i":"1d","o":"65372.0","c":"63736.0","h":"65715.0","l":"63576.0","v":"31030.43653","n":339910}]"""
    }
}
