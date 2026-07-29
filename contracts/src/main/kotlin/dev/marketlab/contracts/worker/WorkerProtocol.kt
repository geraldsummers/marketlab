package dev.marketlab.contracts.worker

import dev.marketlab.contracts.ArtifactId
import dev.marketlab.contracts.AttemptId
import dev.marketlab.contracts.CapabilityId
import dev.marketlab.contracts.JobId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.RunId
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.TrialId
import dev.marketlab.contracts.WorkerRequestId
import kotlinx.serialization.Serializable

const val WORKER_PROTOCOL_VERSION: Int = 1

@Serializable
enum class DatasetFormat {
    PARQUET,
}

@Serializable
data class DatasetRef(
    val artifactId: ArtifactId,
    val uri: String,
    val contentHash: Sha256Digest,
    val format: DatasetFormat,
    val featureColumns: List<String>,
    val rowIdColumn: String,
) {
    init {
        require(uri.isNotBlank()) { "dataset URI cannot be blank" }
        require(featureColumns.isNotEmpty() && featureColumns.none(String::isBlank)) {
            "feature columns cannot be empty or blank"
        }
        require(featureColumns.distinct().size == featureColumns.size) { "feature columns must be unique" }
        require(rowIdColumn.isNotBlank()) { "row id column cannot be blank" }
        require(rowIdColumn !in featureColumns) { "row id cannot also be a model feature" }
    }
}

@Serializable
data class LabeledTrainingDatasetRef(
    val features: DatasetRef,
    val labelColumn: String,
) {
    init {
        require(labelColumn.isNotBlank()) { "training label column cannot be blank" }
        require(labelColumn !in features.featureColumns) { "label cannot also be a model feature" }
        require(labelColumn != features.rowIdColumn) { "label cannot be the row id" }
    }
}

@Serializable
enum class WorkerRuntime {
    KOTLIN,
    PYTHON,
    R,
}

/**
 * Deliberately has no test-label field. Label sealing is enforced by protocol shape,
 * not by a convention inside a worker.
 */
@Serializable
data class FitPredictRequest(
    val protocolVersion: Int = WORKER_PROTOCOL_VERSION,
    val requestId: WorkerRequestId,
    val runId: RunId,
    val trialId: TrialId,
    val runtime: WorkerRuntime,
    val estimator: String,
    val parameters: Map<String, String>,
    val randomSeed: Long,
    val training: LabeledTrainingDatasetRef,
    val testFeatures: DatasetRef,
    val deadline: MarketTimestamp,
) {
    init {
        require(protocolVersion == WORKER_PROTOCOL_VERSION) { "unsupported worker protocol version" }
        require(estimator.isNotBlank()) { "estimator cannot be blank" }
        require(training.features.featureColumns == testFeatures.featureColumns) {
            "training and test feature schemas must match"
        }
    }
}

@Serializable
data class PredictionArtifactRef(
    val artifactId: ArtifactId,
    val uri: String,
    val contentHash: Sha256Digest,
    val rowIdColumn: String,
    val predictionColumn: String,
) {
    init {
        require(uri.isNotBlank()) { "prediction URI cannot be blank" }
        require(rowIdColumn.isNotBlank()) { "row id column cannot be blank" }
        require(predictionColumn.isNotBlank() && predictionColumn != rowIdColumn) {
            "prediction column must be non-blank and distinct from row id"
        }
    }
}

@Serializable
sealed interface FitPredictResponse {
    val protocolVersion: Int
    val requestId: WorkerRequestId

    @Serializable
    data class Success(
        override val protocolVersion: Int = WORKER_PROTOCOL_VERSION,
        override val requestId: WorkerRequestId,
        val predictions: PredictionArtifactRef,
        val model: PredictionArtifactRef? = null,
        val diagnostics: Map<String, String> = emptyMap(),
    ) : FitPredictResponse {
        init {
            require(protocolVersion == WORKER_PROTOCOL_VERSION) { "unsupported worker protocol version" }
        }
    }

    @Serializable
    data class Failure(
        override val protocolVersion: Int = WORKER_PROTOCOL_VERSION,
        override val requestId: WorkerRequestId,
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : FitPredictResponse {
        init {
            require(protocolVersion == WORKER_PROTOCOL_VERSION) { "unsupported worker protocol version" }
            require(code.isNotBlank()) { "worker failure code cannot be blank" }
            require(message.isNotBlank()) { "worker failure message cannot be blank" }
        }
    }
}

@Serializable
sealed interface WorkerTask {
    @Serializable
    data class FitPredict(val request: FitPredictRequest) : WorkerTask
}

@Serializable
data class WorkerResourceClass(
    val cpuCount: Int,
    val memoryMiB: Int,
    val gpuCount: Int = 0,
) {
    init {
        require(cpuCount > 0) { "worker CPU count must be positive" }
        require(memoryMiB > 0) { "worker memory must be positive" }
        require(gpuCount in 0..1) { "v1 workers support at most one GPU" }
    }
}

@Serializable
data class WorkerOutputConstraints(
    val outputDirectory: String,
    val maximumBytes: Long,
    val allowedMediaTypes: List<String>,
) {
    init {
        require(outputDirectory.startsWith("/") && ".." !in outputDirectory.split('/')) {
            "worker output directory must be an absolute, normalized path"
        }
        require(maximumBytes > 0) { "worker output limit must be positive" }
        require(allowedMediaTypes.isNotEmpty() && allowedMediaTypes.none(String::isBlank)) {
            "worker output media types cannot be empty or blank"
        }
        require(allowedMediaTypes == allowedMediaTypes.distinct().sorted()) {
            "worker output media types must be unique and sorted"
        }
    }
}

/**
 * Complete, immutable runner authorization. A runner executes this manifest only after
 * verifying its image, input hashes, resource class, output constraints, and deadline.
 */
@Serializable
data class WorkerInvocationManifest(
    val protocolVersion: Int = WORKER_PROTOCOL_VERSION,
    val jobId: JobId,
    val attemptId: AttemptId,
    val runHash: Sha256Digest,
    val capabilityId: CapabilityId,
    val expectedImageDigest: Sha256Digest,
    val task: WorkerTask,
    val seed: Long,
    val deadline: MarketTimestamp,
    val immutableInputHashes: List<Sha256Digest>,
    val resourceClass: WorkerResourceClass,
    val outputConstraints: WorkerOutputConstraints,
) {
    init {
        require(protocolVersion == WORKER_PROTOCOL_VERSION) { "unsupported worker protocol version" }
        require(immutableInputHashes.isNotEmpty()) { "worker invocation must pin immutable inputs" }
        require(immutableInputHashes == immutableInputHashes.distinct().sortedBy(Sha256Digest::hex)) {
            "immutable input hashes must be unique and sorted"
        }
        val fitPredict = (task as? WorkerTask.FitPredict)?.request
        require(fitPredict == null || fitPredict.randomSeed == seed) {
            "manifest and task seeds must match"
        }
        require(fitPredict == null || fitPredict.deadline == deadline) {
            "manifest and task deadlines must match"
        }
        if (fitPredict != null) {
            val requiredInputHashes = setOf(
                fitPredict.training.features.contentHash,
                fitPredict.testFeatures.contentHash,
            )
            require(immutableInputHashes.toSet().containsAll(requiredInputHashes)) {
                "manifest must pin every task input hash"
            }
        }
    }
}
