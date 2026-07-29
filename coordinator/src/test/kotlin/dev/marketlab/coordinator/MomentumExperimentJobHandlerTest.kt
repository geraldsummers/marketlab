package dev.marketlab.coordinator

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
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
import dev.marketlab.theories.AcademicTheoryRegistry
import dev.marketlab.theory.TheoryPlanHasher
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class MomentumExperimentJobHandlerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `production configuration is the frozen registered design`() {
        val config = MomentumExperimentConfig()

        assertEquals(3 * 365, config.minimumSourceRows)
        assertEquals(365, config.lookbackRows)
        assertEquals(60, config.volatilityRows)
        assertEquals(30, config.targetRows)
        assertEquals(90, config.sealedHoldoutRows)
        assertEquals(180, config.minimumTrainingRows)
        assertEquals(60, config.testRows)
        assertEquals(60, config.stepRows)
        assertEquals(5, config.foldCount)
        assertEquals(30L * DAY_MILLIS, config.purgeMillis)
        assertEquals(30L * DAY_MILLIS, config.embargoMillis)
        assertEquals(29, config.hacLag)
        assertEquals(0.05, config.predictiveAlpha)
    }

    @Test
    fun `exact observed BTC candles produce causal forecasts against both controls and one trial`() =
        runBlocking {
            val fixture = realSnapshotFixture()
            assertEquals(REAL_CAPTURE_SHA256, fixture.snapshot.objects.single().contentHash.hex)
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                MomentumExperimentJobHandler(
                    persistence = persistence,
                    snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                    artifacts = ExperimentArtifactStore(temporaryDirectory.resolve("artifacts")),
                    sourceRevision = TEST_SOURCE_REVISION,
                    expectedPlanHash = MOMENTUM_PLAN_HASH,
                    config = TEST_CONFIG,
                )

            val outcome = handler.execute(leaseFor(run))

            val succeeded = assertIs<JobExecutionResult.Succeeded>(outcome, outcome.toString())
            assertEquals(RunStatus.SUCCEEDED, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertEquals(PromotionStatus.BLOCKED.name, succeeded.result
                .getValue("promotionStatus").jsonPrimitive.content)
            assertEquals(2, persistence.artifacts.size)
            assertEquals(1, persistence.trials.size)
            assertEquals(TrialStatus.SUCCEEDED, persistence.trials.single().status)
            assertEquals(buildJsonObject {}, persistence.trials.single().parameters)
            assertEquals(persistence.run.metrics, persistence.trials.single().metrics)

            val predictions =
                readJson(
                    persistence.artifacts.single { it.kind == "PREDICTIONS" }.artifact.uri,
                )
            val observations = predictions.getValue("observations").jsonArray
            assertEquals(3, observations.size)
            val first = observations.first().jsonObject
            assertEquals("DEVELOPMENT_OOS", first.getValue("phase").jsonPrimitive.content)
            assertEquals(
                ln(65_069.0 / 66_527.0),
                first.getValue("return_365d").jsonPrimitive.double,
                absoluteTolerance = 1e-15,
            )
            val firstTrailingReturn = ln(66_086.0 / 66_527.0)
            val secondTrailingReturn = ln(65_069.0 / 66_086.0)
            assertEquals(
                kotlin.math.abs(firstTrailingReturn - secondTrailingReturn) / sqrt(2.0),
                first.getValue("volatility_60d").jsonPrimitive.double,
                absoluteTolerance = 1e-15,
            )
            assertEquals(
                ln(64_123.0 / 65_069.0),
                first.getValue("actualLogReturn30d").jsonPrimitive.double,
                absoluteTolerance = 1e-15,
            )
            assertEquals(
                1_784_851_200_000L,
                first.getValue("decisionTimeEpochMillis").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                1_784_937_600_000L,
                first.getValue("labelToEpochMillis").jsonPrimitive.content.toLong(),
            )
            observations.forEach { observation ->
                val value = observation.jsonObject
                assertTrue(value.getValue("candidateForecast").jsonPrimitive.double.isFinite())
                assertTrue(value.getValue("historicalMeanForecast").jsonPrimitive.double.isFinite())
                assertEquals(0.0, value.getValue("zeroReturnForecast").jsonPrimitive.double)
            }
            val holdoutObservations =
                observations
                    .map { it.jsonObject }
                    .filter { it.getValue("phase").jsonPrimitive.content == "SEALED_HOLDOUT" }
            assertEquals(1, holdoutObservations.size)
            assertEquals(
                holdoutObservations.size,
                holdoutObservations.map { it.getValue("rowId").jsonPrimitive.content }.distinct().size,
            )

            val report =
                readJson(
                    persistence.artifacts.single { it.kind == "REPORT" }.artifact.uri,
                )
            assertEquals(
                true,
                report.getValue("sealedHoldoutEvaluated").jsonPrimitive.content.toBoolean(),
            )
            assertEquals(1, report.getValue("sealedHoldoutRows").jsonPrimitive.content.toInt())
            assertEquals(1, report.getValue("holdoutBoundaryPurgedRows").jsonPrimitive.content.toInt())
            val holdoutFrom =
                report.getValue("sealedHoldoutFromEpochMillis").jsonPrimitive.content.toLong()
            observations
                .map { it.jsonObject }
                .filter { it.getValue("phase").jsonPrimitive.content == "DEVELOPMENT_OOS" }
                .forEach { development ->
                    val labelTo =
                        development.getValue("labelToEpochMillis").jsonPrimitive.content.toLong()
                    assertTrue(labelTo <= holdoutFrom - DAY_MILLIS)
                    assertTrue(labelTo + DAY_MILLIS <= holdoutFrom)
                }
            assertEquals(
                false,
                report
                    .getValue("validation")
                    .jsonObject
                    .getValue("registeredGlobalSpaEvaluated")
                    .jsonPrimitive
                    .content
                    .toBoolean(),
            )
            persistence.artifacts.forEach { write ->
                val path = Path.of(URI(write.artifact.uri))
                assertTrue(Files.isRegularFile(path))
                assertEquals(write.artifact.byteCount, Files.size(path))
            }
        }

    @Test
    fun `insufficient exact real history is inconclusive without a fitted trial`() =
        runBlocking {
            val fixture = realSnapshotFixture()
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                MomentumExperimentJobHandler(
                    persistence = persistence,
                    snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                    artifacts = ExperimentArtifactStore(temporaryDirectory.resolve("short-artifacts")),
                    sourceRevision = TEST_SOURCE_REVISION,
                    expectedPlanHash = MOMENTUM_PLAN_HASH,
                    config = MomentumExperimentConfig(minimumSourceRows = 12),
                )

            val outcome = handler.execute(leaseFor(run))

            val failed = assertIs<JobExecutionResult.Failed>(outcome)
            assertEquals("INSUFFICIENT_REAL_DATA", failed.code)
            assertEquals(RunStatus.INCONCLUSIVE, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertTrue(persistence.artifacts.isEmpty())
            assertTrue(persistence.trials.isEmpty())
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
                    rowCount = 12,
                    eventTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_160_000_000L),
                            MarketTimestamp(1_785_196_800_000L),
                        ),
                    availabilityTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_246_400_000L),
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
                            key = "hyperliquid_btc_daily_candles",
                            observation = ObservationKind.CANDLE,
                            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                            sampling = Sampling.FixedDuration(DAY_MILLIS),
                            requiredFields = listOf("close"),
                            minimumHistoryMillis = 12L * DAY_MILLIS,
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
                id = UUID.fromString("00000000-0000-0000-0000-000000000321"),
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

    private fun runRow(snapshotId: UUID): ExperimentRunRow =
        ExperimentRunRow(
            id = UUID.fromString("00000000-0000-0000-0000-000000000322"),
            theoryId = HYPERLIQUID_BTC_MOMENTUM_THEORY_ID,
            theoryVersion = "1.0.0",
            theoryPlanHash = MOMENTUM_PLAN_HASH,
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

    private fun leaseFor(run: ExperimentRunRow): JobLease {
        val jobId = UUID.fromString("00000000-0000-0000-0000-000000000323")
        val leaseToken = UUID.fromString("00000000-0000-0000-0000-000000000324")
        return JobLease(
            job =
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
                    leaseOwner = "momentum-test-worker",
                    leaseToken = leaseToken,
                    leaseUntil = RETRIEVED_AT.plusSeconds(120),
                    cancellationRequested = false,
                    result = null,
                    lastError = null,
                    createdAt = RETRIEVED_AT,
                    updatedAt = RETRIEVED_AT,
                    completedAt = null,
                ),
            attemptId = UUID.fromString("00000000-0000-0000-0000-000000000325"),
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
        val trials = mutableListOf<TrialWrite>()

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
            this.artifacts += artifacts
            this.trials += trials
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
        val RETRIEVED_AT: Instant = Instant.parse("2026-07-28T05:37:44Z")
        val MOMENTUM_PLAN_HASH: String =
            TheoryPlanHasher.hash(
                requireNotNull(
                    AcademicTheoryRegistry.find(
                        TheoryId(HYPERLIQUID_BTC_MOMENTUM_THEORY_ID),
                    ),
                ).compile(),
            ).hex
        val TEST_CONFIG =
            MomentumExperimentConfig(
                minimumSourceRows = 12,
                lookbackRows = 2,
                volatilityRows = 2,
                targetRows = 1,
                sealedHoldoutRows = 1,
                minimumTrainingRows = 4,
                testRows = 1,
                stepRows = 1,
                foldCount = 2,
                purgeMillis = DAY_MILLIS,
                embargoMillis = DAY_MILLIS,
                hacLag = 1,
            )
        const val DAY_MILLIS = 86_400_000L
        const val REAL_CAPTURE_SHA256 =
            "6d1d2a3279ad1cf1df01765216e304fa7b9edda66db32b7bd6f3ba4f188dbb09"
        const val TEST_SOURCE_REVISION =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val REAL_REQUEST =
            """{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1d","startTime":1784160000000,"endTime":1785196799999}}"""

        /*
         * Exact response bytes from Hyperliquid mainnet /info candleSnapshot,
         * requested for BTC 1d on 2026-07-28. Values are observations, not a
         * generated price path.
         */
        const val REAL_CANDLES_JSON =
            """[{"t":1784160000000,"T":1784246399999,"s":"BTC","i":"1d","o":"64738.0","c":"63815.0","h":"64999.0","l":"63723.0","v":"25997.68696","n":269141},{"t":1784246400000,"T":1784332799999,"s":"BTC","i":"1d","o":"63815.0","c":"63928.0","h":"64398.0","l":"62550.0","v":"30725.18854","n":319509},{"t":1784332800000,"T":1784419199999,"s":"BTC","i":"1d","o":"63927.0","c":"64827.0","h":"64873.0","l":"63873.0","v":"12085.92978","n":147009},{"t":1784419200000,"T":1784505599999,"s":"BTC","i":"1d","o":"64828.0","c":"64718.0","h":"64957.0","l":"64275.0","v":"14070.03176","n":160779},{"t":1784505600000,"T":1784591999999,"s":"BTC","i":"1d","o":"64719.0","c":"65226.0","h":"65778.0","l":"63730.0","v":"42735.9526","n":360891},{"t":1784592000000,"T":1784678399999,"s":"BTC","i":"1d","o":"65227.0","c":"66527.0","h":"66918.0","l":"65124.0","v":"31474.47357","n":333726},{"t":1784678400000,"T":1784764799999,"s":"BTC","i":"1d","o":"66526.0","c":"66086.0","h":"66714.0","l":"65534.0","v":"29966.73257","n":332005},{"t":1784764800000,"T":1784851199999,"s":"BTC","i":"1d","o":"66086.0","c":"65069.0","h":"66295.0","l":"64637.0","v":"26441.77955","n":306098},{"t":1784851200000,"T":1784937599999,"s":"BTC","i":"1d","o":"65070.0","c":"64123.0","h":"65795.0","l":"63730.0","v":"33123.68641","n":311224},{"t":1784937600000,"T":1785023999999,"s":"BTC","i":"1d","o":"64122.0","c":"64361.0","h":"64434.0","l":"63736.0","v":"10980.43849","n":126469},{"t":1785024000000,"T":1785110399999,"s":"BTC","i":"1d","o":"64361.0","c":"65366.0","h":"65561.0","l":"64262.0","v":"11484.93585","n":148676},{"t":1785110400000,"T":1785196799999,"s":"BTC","i":"1d","o":"65372.0","c":"63736.0","h":"65715.0","l":"63576.0","v":"31030.43653","n":339910}]"""
    }
}
