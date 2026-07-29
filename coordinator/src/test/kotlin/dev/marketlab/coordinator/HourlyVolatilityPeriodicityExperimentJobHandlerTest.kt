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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class HourlyVolatilityPeriodicityExperimentJobHandlerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `production configuration freezes unseen ETH source and holdout`() {
        val config = HourlyVolatilityPeriodicityExperimentConfig()

        assertEquals(UUID.fromString("af958a14-31b2-3841-9a10-9a6762a1208b"), config.expectedSnapshotId)
        assertEquals(4_800, config.sourceRows)
        assertEquals(24, config.trailingVarianceRows)
        assertEquals(4_775, config.expectedLabeledRows)
        assertEquals(720, config.sealedHoldoutRows)
        assertEquals(4_054, config.expectedValidationRows)
        assertEquals(1, config.expectedBoundaryPurgedRows)
        assertEquals(2_160, config.minimumTrainingRows)
        assertEquals(168, config.testRows)
        assertEquals(11, config.foldCount)
        assertEquals(24, config.hacLag)
        assertEquals(1_782_615_600_000L, config.firstHoldoutDecisionEpochMillis)
        assertEquals(1_785_204_000_000L, config.lastHoldoutDecisionEpochMillis)
        assertEquals(1_785_207_600_000L, config.holdoutLabelToEpochMillis)
    }

    @Test
    fun `real observed ETH development excerpt exercises sealed variance path`() =
        runBlocking {
            val fixturePath =
                generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) {
                    it.parent
                }.map {
                    it.resolve(".testdata/fixtures/eth-hourly-periodicity-first100.json")
                }.firstOrNull(Files::isRegularFile)
                    ?: Path.of(".missing-real-periodicity-fixture")
            assumeTrue(
                Files.isRegularFile(fixturePath),
                "real Hyperliquid fixture is intentionally external to source control",
            )
            val fixture = fixture(Files.readAllBytes(fixturePath))
            assertEquals(REAL_EXCERPT_SHA256, fixture.snapshot.objects.single().contentHash.hex)
            val run = runRow(fixture.snapshotRow.id)
            val persistence = PeriodicityFakePersistence(run, fixture.snapshotRow)
            val handler =
                HourlyVolatilityPeriodicityExperimentJobHandler(
                    persistence,
                    StoredHyperliquidSnapshotReader(fixture.store),
                    ExperimentArtifactStore(temporaryDirectory.resolve("artifacts")),
                    SOURCE_REVISION,
                    PLAN_HASH,
                    testConfig(fixture),
                )

            val outcome = handler.execute(lease(run))

            val success = assertIs<JobExecutionResult.Succeeded>(outcome, outcome.toString())
            assertEquals(RunStatus.SUCCEEDED, persistence.run.status)
            assertEquals(PromotionStatus.BLOCKED, persistence.run.promotionStatus)
            assertEquals(2, persistence.artifacts.size)
            assertEquals(1, persistence.trials.size)
            assertEquals(TrialStatus.SUCCEEDED, persistence.trials.single().status)
            assertEquals("true", success.result.getValue("realDataOnly").jsonPrimitive.content)

            val predictions =
                readJson(
                    persistence.artifacts.single { it.kind == "PREDICTIONS" }.artifact.uri,
                )
            val observations = predictions.getValue("observations").jsonArray
            assertEquals(22, observations.size)
            assertEquals(
                16,
                observations.count {
                    it.jsonObject.getValue("phase").jsonPrimitive.content == "DEVELOPMENT"
                },
            )
            assertEquals(
                6,
                observations.count {
                    it.jsonObject.getValue("phase").jsonPrimitive.content == "SEALED_HOLDOUT"
                },
            )
            assertTrue(
                observations.all {
                    it.jsonObject.getValue("actualVariance").jsonPrimitive.content.toDouble() > 0.0
                },
            )

            val report =
                readJson(
                    persistence.artifacts.single { it.kind == "REPORT" }.artifact.uri,
                )
            assertEquals(100, report.getValue("sourceCandleCount").jsonPrimitive.content.toInt())
            assertEquals(75, report.getValue("totalLabeledRowCount").jsonPrimitive.content.toInt())
            assertEquals(68, report.getValue("validationRowCount").jsonPrimitive.content.toInt())
            assertEquals(1, report.getValue("holdoutBoundaryPurgedRows").jsonPrimitive.content.toInt())
            assertEquals(6, report.getValue("sealedHoldoutRows").jsonPrimitive.content.toInt())
            assertEquals(
                26,
                report.getValue("fittedDevelopmentCoefficients").jsonObject.size,
            )
            val holdout =
                report.getValue("metrics").jsonObject.getValue("sealedHoldout").jsonObject
            assertEquals(2, holdout.getValue("controls").jsonArray.size)
            persistence.artifacts.forEach {
                val path = Path.of(URI(it.artifact.uri))
                assertTrue(Files.isRegularFile(path))
                assertEquals(it.artifact.byteCount, Files.size(path))
            }

            val recovered = handler.execute(lease(persistence.run))
            assertEquals(
                "true",
                assertIs<JobExecutionResult.Succeeded>(recovered)
                    .result.getValue("recovered").jsonPrimitive.content,
            )
            assertEquals(2, persistence.artifacts.size)
        }

    private fun fixture(raw: ByteArray): RealPeriodicityFixture {
        val store = ContentAddressedDataStore(temporaryDirectory.resolve("raw"))
        val manifest =
            store.putRaw(
                raw,
                RawObjectDescriptor(
                    source = DataSourceId("hyperliquid-mainnet"),
                    request =
                        SourceRequest(
                            method = "POST",
                            uri = "https://api.hyperliquid.xyz/info",
                            parameters = mapOf("body" to REQUEST),
                        ),
                    retrievedAt = RETRIEVED_AT,
                    schemaVersion = "hyperliquid-info-json-v1",
                    adapterVersion = "hyperliquid-v1",
                    rowCount = 100,
                    eventTimeRange =
                        TimeRange(
                            MarketTimestamp(1_767_927_600_000L),
                            MarketTimestamp(1_768_287_600_000L),
                        ),
                    availabilityTimeRange =
                        TimeRange(
                            MarketTimestamp(1_767_931_200_000L),
                            MarketTimestamp(1_768_287_600_001L),
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
                            key = "hyperliquid_eth_hourly_candles",
                            observation = ObservationKind.CANDLE,
                            sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                            sampling = Sampling.FixedDuration(HOUR),
                            requiredFields = listOf("close", "close_time"),
                            minimumHistoryMillis = 99L * HOUR,
                        ),
                    ),
                objects = listOf(manifest),
                quality = DataQualityReport(MarketTimestamp(RETRIEVED_AT.toEpochMilli()), emptyList()),
            )
        val snapshotId = UUID.randomUUID()
        val row =
            SnapshotRow(
                snapshotId,
                snapshot.manifestHash.hex,
                SnapshotStatus.READY,
                RETRIEVED_AT,
                buildJsonObject {
                    put("contractSnapshotId", snapshot.id.value)
                    put(
                        "contractSnapshot",
                        JSON.encodeToJsonElement(DataSnapshot.serializer(), snapshot),
                    )
                },
                1,
                0,
            )
        return RealPeriodicityFixture(store, snapshot, row)
    }

    private fun testConfig(fixture: RealPeriodicityFixture) =
        HourlyVolatilityPeriodicityExperimentConfig(
            expectedSnapshotId = fixture.snapshotRow.id,
            expectedSnapshotManifestHash = fixture.snapshot.manifestHash.hex,
            expectedRawObjectHash = REAL_EXCERPT_SHA256,
            sourceRows = 100,
            trailingVarianceRows = 24,
            expectedLabeledRows = 75,
            sealedHoldoutRows = 6,
            expectedValidationRows = 68,
            expectedBoundaryPurgedRows = 1,
            minimumTrainingRows = 48,
            testRows = 4,
            stepRows = 4,
            foldCount = 4,
            hacLag = 2,
            expectedFirstAvailableAtEpochMillis = 1_767_931_200_000L,
            expectedLastAvailableAtEpochMillis = 1_768_287_600_000L,
            firstHoldoutDecisionEpochMillis = 1_768_266_000_000L,
            lastHoldoutDecisionEpochMillis = 1_768_284_000_000L,
            holdoutLabelToEpochMillis = 1_768_287_600_000L,
            expectedRequestBody = REQUEST,
        )

    private fun runRow(snapshotId: UUID) =
        ExperimentRunRow(
            UUID.randomUUID(),
            HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID,
            "1.0.0",
            PLAN_HASH,
            snapshotId,
            RunStatus.QUEUED,
            PromotionStatus.NOT_EVALUATED,
            buildJsonObject {},
            null,
            null,
            null,
            RETRIEVED_AT,
            null,
            null,
        )

    private fun lease(run: ExperimentRunRow) =
        JobLease(
            JobRow(
                UUID.randomUUID(),
                "EXPERIMENT_RUN",
                "run",
                run.id.toString(),
                buildJsonObject {
                    put("runId", run.id.toString())
                    put("theoryId", run.theoryId)
                    put("theoryVersion", run.theoryVersion)
                    put("theoryPlanHash", run.theoryPlanHash)
                    put("snapshotId", run.snapshotId.toString())
                    put("parameters", run.parameters)
                },
                JobStatus.LEASED,
                0,
                RETRIEVED_AT,
                3,
                1,
                "periodicity-test",
                UUID.randomUUID(),
                RETRIEVED_AT.plusSeconds(120),
                false,
                null,
                null,
                RETRIEVED_AT,
                RETRIEVED_AT,
                null,
            ),
            UUID.randomUUID(),
        )

    private fun readJson(uri: String): JsonObject =
        JSON.parseToJsonElement(Files.readString(Path.of(URI(uri)))).jsonObject

    private data class RealPeriodicityFixture(
        val store: ContentAddressedDataStore,
        val snapshot: DataSnapshot,
        val snapshotRow: SnapshotRow,
    )

    private class PeriodicityFakePersistence(
        initial: ExperimentRunRow,
        private val snapshot: SnapshotRow,
    ) : ExperimentPersistence {
        var run = initial
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
            manifest: JsonElement?,
            metrics: JsonElement?,
            failure: JsonElement?,
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
            return FencedRunCompletion(run, artifacts.map { it.id })
        }

        override fun getSnapshot(id: UUID) = snapshot.takeIf { it.id == id }
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val REAL_EXCERPT_SHA256 =
            "b7bd45ef73d03af54ec8fcafd2df1e88d74dcbc57f66f4141d9a2ac4162c2c59"
        const val SOURCE_REVISION =
            "7777777777777777777777777777777777777777777777777777777777777777"
        const val REQUEST =
            """{"req":{"coin":"ETH","endTime":1785207599999,"interval":"1h","startTime":1767927600000},"type":"candleSnapshot"}"""
        val PLAN_HASH =
            TheoryPlanHasher.hash(
                requireNotNull(
                    AcademicTheoryRegistry.find(
                        TheoryId(HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID),
                    ),
                ).compile(),
            ).hex
        val RETRIEVED_AT = Instant.parse("2026-07-28T08:29:17.750Z")
        val JSON = Json { ignoreUnknownKeys = false }
    }
}
