package dev.marketlab.contracts.data

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import kotlinx.serialization.Serializable

@Serializable
enum class InformationMutation {
    CREATE,
    UPDATE,
    DELETE,
}

@Serializable
enum class InformationChannel {
    SOCIAL,
    NEWS,
}

/**
 * A public-information event ordered by the time Marketlab first received it.
 *
 * `eventTime` is supplied by the publisher and is never trusted as a causal
 * clock. Features may use an event only at or after `receivedAt`.
 */
@Serializable
data class PublicInformationEvent(
    val source: DataSourceId,
    val sourceEventId: String,
    val channel: InformationChannel,
    val mutation: InformationMutation,
    val eventTime: MarketTimestamp,
    val receivedAt: MarketTimestamp,
    val availableAt: MarketTimestamp,
    val authorIdHash: Sha256Digest?,
    val language: String?,
    val text: String?,
    val canonicalUrl: String?,
    val matchedInstruments: List<InstrumentId>,
    val sourceMetrics: Map<String, Double> = emptyMap(),
    val rawContentHash: Sha256Digest,
    val adapterRevision: Sha256Digest,
    val production: Boolean,
) {
    init {
        require(sourceEventId.isNotBlank()) { "source event id cannot be blank" }
        require(availableAt == receivedAt) {
            "public information is available only at the local receive clock"
        }
        require(receivedAt.epochMillis >= eventTime.epochMillis || mutation == InformationMutation.CREATE) {
            "updates and deletions cannot be received before their declared event time"
        }
        require(language == null || LANGUAGE_TAG.matches(language)) {
            "language must be a normalized BCP-47 style tag"
        }
        require(matchedInstruments.distinct().size == matchedInstruments.size) {
            "matched instruments must be unique"
        }
        require(sourceMetrics.keys.none(String::isBlank) && sourceMetrics.values.all(Double::isFinite)) {
            "source metrics must have non-blank keys and finite values"
        }
        if (mutation == InformationMutation.DELETE) {
            require(text == null) { "deletion events must not carry mutable text" }
        } else {
            require(!text.isNullOrBlank()) { "create/update events require text" }
        }
        require(canonicalUrl == null || canonicalUrl.startsWith("https://")) {
            "canonical URLs must use HTTPS"
        }
        require(production) { "non-production public information cannot enter empirical snapshots" }
    }

    companion object {
        private val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[A-Z]{2})?")
    }
}

@Serializable
data class SentimentModelIdentity(
    val key: String,
    val upstreamRevision: String,
    val modelSha256: Sha256Digest,
    val tokenizerSha256: Sha256Digest,
    val preprocessingSha256: Sha256Digest,
) {
    init {
        require(key.isNotBlank()) { "sentiment model key cannot be blank" }
        require(UPSTREAM_REVISION.matches(upstreamRevision)) {
            "upstream model revision must be an immutable hexadecimal commit"
        }
    }

    private companion object {
        val UPSTREAM_REVISION = Regex("[0-9a-f]{40,64}")
    }
}

@Serializable
data class SentimentScore(
    val sourceEventId: String,
    val model: SentimentModelIdentity,
    val scoredAt: MarketTimestamp,
    val negativeProbability: Double,
    val neutralProbability: Double,
    val positiveProbability: Double,
) {
    init {
        require(sourceEventId.isNotBlank()) { "source event id cannot be blank" }
        val probabilities =
            listOf(negativeProbability, neutralProbability, positiveProbability)
        require(probabilities.all { it.isFinite() && it in 0.0..1.0 }) {
            "sentiment probabilities must be finite and between zero and one"
        }
        require(kotlin.math.abs(probabilities.sum() - 1.0) <= 1.0e-6) {
            "sentiment probabilities must sum to one"
        }
    }

    val polarity: Double
        get() = positiveProbability - negativeProbability
}

@Serializable
data class HyperliquidUniverseMember(
    val rank: Int,
    val instrument: InstrumentId,
    val symbol: String,
    val trailingThirtyDayNotional: Double,
    val listingAgeDays: Int,
) {
    init {
        require(rank in 1..10)
        require(symbol.matches(Regex("[A-Z0-9][A-Z0-9._:-]{0,63}")))
        require(trailingThirtyDayNotional.isFinite() && trailingThirtyDayNotional >= 0.0)
        require(listingAgeDays >= 90)
    }
}

@Serializable
data class HyperliquidUniverseSnapshot(
    val schemaVersion: String = "marketlab.hyperliquid-social-universe.v1",
    val selectedAt: MarketTimestamp,
    val effectiveFrom: MarketTimestamp,
    val effectiveToExclusive: MarketTimestamp,
    val method: String = "trailing-30d-daily-candle-notional",
    val minimumListingAgeDays: Int = 90,
    val members: List<HyperliquidUniverseMember>,
    val sourceRevision: Sha256Digest,
) {
    init {
        require(effectiveFrom.epochMillis <= selectedAt.epochMillis)
        require(selectedAt < effectiveToExclusive)
        require(method == "trailing-30d-daily-candle-notional")
        require(minimumListingAgeDays == 90)
        require(members.size == 10)
        require(members.map(HyperliquidUniverseMember::rank) == (1..10).toList())
        require(members.map(HyperliquidUniverseMember::instrument).distinct().size == members.size)
    }
}
