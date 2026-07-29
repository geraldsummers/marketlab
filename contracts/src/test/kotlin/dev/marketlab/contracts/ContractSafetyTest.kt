package dev.marketlab.contracts

import dev.marketlab.contracts.market.EventHeader
import dev.marketlab.contracts.worker.DatasetFormat
import dev.marketlab.contracts.worker.DatasetRef
import dev.marketlab.contracts.worker.FitPredictRequest
import dev.marketlab.contracts.worker.LabeledTrainingDatasetRef
import dev.marketlab.contracts.worker.WorkerRuntime
import dev.marketlab.contracts.paper.PaperRiskLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractSafetyTest {
    @Test
    fun `decimals have one exact wire representation`() {
        assertEquals("100", DecimalValue.of("1e2").canonical)
        assertEquals("0", DecimalValue.of("-0.000").canonical)
        assertFailsWith<IllegalArgumentException> { DecimalValue("1.00") }
        assertFailsWith<IllegalArgumentException> { DecimalValue("NaN") }
    }

    @Test
    fun `historical receipt may follow original causal availability`() {
        val header = EventHeader(
            id = MarketEventId("hl:trade:real-record"),
            source = DataSourceId("hyperliquid-mainnet"),
            instrument = InstrumentId("hyperliquid:BTC-PERP"),
            exchangeTime = MarketTimestamp(1_700_000_000_000),
            receivedAt = MarketTimestamp(1_800_000_000_000),
            availableAt = MarketTimestamp(1_700_000_000_100),
        )

        assertTrue(header.receivedAt > header.availableAt)
    }

    @Test
    fun `paper account defaults lock the registered risk envelope`() {
        val defaults = PaperRiskLimits.DEFAULT

        assertEquals("100000", defaults.initialEquity.canonical)
        assertEquals("1", defaults.maximumGrossLeverage.canonical)
        assertEquals("0.25", defaults.maximumInstrumentFraction.canonical)
        assertEquals("0.1", defaults.drawdownHaltFraction.canonical)
    }

    @Test
    fun `worker protocol structurally seals test labels`() {
        val digest = Sha256Digest("0".repeat(64))
        val trainingFeatures = DatasetRef(
            artifactId = ArtifactId("train-features"),
            uri = "/inputs/train.parquet",
            contentHash = digest,
            format = DatasetFormat.PARQUET,
            featureColumns = listOf("basis", "funding"),
            rowIdColumn = "row_id",
        )
        val request = FitPredictRequest(
            requestId = WorkerRequestId("worker-request"),
            runId = RunId("run"),
            trialId = TrialId("trial"),
            runtime = WorkerRuntime.KOTLIN,
            estimator = "linear",
            parameters = emptyMap(),
            randomSeed = 7,
            training = LabeledTrainingDatasetRef(trainingFeatures, "future_return"),
            testFeatures = trainingFeatures.copy(
                artifactId = ArtifactId("test-features"),
                uri = "/inputs/test.parquet",
            ),
            deadline = MarketTimestamp(1_900_000_000_000),
        )

        val encoded = Json.encodeToJsonElement(FitPredictRequest.serializer(), request).jsonObject
        assertTrue("labelColumn" in encoded.getValue("training").jsonObject)
        assertFalse("labelColumn" in encoded.getValue("testFeatures").jsonObject)
    }
}
