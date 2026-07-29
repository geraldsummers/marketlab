package dev.marketlab.collector

import kotlinx.serialization.Serializable

internal const val SEGMENT_SCHEMA_VERSION = "marketlab.hyperliquid.websocket-segment.v1"
internal const val RECORD_SCHEMA_VERSION = "marketlab.hyperliquid.websocket-record.v1"
internal const val MANIFEST_SCHEMA_VERSION = "marketlab.hyperliquid.websocket-manifest.v1"
internal const val INDEX_SCHEMA_VERSION = "marketlab.hyperliquid.websocket-index-entry.v1"
internal const val READY_SCHEMA_VERSION = "marketlab.hyperliquid.websocket-ready.v1"
internal const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
internal const val HYPERLIQUID_MAINNET_WS_URI = "wss://api.hyperliquid.xyz/ws"

@Serializable
internal data class SubscriptionDescriptor(
    val channel: String,
    val coin: String,
    val expectedMaximumGapMillis: Long,
)

@Serializable
internal data class SegmentHeaderRecord(
    val recordType: String = "segment_header",
    val schemaVersion: String = SEGMENT_SCHEMA_VERSION,
    val recordSchemaVersion: String = RECORD_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val sourceUri: String = HYPERLIQUID_MAINNET_WS_URI,
    val production: Boolean = true,
    val sourceRevision: String,
    val coin: String,
    val subscriptions: List<SubscriptionDescriptor>,
    val openedAtEpochMillis: Long,
)

@Serializable
internal data class ObservationRecord(
    val recordType: String = "observation",
    val schemaVersion: String = RECORD_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val production: Boolean = true,
    val sourceRevision: String,
    val subscription: String,
    val coin: String,
    val eventKind: String,
    val eventId: String,
    val instrument: String,
    val exchangeTimeEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val availableAtEpochMillis: Long,
    val sequence: Long?,
    val rawEncoding: String = "base64",
    val rawBodyBase64: String,
    val rawSha256: String,
    val rawByteCount: Long,
)

@Serializable
internal data class ConnectionRecord(
    val recordType: String = "connection",
    val schemaVersion: String = RECORD_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val production: Boolean = true,
    val sourceRevision: String,
    val subscription: String,
    val coin: String,
    val connectionOrdinal: Long,
    val connectedAtEpochMillis: Long,
)

@Serializable
internal data class SubscriptionAcknowledgementRecord(
    val recordType: String = "subscription_acknowledgement",
    val schemaVersion: String = RECORD_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val production: Boolean = true,
    val sourceRevision: String,
    val subscription: String,
    val coin: String,
    val acknowledgedAtEpochMillis: Long,
    val rawEncoding: String = "base64",
    val rawBodyBase64: String,
    val rawSha256: String,
    val rawByteCount: Long,
)

@Serializable
internal data class CollectorReadyMarker(
    val schemaVersion: String = READY_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val production: Boolean = true,
    val sourceRevision: String,
    val coin: String,
    val subscriptions: List<String>,
    val startedAtEpochMillis: Long,
    val readyAtEpochMillis: Long,
)

@Serializable
internal data class QualityRecord(
    val recordType: String = "quality_signal",
    val schemaVersion: String = RECORD_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val production: Boolean = true,
    val sourceRevision: String,
    val subscription: String,
    val coin: String,
    val observedAtEpochMillis: Long,
    val issueKind: String,
    val severity: String,
    val message: String,
    val rangeFromInclusiveEpochMillis: Long?,
    val rangeToExclusiveEpochMillis: Long?,
    val objectId: String?,
)

@Serializable
internal data class StreamSegmentManifest(
    val schemaVersion: String = MANIFEST_SCHEMA_VERSION,
    val segmentSchemaVersion: String = SEGMENT_SCHEMA_VERSION,
    val recordSchemaVersion: String = RECORD_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val sourceUri: String = HYPERLIQUID_MAINNET_WS_URI,
    val production: Boolean = true,
    val sourceRevision: String,
    val coin: String,
    val subscriptions: List<SubscriptionDescriptor>,
    val segmentUri: String,
    val segmentSha256: String,
    val byteCount: Long,
    val recordCount: Long,
    val observationCount: Long,
    val distinctRawMessageCount: Long,
    val connectionCount: Long,
    val subscriptionAcknowledgementCount: Long,
    val qualitySignalCount: Long,
    val openedAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long,
    val firstEventTimeEpochMillis: Long?,
    val lastEventTimeEpochMillis: Long?,
    val firstReceivedTimeEpochMillis: Long?,
    val lastReceivedTimeEpochMillis: Long?,
    val firstAvailabilityTimeEpochMillis: Long?,
    val lastAvailabilityTimeEpochMillis: Long?,
)

@Serializable
internal data class SegmentIndexEntry(
    val schemaVersion: String = INDEX_SCHEMA_VERSION,
    val source: String = HYPERLIQUID_SOURCE,
    val production: Boolean = true,
    val sourceRevision: String,
    val coin: String,
    val manifestUri: String,
    val manifestSha256: String,
    val segmentUri: String,
    val segmentSha256: String,
    val appendedAtEpochMillis: Long,
)
