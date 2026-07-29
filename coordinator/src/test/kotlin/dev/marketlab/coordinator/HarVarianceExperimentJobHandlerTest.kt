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

class HarVarianceExperimentJobHandlerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `production configuration is the frozen four-hour design`() {
        val config = HarVarianceExperimentConfig()

        assertEquals(4_201, config.sourceRows)
        assertEquals(6, config.returnsPerDay)
        assertEquals(7, config.weeklyDays)
        assertEquals(30, config.monthlyDays)
        assertEquals(700, config.dailyRealizedVarianceRows)
        assertEquals(670, config.expectedLabeledRows)
        assertEquals(90, config.sealedHoldoutRows)
        assertEquals(365, config.minimumTrainingRows)
        assertEquals(30, config.testRows)
        assertEquals(30, config.stepRows)
        assertEquals(5, config.foldCount)
        assertEquals(DAY_MILLIS, config.purgeMillis)
        assertEquals(DAY_MILLIS, config.embargoMillis)
        assertEquals(7, config.hacLag)
        assertEquals(1_714_435_200_000L, config.firstSourceAvailableAtEpochMillis)
        assertEquals(1_774_915_200_000L, config.lastSourceAvailableAtEpochMillis)
        assertEquals(1_767_139_200_000L, config.firstHoldoutDecisionEpochMillis)
        assertEquals(1_774_828_800_000L, config.lastHoldoutDecisionEpochMillis)
        assertEquals(1_774_915_200_000L, config.lastHoldoutLabelToEpochMillis)
    }

    @Test
    fun `exact captured four-hour candles produce positive causal forecasts and durable evidence`() =
        runBlocking {
            val fixture = realSnapshotFixture()
            assertEquals(REAL_CAPTURE_SHA256, fixture.snapshot.objects.single().contentHash.hex)
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                HarVarianceExperimentJobHandler(
                    persistence = persistence,
                    snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                    artifacts = ExperimentArtifactStore(temporaryDirectory.resolve("artifacts")),
                    sourceRevision = TEST_SOURCE_REVISION,
                    expectedPlanHash = HAR_PLAN_HASH,
                    config = testConfig(),
                )

            val outcome = handler.execute(leaseFor(run))

            val succeeded = assertIs<JobExecutionResult.Succeeded>(outcome, outcome.toString())
            assertEquals(RunStatus.SUCCEEDED, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertEquals(
                PromotionStatus.BLOCKED.name,
                succeeded.result.getValue("promotionStatus").jsonPrimitive.content,
            )
            assertEquals(2, persistence.artifacts.size)
            assertEquals(1, persistence.trials.size)
            assertEquals(TrialStatus.SUCCEEDED, persistence.trials.single().status)
            assertEquals(buildJsonObject {}, persistence.trials.single().parameters)
            assertEquals(persistence.run.metrics, persistence.trials.single().metrics)

            val predictions =
                readJson(
                    persistence.artifacts.single { it.kind == "PREDICTIONS" }.artifact.uri,
                )
            assertEquals(true, predictions.getValue("realDataOnly").jsonPrimitive.content.toBoolean())
            assertEquals(
                REAL_CAPTURE_SHA256,
                predictions
                    .getValue("rawSourceObjects")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("contentHash")
                    .jsonPrimitive
                    .content,
            )
            val observations = predictions.getValue("observations").jsonArray.map { it.jsonObject }
            assertEquals(2, observations.size)
            assertEquals("DEVELOPMENT_OOS", observations.first()
                .getValue("phase").jsonPrimitive.content)
            assertEquals("SEALED_HOLDOUT", observations.last()
                .getValue("phase").jsonPrimitive.content)
            assertEquals(
                1_784_937_600_000L,
                observations.first()
                    .getValue("decisionTimeEpochMillis")
                    .jsonPrimitive
                    .content
                    .toLong(),
            )
            assertEquals(
                1_785_110_400_000L,
                observations.last()
                    .getValue("decisionTimeEpochMillis")
                    .jsonPrimitive
                    .content
                    .toLong(),
            )
            assertEquals(
                1_785_196_800_000L,
                observations.last()
                    .getValue("labelToEpochMillis")
                    .jsonPrimitive
                    .content
                    .toLong(),
            )

            val daily = capturedDailyVariances()
            assertEquals(
                ln(daily[8]),
                observations.first().getValue("log_rv_daily").jsonPrimitive.double,
                absoluteTolerance = 1e-15,
            )
            assertEquals(
                ln(daily.subList(7, 9).average()),
                observations.first().getValue("log_rv_weekly").jsonPrimitive.double,
                absoluteTolerance = 1e-15,
            )
            assertEquals(
                ln(daily.subList(6, 9).average()),
                observations.first().getValue("log_rv_monthly").jsonPrimitive.double,
                absoluteTolerance = 1e-15,
            )
            assertEquals(
                daily[9],
                observations.first()
                    .getValue("actualNextDayRealizedVariance")
                    .jsonPrimitive
                    .double,
                absoluteTolerance = 1e-18,
            )
            observations.forEach { observation ->
                assertTrue(observation.getValue("candidateForecast").jsonPrimitive.double > 0.0)
                assertTrue(
                    observation
                        .getValue("historicalMeanVarianceForecast")
                        .jsonPrimitive
                        .double > 0.0,
                )
                assertTrue(
                    observation
                        .getValue("persistenceVarianceForecast")
                        .jsonPrimitive
                        .double > 0.0,
                )
                assertEquals(
                    true,
                    observation.getValue("allForecastsPositive").jsonPrimitive.content.toBoolean(),
                )
            }

            val report =
                readJson(
                    persistence.artifacts.single { it.kind == "REPORT" }.artifact.uri,
                )
            assertEquals(73, report.getValue("sourceCandleCount").jsonPrimitive.content.toInt())
            assertEquals(12, report
                .getValue("dailyRealizedVarianceCount").jsonPrimitive.content.toInt())
            assertEquals(9, report.getValue("totalLabeledRowCount").jsonPrimitive.content.toInt())
            assertEquals(1, report.getValue("holdoutBoundaryPurgedRows").jsonPrimitive.content.toInt())
            assertEquals(1, report.getValue("sealedHoldoutRows").jsonPrimitive.content.toInt())
            assertEquals(
                true,
                report.getValue("singleUnconditionalHoldoutDecision")
                    .jsonPrimitive.content.toBoolean(),
            )
            assertEquals(
                false,
                report.getValue("modelSelectionUsedHoldout").jsonPrimitive.content.toBoolean(),
            )
            assertEquals(false, report.getValue("researcherBlind").jsonPrimitive.content.toBoolean())
            assertEquals(
                false,
                report.getValue("crossRunHoldoutReusePrevented")
                    .jsonPrimitive.content.toBoolean(),
            )
            assertEquals(6, report.getValue("limitations").jsonArray.size)
            assertEquals(
                false,
                report
                    .getValue("aggregationSemantics")
                    .jsonObject
                    .getValue("logTargetRetransformationSmearingCorrection")
                    .jsonPrimitive
                    .content
                    .toBoolean(),
            )
            val metrics = report.getValue("metrics").jsonObject
            val holdout = metrics.getValue("sealedHoldout").jsonObject
            val candidate = holdout.getValue("candidate").jsonObject
            assertTrue(candidate.getValue("meanQlikeLoss").jsonPrimitive.double.isFinite())
            assertTrue(candidate.getValue("meanAbsoluteError").jsonPrimitive.double >= 0.0)
            assertTrue(candidate.getValue("rootMeanSquaredError").jsonPrimitive.double >= 0.0)
            val controls = holdout.getValue("controls").jsonArray
            assertEquals(2, controls.size)
            controls.forEach { element ->
                val control = element.jsonObject
                assertTrue(
                    control.getValue("benjaminiHochbergPValue")
                        .jsonPrimitive.double in 0.0..1.0,
                )
                assertEquals(0, control.getValue("hacLag").jsonPrimitive.content.toInt())
            }
            persistence.artifacts.forEach { write ->
                val path = Path.of(URI(write.artifact.uri))
                assertTrue(Files.isRegularFile(path))
                assertEquals(write.artifact.byteCount, Files.size(path))
            }

            val recovered = handler.execute(leaseFor(persistence.run))
            assertEquals(
                true,
                assertIs<JobExecutionResult.Succeeded>(recovered)
                    .result
                    .getValue("recovered")
                    .jsonPrimitive
                    .content
                    .toBoolean(),
            )
            assertEquals(2, persistence.artifacts.size)
            assertEquals(1, persistence.trials.size)
        }

    @Test
    fun `different exact availability interval is rejected before fitting`() =
        runBlocking {
            val fixture = realSnapshotFixture()
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                HarVarianceExperimentJobHandler(
                    persistence = persistence,
                    snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                    artifacts = ExperimentArtifactStore(temporaryDirectory.resolve("wrong-interval-artifacts")),
                    sourceRevision = TEST_SOURCE_REVISION,
                    expectedPlanHash = HAR_PLAN_HASH,
                    config = testConfig(clockShiftMillis = DAY_MILLIS),
                )

            val outcome = handler.execute(leaseFor(run))

            val failed = assertIs<JobExecutionResult.Failed>(outcome)
            assertEquals("INVALID_REAL_DATA_SNAPSHOT", failed.code)
            assertEquals(RunStatus.REJECTED, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertTrue(persistence.artifacts.isEmpty())
            assertTrue(persistence.trials.isEmpty())
        }

    @Test
    fun `different raw request provenance is rejected before fitting`() =
        runBlocking {
            val fixture =
                realSnapshotFixture(
                    requestBody =
                        """{"type":"candleSnapshot","req":{"coin":"BTC","interval":"4h","startTime":0,"endTime":1}}""",
                )
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                HarVarianceExperimentJobHandler(
                    persistence = persistence,
                    snapshotReader = StoredHyperliquidSnapshotReader(fixture.store),
                    artifacts =
                        ExperimentArtifactStore(
                            temporaryDirectory.resolve("wrong-provenance-artifacts"),
                        ),
                    sourceRevision = TEST_SOURCE_REVISION,
                    expectedPlanHash = HAR_PLAN_HASH,
                    config = testConfig(),
                )

            val outcome = handler.execute(leaseFor(run))

            val failed = assertIs<JobExecutionResult.Failed>(outcome)
            assertEquals("INVALID_REAL_DATA_SNAPSHOT", failed.code)
            assertEquals(RunStatus.REJECTED, persistence.run.status)
            assertTrue(persistence.artifacts.isEmpty())
            assertTrue(persistence.trials.isEmpty())
        }

    private fun testConfig(clockShiftMillis: Long = 0L) =
        HarVarianceExperimentConfig(
            sourceRows = 73,
            returnsPerDay = 6,
            weeklyDays = 2,
            monthlyDays = 3,
            sealedHoldoutRows = 1,
            minimumTrainingRows = 5,
            testRows = 1,
            stepRows = 1,
            foldCount = 1,
            purgeMillis = DAY_MILLIS,
            embargoMillis = DAY_MILLIS,
            hacLag = 1,
            firstSourceAvailableAtEpochMillis = 1_784_160_000_000L + clockShiftMillis,
            lastSourceAvailableAtEpochMillis = 1_785_196_800_000L + clockShiftMillis,
            firstHoldoutDecisionEpochMillis = 1_785_110_400_000L + clockShiftMillis,
            lastHoldoutDecisionEpochMillis = 1_785_110_400_000L + clockShiftMillis,
            lastHoldoutLabelToEpochMillis = 1_785_196_800_000L + clockShiftMillis,
            expectedRequestBody = REAL_REQUEST,
        )

    private fun realSnapshotFixture(
        requestBody: String = REAL_REQUEST,
    ): RealSnapshotFixture {
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
                            parameters = mapOf("body" to requestBody),
                        ),
                    retrievedAt = RETRIEVED_AT,
                    schemaVersion = "hyperliquid-info-v1",
                    adapterVersion = "hyperliquid-rest-v1",
                    rowCount = 73,
                    eventTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_159_999_999L),
                            MarketTimestamp(1_785_196_800_000L),
                        ),
                    availabilityTimeRange =
                        TimeRange(
                            MarketTimestamp(1_784_160_000_000L),
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
                            key = "hyperliquid_btc_four_hour_candles",
                            observation = ObservationKind.CANDLE,
                            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                            sampling = Sampling.FixedDuration(FOUR_HOUR_MILLIS),
                            requiredFields = listOf("close", "tradeCount"),
                            minimumHistoryMillis = 72L * FOUR_HOUR_MILLIS,
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
                id = UUID.fromString("00000000-0000-0000-0000-000000000421"),
                manifestHash = snapshot.manifestHash.hex,
                status = SnapshotStatus.READY,
                createdAt = RETRIEVED_AT,
                metadata = snapshotMetadata(snapshot),
                objectCount = snapshot.objects.size,
                fatalFindingCount = 0,
            )
        return RealSnapshotFixture(store, snapshot, row)
    }

    private fun capturedDailyVariances(): List<Double> {
        val closes =
            JSON
                .parseToJsonElement(REAL_CANDLES_JSON)
                .jsonArray
                .map {
                    it.jsonObject.getValue("c").jsonPrimitive.content.toDouble()
                }
        return (0 until 12).map { day ->
            val first = day * 6
            ((first + 1)..(first + 6)).sumOf { index ->
                val value = ln(closes[index] / closes[index - 1])
                value * value
            }
        }
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
            id = UUID.fromString("00000000-0000-0000-0000-000000000422"),
            theoryId = HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID,
            theoryVersion = "1.0.0",
            theoryPlanHash = HAR_PLAN_HASH,
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

    private fun leaseFor(run: ExperimentRunRow): JobLease =
        JobLease(
            job =
                JobRow(
                    id = UUID.fromString("00000000-0000-0000-0000-000000000423"),
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
                    leaseOwner = "har-variance-test-worker",
                    leaseToken = UUID.fromString("00000000-0000-0000-0000-000000000424"),
                    leaseUntil = RETRIEVED_AT.plusSeconds(120),
                    cancellationRequested = false,
                    result = null,
                    lastError = null,
                    createdAt = RETRIEVED_AT,
                    updatedAt = RETRIEVED_AT,
                    completedAt = null,
                ),
            attemptId = UUID.fromString("00000000-0000-0000-0000-000000000425"),
        )

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
        val RETRIEVED_AT: Instant = Instant.parse("2026-07-28T06:45:00Z")
        val HAR_PLAN_HASH: String =
            TheoryPlanHasher.hash(
                requireNotNull(
                    AcademicTheoryRegistry.find(
                        TheoryId(HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID),
                    ),
                ).compile(),
            ).hex
        const val FOUR_HOUR_MILLIS = 14_400_000L
        const val DAY_MILLIS = 86_400_000L
        const val REAL_CAPTURE_SHA256 =
            "c07e799b5a710829e89f48126bedc05d4b77008ec5368fe70160982854ffa92b"
        const val TEST_SOURCE_REVISION =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val REAL_REQUEST =
            """{"type":"candleSnapshot","req":{"coin":"BTC","interval":"4h","startTime":1784145600000,"endTime":1785196799999}}"""

        /*
         * Exact response bytes captured from Hyperliquid mainnet /info
         * candleSnapshot for BTC 4h on 2026-07-28. This is an observed sequence,
         * not a generated price path and not production experiment evidence.
         */
        const val REAL_CANDLES_JSON =
            """[{"t":1784145600000,"T":1784159999999,"s":"BTC","i":"4h","o":"64948.0","c":"64738.0","h":"65036.0","l":"64675.0","v":"2820.99158","n":25490},{"t":1784160000000,"T":1784174399999,"s":"BTC","i":"4h","o":"64738.0","c":"64602.0","h":"64836.0","l":"64357.0","v":"2617.23496","n":30763},{"t":1784174400000,"T":1784188799999,"s":"BTC","i":"4h","o":"64602.0","c":"64222.0","h":"64999.0","l":"64052.0","v":"4296.77069","n":40998},{"t":1784188800000,"T":1784203199999,"s":"BTC","i":"4h","o":"64222.0","c":"64235.0","h":"64349.0","l":"63865.0","v":"3896.29495","n":44031},{"t":1784203200000,"T":1784217599999,"s":"BTC","i":"4h","o":"64235.0","c":"64689.0","h":"64885.0","l":"63845.0","v":"8284.81384","n":74524},{"t":1784217600000,"T":1784231999999,"s":"BTC","i":"4h","o":"64688.0","c":"64263.0","h":"64700.0","l":"63977.0","v":"3872.19917","n":43548},{"t":1784232000000,"T":1784246399999,"s":"BTC","i":"4h","o":"64262.0","c":"63815.0","h":"64268.0","l":"63723.0","v":"3030.37335","n":35277},{"t":1784246400000,"T":1784260799999,"s":"BTC","i":"4h","o":"63815.0","c":"63572.0","h":"64048.0","l":"63377.0","v":"5302.53694","n":47282},{"t":1784260800000,"T":1784275199999,"s":"BTC","i":"4h","o":"63572.0","c":"62839.0","h":"63581.0","l":"62725.0","v":"5250.7047","n":49750},{"t":1784275200000,"T":1784289599999,"s":"BTC","i":"4h","o":"62839.0","c":"63301.0","h":"63379.0","l":"62684.0","v":"3431.64075","n":41793},{"t":1784289600000,"T":1784303999999,"s":"BTC","i":"4h","o":"63300.0","c":"63478.0","h":"63547.0","l":"62550.0","v":"6538.23304","n":75647},{"t":1784304000000,"T":1784318399999,"s":"BTC","i":"4h","o":"63478.0","c":"64156.0","h":"64398.0","l":"63329.0","v":"7129.00698","n":76523},{"t":1784318400000,"T":1784332799999,"s":"BTC","i":"4h","o":"64153.0","c":"63928.0","h":"64231.0","l":"63876.0","v":"3073.06613","n":28514},{"t":1784332800000,"T":1784347199999,"s":"BTC","i":"4h","o":"63927.0","c":"64009.0","h":"64029.0","l":"63873.0","v":"945.55492","n":17034},{"t":1784347200000,"T":1784361599999,"s":"BTC","i":"4h","o":"64009.0","c":"63995.0","h":"64020.0","l":"63914.0","v":"1018.77593","n":13814},{"t":1784361600000,"T":1784375999999,"s":"BTC","i":"4h","o":"63993.0","c":"64049.0","h":"64088.0","l":"63924.0","v":"993.34642","n":14373},{"t":1784376000000,"T":1784390399999,"s":"BTC","i":"4h","o":"64048.0","c":"64104.0","h":"64250.0","l":"63943.0","v":"1854.78024","n":27021},{"t":1784390400000,"T":1784404799999,"s":"BTC","i":"4h","o":"64105.0","c":"64546.0","h":"64619.0","l":"64070.0","v":"3958.58071","n":41316},{"t":1784404800000,"T":1784419199999,"s":"BTC","i":"4h","o":"64545.0","c":"64827.0","h":"64873.0","l":"64500.0","v":"3314.89156","n":33451},{"t":1784419200000,"T":1784433599999,"s":"BTC","i":"4h","o":"64828.0","c":"64694.0","h":"64957.0","l":"64616.0","v":"2239.73169","n":25047},{"t":1784433600000,"T":1784447999999,"s":"BTC","i":"4h","o":"64695.0","c":"64713.0","h":"64810.0","l":"64601.0","v":"1138.19833","n":17402},{"t":1784448000000,"T":1784462399999,"s":"BTC","i":"4h","o":"64714.0","c":"64452.0","h":"64740.0","l":"64424.0","v":"2307.12986","n":21557},{"t":1784462400000,"T":1784476799999,"s":"BTC","i":"4h","o":"64453.0","c":"64574.0","h":"64641.0","l":"64275.0","v":"2615.98252","n":30337},{"t":1784476800000,"T":1784491199999,"s":"BTC","i":"4h","o":"64575.0","c":"64456.0","h":"64762.0","l":"64285.0","v":"2181.20718","n":30258},{"t":1784491200000,"T":1784505599999,"s":"BTC","i":"4h","o":"64456.0","c":"64718.0","h":"64890.0","l":"64339.0","v":"3587.78218","n":36178},{"t":1784505600000,"T":1784519999999,"s":"BTC","i":"4h","o":"64719.0","c":"64841.0","h":"65082.0","l":"64400.0","v":"6892.7276","n":52928},{"t":1784520000000,"T":1784534399999,"s":"BTC","i":"4h","o":"64840.0","c":"64241.0","h":"64841.0","l":"63730.0","v":"5942.95775","n":48320},{"t":1784534400000,"T":1784548799999,"s":"BTC","i":"4h","o":"64241.0","c":"64989.0","h":"65057.0","l":"63808.0","v":"5349.47787","n":51914},{"t":1784548800000,"T":1784563199999,"s":"BTC","i":"4h","o":"64989.0","c":"65565.0","h":"65600.0","l":"64031.0","v":"9730.61516","n":89048},{"t":1784563200000,"T":1784577599999,"s":"BTC","i":"4h","o":"65564.0","c":"65100.0","h":"65778.0","l":"65010.0","v":"10087.92622","n":80505},{"t":1784577600000,"T":1784591999999,"s":"BTC","i":"4h","o":"65100.0","c":"65226.0","h":"65427.0","l":"65020.0","v":"4732.248","n":38176},{"t":1784592000000,"T":1784606399999,"s":"BTC","i":"4h","o":"65227.0","c":"65539.0","h":"65635.0","l":"65124.0","v":"3794.71033","n":40754},{"t":1784606400000,"T":1784620799999,"s":"BTC","i":"4h","o":"65538.0","c":"66170.0","h":"66215.0","l":"65451.0","v":"6075.09529","n":55647},{"t":1784620800000,"T":1784635199999,"s":"BTC","i":"4h","o":"66168.0","c":"66320.0","h":"66382.0","l":"66102.0","v":"4101.1873","n":51938},{"t":1784635200000,"T":1784649599999,"s":"BTC","i":"4h","o":"66321.0","c":"66644.0","h":"66918.0","l":"66238.0","v":"8847.17956","n":87304},{"t":1784649600000,"T":1784663999999,"s":"BTC","i":"4h","o":"66645.0","c":"66417.0","h":"66729.0","l":"66065.0","v":"5815.17432","n":59844},{"t":1784664000000,"T":1784678399999,"s":"BTC","i":"4h","o":"66417.0","c":"66527.0","h":"66553.0","l":"66185.0","v":"2841.12677","n":38239},{"t":1784678400000,"T":1784692799999,"s":"BTC","i":"4h","o":"66526.0","c":"66180.0","h":"66714.0","l":"66152.0","v":"3614.53711","n":45081},{"t":1784692800000,"T":1784707199999,"s":"BTC","i":"4h","o":"66179.0","c":"65808.0","h":"66400.0","l":"65663.0","v":"5075.69812","n":51673},{"t":1784707200000,"T":1784721599999,"s":"BTC","i":"4h","o":"65808.0","c":"65990.0","h":"66142.0","l":"65808.0","v":"2409.60463","n":47453},{"t":1784721600000,"T":1784735999999,"s":"BTC","i":"4h","o":"65989.0","c":"66022.0","h":"66190.0","l":"65534.0","v":"8664.86807","n":85407},{"t":1784736000000,"T":1784750399999,"s":"BTC","i":"4h","o":"66022.0","c":"65906.0","h":"66358.0","l":"65666.0","v":"6681.24928","n":62252},{"t":1784750400000,"T":1784764799999,"s":"BTC","i":"4h","o":"65902.0","c":"66086.0","h":"66125.0","l":"65770.0","v":"3520.77536","n":40139},{"t":1784764800000,"T":1784779199999,"s":"BTC","i":"4h","o":"66086.0","c":"65626.0","h":"66295.0","l":"65540.0","v":"5378.39736","n":52004},{"t":1784779200000,"T":1784793599999,"s":"BTC","i":"4h","o":"65625.0","c":"65416.0","h":"65794.0","l":"65323.0","v":"2430.50349","n":37387},{"t":1784793600000,"T":1784807999999,"s":"BTC","i":"4h","o":"65417.0","c":"65523.0","h":"65774.0","l":"65390.0","v":"3391.29484","n":42522},{"t":1784808000000,"T":1784822399999,"s":"BTC","i":"4h","o":"65523.0","c":"64942.0","h":"65566.0","l":"64700.0","v":"8645.34309","n":90792},{"t":1784822400000,"T":1784836799999,"s":"BTC","i":"4h","o":"64941.0","c":"64834.0","h":"64942.0","l":"64637.0","v":"3349.72532","n":46217},{"t":1784836800000,"T":1784851199999,"s":"BTC","i":"4h","o":"64837.0","c":"65069.0","h":"65187.0","l":"64810.0","v":"3246.51545","n":37176},{"t":1784851200000,"T":1784865599999,"s":"BTC","i":"4h","o":"65070.0","c":"65426.0","h":"65440.0","l":"64721.0","v":"5247.68987","n":47583},{"t":1784865600000,"T":1784879999999,"s":"BTC","i":"4h","o":"65426.0","c":"65483.0","h":"65795.0","l":"65216.0","v":"4435.44673","n":42618},{"t":1784880000000,"T":1784894399999,"s":"BTC","i":"4h","o":"65488.0","c":"65065.0","h":"65496.0","l":"64828.0","v":"5720.76773","n":53300},{"t":1784894400000,"T":1784908799999,"s":"BTC","i":"4h","o":"65065.0","c":"64085.0","h":"65125.0","l":"63730.0","v":"11816.67006","n":101949},{"t":1784908800000,"T":1784923199999,"s":"BTC","i":"4h","o":"64087.0","c":"64199.0","h":"64280.0","l":"63862.0","v":"4255.72484","n":43264},{"t":1784923200000,"T":1784937599999,"s":"BTC","i":"4h","o":"64199.0","c":"64123.0","h":"64264.0","l":"64099.0","v":"1647.38718","n":22510},{"t":1784937600000,"T":1784951999999,"s":"BTC","i":"4h","o":"64122.0","c":"64062.0","h":"64155.0","l":"63983.0","v":"1507.07101","n":20589},{"t":1784952000000,"T":1784966399999,"s":"BTC","i":"4h","o":"64060.0","c":"63984.0","h":"64173.0","l":"63926.0","v":"2113.57536","n":20934},{"t":1784966400000,"T":1784980799999,"s":"BTC","i":"4h","o":"63985.0","c":"64032.0","h":"64087.0","l":"63736.0","v":"3352.49609","n":29587},{"t":1784980800000,"T":1784995199999,"s":"BTC","i":"4h","o":"64032.0","c":"64157.0","h":"64250.0","l":"64010.0","v":"1276.01794","n":18564},{"t":1784995200000,"T":1785009599999,"s":"BTC","i":"4h","o":"64157.0","c":"64368.0","h":"64434.0","l":"64094.0","v":"1831.63157","n":22794},{"t":1785009600000,"T":1785023999999,"s":"BTC","i":"4h","o":"64369.0","c":"64361.0","h":"64410.0","l":"64234.0","v":"899.64652","n":14001},{"t":1785024000000,"T":1785038399999,"s":"BTC","i":"4h","o":"64361.0","c":"64520.0","h":"64558.0","l":"64334.0","v":"994.01834","n":15138},{"t":1785038400000,"T":1785052799999,"s":"BTC","i":"4h","o":"64520.0","c":"64346.0","h":"64571.0","l":"64262.0","v":"1177.22303","n":14848},{"t":1785052800000,"T":1785067199999,"s":"BTC","i":"4h","o":"64347.0","c":"64476.0","h":"64543.0","l":"64333.0","v":"629.6129","n":14162},{"t":1785067200000,"T":1785081599999,"s":"BTC","i":"4h","o":"64477.0","c":"64737.0","h":"64780.0","l":"64377.0","v":"2248.88752","n":28904},{"t":1785081600000,"T":1785095999999,"s":"BTC","i":"4h","o":"64737.0","c":"64669.0","h":"64907.0","l":"64637.0","v":"1872.34752","n":25632},{"t":1785096000000,"T":1785110399999,"s":"BTC","i":"4h","o":"64670.0","c":"65366.0","h":"65561.0","l":"64599.0","v":"4562.84654","n":49992},{"t":1785110400000,"T":1785124799999,"s":"BTC","i":"4h","o":"65372.0","c":"65261.0","h":"65395.0","l":"64866.0","v":"2381.95709","n":39494},{"t":1785124800000,"T":1785139199999,"s":"BTC","i":"4h","o":"65261.0","c":"65191.0","h":"65715.0","l":"65180.0","v":"2512.14238","n":34798},{"t":1785139200000,"T":1785153599999,"s":"BTC","i":"4h","o":"65190.0","c":"65074.0","h":"65411.0","l":"65058.0","v":"1860.95252","n":36262},{"t":1785153600000,"T":1785167999999,"s":"BTC","i":"4h","o":"65073.0","c":"64514.0","h":"65680.0","l":"64394.0","v":"11482.47402","n":103331},{"t":1785168000000,"T":1785182399999,"s":"BTC","i":"4h","o":"64515.0","c":"64949.0","h":"65059.0","l":"64500.0","v":"4686.11829","n":57075},{"t":1785182400000,"T":1785196799999,"s":"BTC","i":"4h","o":"64948.0","c":"63736.0","h":"65027.0","l":"63576.0","v":"8106.79223","n":68950}]"""
    }
}
