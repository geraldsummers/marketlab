package dev.marketlab.contracts.jobs

import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.contracts.JobId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.RunId
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.SnapshotId
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.TrialId
import dev.marketlab.contracts.data.ArtifactManifest
import kotlinx.serialization.Serializable

@Serializable
enum class JobKind {
    INGESTION,
    DATA_QUALITY,
    EXPERIMENT,
    PAPER_REPLAY,
}

@Serializable
enum class JobStatus {
    QUEUED,
    LEASED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Serializable
data class JobFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
) {
    init {
        require(code.isNotBlank()) { "failure code cannot be blank" }
        require(message.isNotBlank()) { "failure message cannot be blank" }
    }
}

@Serializable
data class JobRecord(
    val id: JobId,
    val kind: JobKind,
    val status: JobStatus,
    val createdAt: MarketTimestamp,
    val updatedAt: MarketTimestamp,
    val attempt: Int,
    val leaseOwner: String? = null,
    val leaseExpiresAt: MarketTimestamp? = null,
    val failure: JobFailure? = null,
) {
    init {
        require(updatedAt >= createdAt) { "job update cannot precede creation" }
        require(attempt >= 0) { "job attempt cannot be negative" }
        require((leaseOwner == null) == (leaseExpiresAt == null)) {
            "lease owner and expiry must be set together"
        }
    }
}

@Serializable
enum class RunStatus {
    REGISTERED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    REJECTED,
    INCONCLUSIVE,
    FAILED,
    CANCELLED,
}

@Serializable
data class RunMetric(
    val name: String,
    val value: FiniteDouble,
    val unit: String? = null,
    val fold: Int? = null,
    val confidenceLower: FiniteDouble? = null,
    val confidenceUpper: FiniteDouble? = null,
) {
    init {
        require(name.isNotBlank()) { "metric name cannot be blank" }
        require(fold == null || fold >= 0) { "fold cannot be negative" }
        require((confidenceLower == null) == (confidenceUpper == null)) {
            "confidence interval bounds must be set together"
        }
        require(
            confidenceLower == null ||
                confidenceUpper == null ||
                confidenceLower.value <= confidenceUpper.value,
        ) {
            "confidence interval is inverted"
        }
    }
}

@Serializable
data class TrialRecord(
    val id: TrialId,
    val parameters: Map<String, String>,
    val status: RunStatus,
    val metrics: List<RunMetric>,
    val startedAt: MarketTimestamp?,
    val completedAt: MarketTimestamp?,
    val failure: JobFailure? = null,
) {
    init {
        require(completedAt == null || startedAt == null || completedAt >= startedAt) {
            "trial completion cannot precede its start"
        }
    }
}

@Serializable
data class ExperimentRun(
    val id: RunId,
    val theoryId: TheoryId,
    val theoryPlanHash: Sha256Digest,
    val snapshotId: SnapshotId,
    val status: RunStatus,
    val createdAt: MarketTimestamp,
    val startedAt: MarketTimestamp? = null,
    val completedAt: MarketTimestamp? = null,
    val trials: List<TrialRecord> = emptyList(),
    val metrics: List<RunMetric> = emptyList(),
    val artifacts: List<ArtifactManifest> = emptyList(),
    val failure: JobFailure? = null,
) {
    init {
        require(startedAt == null || startedAt >= createdAt) { "run start cannot precede creation" }
        require(completedAt == null || completedAt >= (startedAt ?: createdAt)) {
            "run completion cannot precede its start"
        }
        require(artifacts.all { it.runId == id }) { "all artifacts must belong to this run" }
    }
}
