package dev.marketlab.contracts.data

import dev.marketlab.contracts.ArtifactId
import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.RunId
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.SnapshotId
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.market.InstrumentKind
import kotlinx.serialization.Serializable

@Serializable
enum class ObservationKind {
    TRADE,
    BBO,
    L2_BOOK,
    CANDLE,
    FUNDING,
    OPEN_INTEREST,
    MARK_ORACLE,
    INSTRUMENT_METADATA,
    LIQUIDATION,
    DERIVATIVE_CHAIN,
    ON_CHAIN,
    MACROECONOMIC,
    SOCIAL_MESSAGE,
    NEWS_ITEM,
}

@Serializable
sealed interface Sampling {
    @Serializable
    data object EventTime : Sampling

    @Serializable
    data class FixedDuration(val millis: Long) : Sampling {
        init {
            require(millis > 0) { "sampling duration must be positive" }
        }
    }
}

@Serializable
data class DataRequirement(
    val key: String,
    val observation: ObservationKind,
    val sourcePreference: List<DataSourceId>,
    val instrumentKinds: List<InstrumentKind>,
    val sampling: Sampling,
    val requiredFields: List<String>,
    val minimumHistoryMillis: Long,
    val maximumAvailabilityLagMillis: Long? = null,
) {
    init {
        require(key.isNotBlank()) { "data requirement key cannot be blank" }
        require(sourcePreference.isNotEmpty()) { "at least one real data source is required" }
        require(instrumentKinds.isNotEmpty()) { "at least one instrument kind is required" }
        require(requiredFields.isNotEmpty() && requiredFields.none(String::isBlank)) {
            "required fields cannot be empty or blank"
        }
        require(requiredFields.distinct().size == requiredFields.size) { "required fields must be unique" }
        require(minimumHistoryMillis > 0) { "minimum history must be positive" }
        require(maximumAvailabilityLagMillis == null || maximumAvailabilityLagMillis >= 0) {
            "maximum availability lag cannot be negative"
        }
    }
}

@Serializable
data class SourceRequest(
    val method: String,
    val uri: String,
    val parameters: Map<String, String> = emptyMap(),
) {
    init {
        require(method.isNotBlank()) { "source request method cannot be blank" }
        require(uri.isNotBlank()) { "source request URI cannot be blank" }
    }
}

@Serializable
data class ObjectProvenance(
    val source: DataSourceId,
    val request: SourceRequest,
    val retrievedAt: MarketTimestamp,
    val schemaVersion: String,
    val adapterVersion: String,
    val production: Boolean,
) {
    init {
        require(schemaVersion.isNotBlank()) { "schema version cannot be blank" }
        require(adapterVersion.isNotBlank()) { "adapter version cannot be blank" }
    }
}

@Serializable
data class DataObjectManifest(
    val id: ArtifactId,
    val uri: String,
    val contentHash: Sha256Digest,
    val byteCount: Long,
    val rowCount: Long,
    val eventTimeRange: TimeRange,
    val availabilityTimeRange: TimeRange,
    val provenance: ObjectProvenance,
) {
    init {
        require(uri.isNotBlank()) { "data object URI cannot be blank" }
        require(byteCount > 0) { "data object must not be empty" }
        require(rowCount > 0) { "data object must contain observations" }
    }
}

@Serializable
enum class QualitySeverity {
    INFO,
    WARNING,
    FATAL,
}

@Serializable
enum class QualityIssueKind {
    SEQUENCE_GAP,
    DUPLICATE,
    INVALID_BOOK,
    INVALID_CANDLE,
    TIMESTAMP_UNIT_CHANGE,
    UNFINISHED_CANDLE,
    MISSING_METADATA,
    STALE_DATA,
    INSUFFICIENT_COVERAGE,
    SOURCE_REVISION,
    SOURCE_GAP,
    SCHEMA_DRIFT,
    CLOCK_REGRESSION,
    INVALID_SIGNATURE,
    ENTITY_RESOLUTION,
}

@Serializable
data class DataQualityIssue(
    val kind: QualityIssueKind,
    val severity: QualitySeverity,
    val message: String,
    val timeRange: TimeRange? = null,
    val objectId: ArtifactId? = null,
) {
    init {
        require(message.isNotBlank()) { "quality issue message cannot be blank" }
    }
}

@Serializable
data class DataQualityReport(
    val checkedAt: MarketTimestamp,
    val issues: List<DataQualityIssue>,
) {
    val usable: Boolean
        get() = issues.none { it.severity == QualitySeverity.FATAL }
}

@Serializable
data class DataSnapshot(
    val id: SnapshotId,
    val createdAt: MarketTimestamp,
    val requirements: List<DataRequirement>,
    val objects: List<DataObjectManifest>,
    val quality: DataQualityReport,
    val manifestHash: Sha256Digest,
) {
    init {
        require(requirements.isNotEmpty()) { "snapshot must state its data requirements" }
        require(objects.isNotEmpty()) { "snapshot must contain real data objects" }
        require(objects.all { it.provenance.production }) { "non-production data cannot enter an empirical snapshot" }
    }
}

@Serializable
enum class ArtifactKind {
    MODEL,
    PREDICTIONS,
    METRICS,
    REPORT,
    FOLD_ASSIGNMENTS,
    TRIAL_LEDGER,
    PAPER_LEDGER_EXPORT,
}

@Serializable
data class ArtifactManifest(
    val id: ArtifactId,
    val runId: RunId,
    val kind: ArtifactKind,
    val uri: String,
    val contentHash: Sha256Digest,
    val byteCount: Long,
    val mediaType: String,
    val createdAt: MarketTimestamp,
    val producerVersion: String,
) {
    init {
        require(uri.isNotBlank()) { "artifact URI cannot be blank" }
        require(byteCount > 0) { "artifact must not be empty" }
        require(mediaType.isNotBlank()) { "artifact media type cannot be blank" }
        require(producerVersion.isNotBlank()) { "producer version cannot be blank" }
    }
}
