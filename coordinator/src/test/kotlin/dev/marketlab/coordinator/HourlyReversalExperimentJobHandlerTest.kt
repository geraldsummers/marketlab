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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class HourlyReversalExperimentJobHandlerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `production configuration freezes the exact hourly holdout`() {
        val config = HourlyReversalExperimentConfig()

        assertEquals(4_800, config.sourceRows)
        assertEquals(4_798, config.expectedLabeledRows)
        assertEquals(720, config.sealedHoldoutRows)
        assertEquals(4_077, config.expectedValidationRows)
        assertEquals(1, config.expectedBoundaryPurgedRows)
        assertEquals(2_160, config.minimumTrainingRows)
        assertEquals(168, config.testRows)
        assertEquals(11, config.foldCount)
        assertEquals(HOUR, config.purgeMillis)
        assertEquals(HOUR, config.embargoMillis)
        assertEquals(24, config.hacLag)
        assertEquals(1_782_615_600_000L, config.firstHoldoutDecisionEpochMillis)
        assertEquals(1_785_204_000_000L, config.lastHoldoutDecisionEpochMillis)
        assertEquals(1_785_207_600_000L, config.holdoutLabelToEpochMillis)
    }

    @Test
    fun `observed hourly candles produce durable sealed evidence`() =
        runBlocking {
            val fixture = realFixture()
            assertEquals(REAL_CAPTURE_SHA256, fixture.snapshot.objects.single().contentHash.hex)
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                HourlyReversalExperimentJobHandler(
                    persistence,
                    StoredHyperliquidSnapshotReader(fixture.store),
                    ExperimentArtifactStore(temporaryDirectory.resolve("artifacts")),
                    TEST_SOURCE_REVISION,
                    PLAN_HASH,
                    testConfig(fixture),
                )

            val outcome = handler.execute(leaseFor(run))

            val succeeded = assertIs<JobExecutionResult.Succeeded>(outcome, outcome.toString())
            assertEquals(RunStatus.SUCCEEDED, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertEquals(2, persistence.artifacts.size)
            assertEquals(1, persistence.trials.size)
            assertEquals(TrialStatus.SUCCEEDED, persistence.trials.single().status)
            assertEquals(
                "true",
                succeeded.result.getValue("realDataOnly").jsonPrimitive.content,
            )

            val predictions =
                readJson(
                    persistence.artifacts.single { it.kind == "PREDICTIONS" }.artifact.uri,
                )
            val observations = predictions.getValue("observations").jsonArray.map { it.jsonObject }
            assertEquals(14, observations.size)
            assertEquals(8, observations.count {
                it.getValue("phase").jsonPrimitive.content == "DEVELOPMENT_OOS"
            })
            assertEquals(6, observations.count {
                it.getValue("phase").jsonPrimitive.content == "SEALED_HOLDOUT"
            })
            assertEquals(
                1_767_992_400_000L,
                observations.first {
                    it.getValue("phase").jsonPrimitive.content == "SEALED_HOLDOUT"
                }.getValue("decisionTimeEpochMillis").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                1_768_014_000_000L,
                observations.last().getValue("labelToEpochMillis").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                REAL_CAPTURE_SHA256,
                predictions.getValue("rawSourceObjects").jsonArray.single().jsonObject
                    .getValue("contentHash").jsonPrimitive.content,
            )

            val report =
                readJson(
                    persistence.artifacts.single { it.kind == "REPORT" }.artifact.uri,
                )
            assertEquals(24, report.getValue("sourceCandleCount").jsonPrimitive.content.toInt())
            assertEquals(22, report.getValue("totalLabeledRowCount").jsonPrimitive.content.toInt())
            assertEquals(15, report.getValue("validationRowCount").jsonPrimitive.content.toInt())
            assertEquals(1, report.getValue("holdoutBoundaryPurgedRows").jsonPrimitive.content.toInt())
            assertEquals(6, report.getValue("sealedHoldoutRows").jsonPrimitive.content.toInt())
            assertEquals(
                4,
                report.getValue("fittedCoefficients").jsonObject
                    .getValue("development").jsonArray.size,
            )
            val holdoutMetrics =
                report.getValue("metrics").jsonObject
                    .getValue("sealedHoldout").jsonObject
            assertEquals(3, holdoutMetrics.getValue("controls").jsonArray.size)
            persistence.artifacts.forEach { write ->
                val path = Path.of(URI(write.artifact.uri))
                assertTrue(Files.isRegularFile(path))
                assertEquals(write.artifact.byteCount, Files.size(path))
            }

            val recovered = handler.execute(leaseFor(persistence.run))
            assertEquals(
                "true",
                assertIs<JobExecutionResult.Succeeded>(recovered)
                    .result.getValue("recovered").jsonPrimitive.content,
            )
            assertEquals(2, persistence.artifacts.size)
        }

    @Test
    fun `different raw request provenance is rejected before fitting`() =
        runBlocking {
            val fixture = realFixture("""{"coin":"ETH","type":"candleSnapshot"}""")
            val run = runRow(fixture.snapshotRow.id)
            val persistence = FakeExperimentPersistence(run, fixture.snapshotRow)
            val handler =
                HourlyReversalExperimentJobHandler(
                    persistence,
                    StoredHyperliquidSnapshotReader(fixture.store),
                    ExperimentArtifactStore(temporaryDirectory.resolve("wrong-provenance")),
                    TEST_SOURCE_REVISION,
                    PLAN_HASH,
                    testConfig(fixture, expectedRequestBody = REAL_REQUEST),
                )

            val outcome = handler.execute(leaseFor(run))

            val failed = assertIs<JobExecutionResult.Failed>(outcome)
            assertEquals("INVALID_REAL_DATA_SNAPSHOT", failed.code)
            assertEquals(RunStatus.REJECTED, persistence.run.status)
            assertTrue(persistence.artifacts.isEmpty())
            assertTrue(persistence.trials.isEmpty())
        }

    private fun testConfig(
        fixture: RealFixture,
        expectedRequestBody: String = REAL_REQUEST,
    ) = HourlyReversalExperimentConfig(
        sourceRows = 24,
        expectedLabeledRows = 22,
        sealedHoldoutRows = 6,
        expectedValidationRows = 15,
        expectedBoundaryPurgedRows = 1,
        minimumTrainingRows = 5,
        testRows = 2,
        stepRows = 2,
        foldCount = 4,
        purgeMillis = HOUR,
        embargoMillis = HOUR,
        hacLag = 2,
        expectedSnapshotManifestHash = fixture.snapshot.manifestHash.hex,
        expectedRawContentHash = REAL_CAPTURE_SHA256,
        expectedRequestBody = expectedRequestBody,
        expectedFirstAvailableAtEpochMillis = 1_767_931_200_000L,
        expectedLastAvailableAtEpochMillis = 1_768_014_000_000L,
        firstHoldoutDecisionEpochMillis = 1_767_992_400_000L,
        lastHoldoutDecisionEpochMillis = 1_768_010_400_000L,
        holdoutLabelToEpochMillis = 1_768_014_000_000L,
    )

    private fun realFixture(
        requestBody: String = REAL_REQUEST,
    ): RealFixture {
        val store = ContentAddressedDataStore(temporaryDirectory.resolve("raw-${UUID.randomUUID()}"))
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
                    schemaVersion = "hyperliquid-info-json-v1",
                    adapterVersion = "hyperliquid-v1",
                    rowCount = 24,
                    eventTimeRange =
                        TimeRange(
                            MarketTimestamp(1_767_927_600_000L),
                            MarketTimestamp(1_768_014_000_000L),
                        ),
                    availabilityTimeRange =
                        TimeRange(
                            MarketTimestamp(1_767_931_200_000L),
                            MarketTimestamp(1_768_014_000_001L),
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
                            key = "hyperliquid_btc_hourly_candles",
                            observation = ObservationKind.CANDLE,
                            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                            sampling = Sampling.FixedDuration(HOUR),
                            requiredFields = listOf("close"),
                            minimumHistoryMillis = 24L * HOUR,
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
                id = UUID.randomUUID(),
                manifestHash = snapshot.manifestHash.hex,
                status = SnapshotStatus.READY,
                createdAt = RETRIEVED_AT,
                metadata =
                    buildJsonObject {
                        put("contractSnapshotId", snapshot.id.value)
                        put(
                            "contractSnapshot",
                            JSON.encodeToJsonElement(DataSnapshot.serializer(), snapshot),
                        )
                    },
                objectCount = 1,
                fatalFindingCount = 0,
            )
        return RealFixture(store, snapshot, row)
    }

    private fun runRow(snapshotId: UUID) =
        ExperimentRunRow(
            id = UUID.randomUUID(),
            theoryId = HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID,
            theoryVersion = "1.0.0",
            theoryPlanHash = PLAN_HASH,
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

    private fun leaseFor(run: ExperimentRunRow) =
        JobLease(
            job =
                JobRow(
                    id = UUID.randomUUID(),
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
                    leaseOwner = "hourly-reversal-test",
                    leaseToken = UUID.randomUUID(),
                    leaseUntil = RETRIEVED_AT.plusSeconds(120),
                    cancellationRequested = false,
                    result = null,
                    lastError = null,
                    createdAt = RETRIEVED_AT,
                    updatedAt = RETRIEVED_AT,
                    completedAt = null,
                ),
            attemptId = UUID.randomUUID(),
        )

    private fun readJson(uri: String): JsonObject =
        JSON.parseToJsonElement(Files.readString(Path.of(URI(uri)))).jsonObject

    private data class RealFixture(
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

        override fun getRun(id: UUID) = run.takeIf { it.id == id }

        override fun markRunning(fence: ExperimentLeaseFence): ExperimentRunRow {
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

        override fun getSnapshot(id: UUID) = snapshot.takeIf { it.id == id }
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val TEST_SOURCE_REVISION =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val REAL_CAPTURE_SHA256 =
            "f225d8f890f3b3db6209ee65e7c05399ee4762cda8bcd2edcd4453170bac685a"
        const val REAL_REQUEST =
            """{"req":{"coin":"BTC","endTime":1768013999999,"interval":"1h","startTime":1767927600000},"type":"candleSnapshot"}"""
        val RETRIEVED_AT: Instant = Instant.parse("2026-07-28T06:00:00Z")
        val JSON = Json { encodeDefaults = true }
        val PLAN_HASH =
            TheoryPlanHasher.hash(
                requireNotNull(
                    AcademicTheoryRegistry.find(
                        TheoryId(HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID),
                    ),
                ).compile(),
            ).hex
        const val REAL_CANDLES_JSON =
            """[{"t":1767927600000,"T":1767931199999,"s":"BTC","i":"1h","o":"91023.0","c":"91207.0","h":"91263.0","l":"90934.0","v":"468.13816","n":5703},{"t":1767931200000,"T":1767934799999,"s":"BTC","i":"1h","o":"91207.0","c":"91010.0","h":"91220.0","l":"90910.0","v":"407.55639","n":4370},{"t":1767934800000,"T":1767938399999,"s":"BTC","i":"1h","o":"91010.0","c":"91085.0","h":"91118.0","l":"90902.0","v":"573.41205","n":5387},{"t":1767938400000,"T":1767941999999,"s":"BTC","i":"1h","o":"91084.0","c":"91137.0","h":"91148.0","l":"90980.0","v":"743.9888","n":6575},{"t":1767942000000,"T":1767945599999,"s":"BTC","i":"1h","o":"91137.0","c":"90733.0","h":"91138.0","l":"90710.0","v":"1850.90985","n":10363},{"t":1767945600000,"T":1767949199999,"s":"BTC","i":"1h","o":"90734.0","c":"90012.0","h":"90849.0","l":"89645.0","v":"5408.01873","n":33458},{"t":1767949200000,"T":1767952799999,"s":"BTC","i":"1h","o":"90009.0","c":"90326.0","h":"90491.0","l":"89995.0","v":"2390.57878","n":16367},{"t":1767952800000,"T":1767956399999,"s":"BTC","i":"1h","o":"90327.0","c":"90478.0","h":"90545.0","l":"90267.0","v":"550.85487","n":7125},{"t":1767956400000,"T":1767959999999,"s":"BTC","i":"1h","o":"90478.0","c":"90434.0","h":"90683.0","l":"90390.0","v":"278.90987","n":6302},{"t":1767960000000,"T":1767963599999,"s":"BTC","i":"1h","o":"90434.0","c":"90438.0","h":"90583.0","l":"90204.0","v":"415.05787","n":7365},{"t":1767963600000,"T":1767967199999,"s":"BTC","i":"1h","o":"90438.0","c":"90585.0","h":"90915.0","l":"90369.0","v":"969.1335","n":15623},{"t":1767967200000,"T":1767970799999,"s":"BTC","i":"1h","o":"90585.0","c":"90137.0","h":"90652.0","l":"89929.0","v":"2137.09429","n":23119},{"t":1767970800000,"T":1767974399999,"s":"BTC","i":"1h","o":"90137.0","c":"91185.0","h":"92061.0","l":"89834.0","v":"6094.2877","n":52820},{"t":1767974400000,"T":1767977999999,"s":"BTC","i":"1h","o":"91185.0","c":"91621.0","h":"91784.0","l":"91156.0","v":"2864.56715","n":29573},{"t":1767978000000,"T":1767981599999,"s":"BTC","i":"1h","o":"91622.0","c":"91428.0","h":"91676.0","l":"90999.0","v":"1633.55362","n":17093},{"t":1767981600000,"T":1767985199999,"s":"BTC","i":"1h","o":"91427.0","c":"90821.0","h":"91501.0","l":"90275.0","v":"3273.80343","n":23412},{"t":1767985200000,"T":1767988799999,"s":"BTC","i":"1h","o":"90821.0","c":"90336.0","h":"90821.0","l":"90143.0","v":"3035.76774","n":19333},{"t":1767988800000,"T":1767992399999,"s":"BTC","i":"1h","o":"90334.0","c":"90307.0","h":"90565.0","l":"90221.0","v":"1859.90476","n":13499},{"t":1767992400000,"T":1767995999999,"s":"BTC","i":"1h","o":"90308.0","c":"90540.0","h":"90634.0","l":"90131.0","v":"634.91111","n":7251},{"t":1767996000000,"T":1767999599999,"s":"BTC","i":"1h","o":"90541.0","c":"90629.0","h":"90700.0","l":"90350.0","v":"778.98908","n":7084},{"t":1767999600000,"T":1768003199999,"s":"BTC","i":"1h","o":"90630.0","c":"90650.0","h":"90732.0","l":"90602.0","v":"261.62531","n":3427},{"t":1768003200000,"T":1768006799999,"s":"BTC","i":"1h","o":"90651.0","c":"90585.0","h":"90693.0","l":"90520.0","v":"254.77407","n":3534},{"t":1768006800000,"T":1768010399999,"s":"BTC","i":"1h","o":"90586.0","c":"90682.0","h":"90733.0","l":"90581.0","v":"357.22549","n":3939},{"t":1768010400000,"T":1768013999999,"s":"BTC","i":"1h","o":"90683.0","c":"90692.0","h":"90790.0","l":"90597.0","v":"270.69908","n":3363}]"""
    }
}
