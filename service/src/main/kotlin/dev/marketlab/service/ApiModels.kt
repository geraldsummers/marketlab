package dev.marketlab.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
)

@Serializable
data class Page<T>(
    val items: List<T>,
)

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
data class TheoryView(
    val id: String,
    val version: String,
    val planHash: String,
    val descriptor: JsonObject,
    val registeredAt: String,
)

@Serializable
data class SnapshotView(
    val id: String,
    val manifestHash: String,
    val status: String,
    val createdAt: String,
    val objectCount: Int,
    val fatalFindingCount: Int,
    val metadata: JsonObject,
)

@Serializable
data class QualityFindingView(
    val id: Long,
    val snapshotId: String?,
    val dataObjectId: String?,
    val severity: String,
    val code: String,
    val eventTimeStart: String?,
    val eventTimeEnd: String?,
    val details: JsonElement,
    val detectedAt: String,
)

@Serializable
enum class IngestionSource {
    HYPERLIQUID_REST,
    HYPERLIQUID_WEBSOCKET,
    HYPERLIQUID_S3,
}

@Serializable
enum class IngestionDataKind {
    CANDLES,
    TRADES,
    BBO,
    L2_BOOK,
    ASSET_CONTEXT,
    FUNDING,
    OPEN_INTEREST,
    ORACLE_MARK,
    METADATA,
}

@Serializable
data class IngestionRequest(
    val source: IngestionSource,
    val instruments: List<String>,
    val dataKinds: List<IngestionDataKind>,
    val startAt: String? = null,
    val endAt: String? = null,
    val candleInterval: String? = null,
    val l2SignificantFigures: Int? = null,
    val l2Mantissa: Int? = null,
)

@Serializable
data class RunRequest(
    val theoryId: String,
    val theoryVersion: String,
    val snapshotId: String,
    val parameters: JsonObject = buildJsonObject {},
)

@Serializable
data class AcceptedResponse(
    val resourceType: String,
    val resourceId: String,
    val jobId: String,
    val status: String = "QUEUED",
    val acceptedAt: String,
    val replayed: Boolean,
)

@Serializable
data class RunView(
    val id: String,
    val theoryId: String,
    val theoryVersion: String,
    val theoryPlanHash: String,
    val snapshotId: String,
    val status: String,
    val promotionStatus: String,
    val parameters: JsonObject,
    val manifest: JsonElement?,
    val metrics: JsonElement?,
    val failure: JsonElement?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?,
)

@Serializable
data class JobView(
    val id: String,
    val kind: String,
    val resourceType: String,
    val resourceId: String,
    val status: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val cancellationRequested: Boolean,
    val availableAt: String,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String?,
    val lastError: JsonElement?,
)

@Serializable
data class ArtifactView(
    val id: String,
    val runId: String?,
    val kind: String,
    val contentHash: String,
    val objectUri: String,
    val byteCount: Long,
    val mediaType: String,
    val manifest: JsonObject,
    val createdAt: String,
)

@Serializable
data class PaperSessionRequest(
    val theoryId: String,
    val theoryVersion: String,
    val runId: String,
    val initialEquity: String = "100000",
    val riskProfile: String = "DEFAULT_100K",
)

@Serializable
data class PaperSessionView(
    val id: String,
    val runId: String?,
    val theoryId: String,
    val theoryVersion: String,
    val status: String,
    val initialEquity: String,
    val riskProfile: String,
    val lastSequence: Long,
    val createdAt: String,
    val updatedAt: String,
    val stoppedAt: String?,
)

@Serializable
data class PaperActionRequest(
    val reason: String? = null,
)

@Serializable
data class PaperEventView(
    val id: String,
    val sessionId: String,
    val sequence: Long,
    val eventType: String,
    val eventTime: String,
    val observedAt: String,
    val marketEventRef: JsonElement?,
    val payload: JsonObject,
    val createdAt: String,
)

@Serializable
data class PositionView(
    val instrument: String,
    val quantity: String,
    val averagePrice: String?,
    val markPrice: String?,
    val realizedPnl: String,
    val unrealizedPnl: String,
)

@Serializable
data class OrderView(
    val orderId: String,
    val instrument: String,
    val side: String,
    val orderType: String,
    val status: String,
    val quantity: String,
    val filledQuantity: String,
    val limitPrice: String?,
)

@Serializable
data class PaperActionResponse(
    val sessionId: String,
    val eventId: String,
    val sequence: Long,
    val status: String,
)

@Serializable
data class ApiDescription(
    val openapi: String = "3.1.0",
    val info: ApiInfo = ApiInfo(),
    val paths: Map<String, JsonObject>,
)

@Serializable
data class ApiInfo(
    val title: String = "Marketlab Control Plane",
    val version: String = "1.0.0",
)

@Serializable
enum class PaperAction {
    @SerialName("start")
    START,

    @SerialName("pause")
    PAUSE,

    @SerialName("stop")
    STOP,
}
