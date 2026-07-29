package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import java.net.URI
import java.time.Instant

internal sealed interface SourceItem {
    val source: String
    val observedAt: Instant

    data class Ready(
        override val source: String,
        override val observedAt: Instant,
        val sourceUri: URI,
    ) : SourceItem

    data class Event(
        override val source: String,
        override val observedAt: Instant,
        val sourceUri: URI,
        val sourceEventId: String,
        val channel: InformationChannel,
        val mutation: InformationMutation,
        val eventTime: Instant,
        val authorId: String?,
        val language: String?,
        val text: String?,
        val canonicalUrl: String?,
        val sourceMetrics: Map<String, Double> = emptyMap(),
        val rawBody: ByteArray,
    ) : SourceItem

    data class RawCapture(
        override val source: String,
        override val observedAt: Instant,
        val sourceUri: URI,
        val mediaType: String,
        val rawBody: ByteArray,
    ) : SourceItem

    data class Quality(
        override val source: String,
        override val observedAt: Instant,
        val sourceUri: URI,
        val issueKind: String,
        val severity: String,
        val message: String,
    ) : SourceItem
}

internal interface InformationSourceAdapter {
    val source: String

    suspend fun collect(emit: suspend (SourceItem) -> Unit): Nothing
}
