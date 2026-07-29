package dev.marketlab.runner

import dev.marketlab.contracts.ArtifactId
import dev.marketlab.contracts.AttemptId
import dev.marketlab.contracts.CapabilityId
import dev.marketlab.contracts.JobId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.RunId
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.TrialId
import dev.marketlab.contracts.WorkerRequestId
import dev.marketlab.contracts.worker.DatasetFormat
import dev.marketlab.contracts.worker.DatasetRef
import dev.marketlab.contracts.worker.FitPredictRequest
import dev.marketlab.contracts.worker.LabeledTrainingDatasetRef
import dev.marketlab.contracts.worker.WorkerInvocationManifest
import dev.marketlab.contracts.worker.WorkerOutputConstraints
import dev.marketlab.contracts.worker.WorkerResourceClass
import dev.marketlab.contracts.worker.WorkerRuntime
import dev.marketlab.contracts.worker.WorkerTask
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ManifestValidationTest {
    @Test
    fun `accepts an invocation pinned to the configured digest`() {
        validateManifest(request, profile, manifest(), Instant.ofEpochMilli(1_785_210_252_000))
    }

    @Test
    fun `rejects a manifest that asks beyond its fixed capability`() {
        assertFailsWith<IllegalArgumentException> {
            validateManifest(
                request,
                profile,
                manifest(resources = WorkerResourceClass(cpuCount = 21, memoryMiB = 49_152)),
                Instant.ofEpochMilli(1_785_210_252_000),
            )
        }
    }

    private fun manifest(
        resources: WorkerResourceClass = WorkerResourceClass(cpuCount = 20, memoryMiB = 49_152),
    ): WorkerInvocationManifest {
        val training = dataset("training", "a".repeat(64))
        val test = dataset("test", "b".repeat(64))
        val deadline = MarketTimestamp(1_785_296_652_000)
        return WorkerInvocationManifest(
            jobId = JobId("job-1"),
            attemptId = AttemptId("attempt-1"),
            runHash = Sha256Digest("c".repeat(64)),
            capabilityId = CapabilityId("kotlin-backtest-v1"),
            expectedImageDigest = Sha256Digest("d".repeat(64)),
            task = WorkerTask.FitPredict(
                FitPredictRequest(
                    requestId = WorkerRequestId("request-1"),
                    runId = RunId("run-1"),
                    trialId = TrialId("trial-1"),
                    runtime = WorkerRuntime.KOTLIN,
                    estimator = "historical-mean",
                    parameters = emptyMap(),
                    randomSeed = 17,
                    training = LabeledTrainingDatasetRef(training, "label"),
                    testFeatures = test,
                    deadline = deadline,
                ),
            ),
            seed = 17,
            deadline = deadline,
            immutableInputHashes = listOf(training.contentHash, test.contentHash),
            resourceClass = resources,
            outputConstraints = WorkerOutputConstraints(
                outputDirectory = "/work/output",
                maximumBytes = 10_000_000,
                allowedMediaTypes = listOf("application/json", "application/vnd.apache.parquet"),
            ),
        )
    }

    private fun dataset(name: String, hash: String) =
        DatasetRef(
            artifactId = ArtifactId(name),
            uri = "file:///work/input/$name.parquet",
            contentHash = Sha256Digest(hash),
            format = DatasetFormat.PARQUET,
            featureColumns = listOf("funding_rate"),
            rowIdColumn = "row_id",
        )

    private val request = LaunchWorkerRequest(
        jobId = "job-1",
        attemptId = "attempt-1",
        capabilityId = "kotlin-backtest-v1",
    )
    private val profile = WorkerProfile(
        capabilityId = "kotlin-backtest-v1",
        image = "registry.example/marketlab/worker@sha256:${"d".repeat(64)}",
        cpuCount = 20,
        memoryMiB = 49_152,
        pidsLimit = 512,
        tmpfsBytes = 8L shl 30,
        maxRuntimeSeconds = 86_400,
    )
}
