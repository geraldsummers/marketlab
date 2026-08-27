package dev.marketlab.sentiment

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
internal data class InformationFeatureRow(
    val schemaVersion: String = "marketlab.social-information-features.v2",
    val rowId: String,
    val instrument: String,
    val decisionTimeEpochMillis: Long,
    val socialAttention15m: Int,
    val socialAttention1h: Int,
    val socialAttention6h: Int,
    val socialAttention24h: Int,
    val socialHasPosts15m: Boolean,
    val socialUniqueAuthors1h: Int,
    val socialUniqueAuthors24h: Int,
    val socialSourcesWithPosts15m: Int,
    val socialSourceCount1h: Int,
    val socialMinutesSincePost: Double,
    val socialAttentionChange1h: Int,
    val socialPolarity1h: Double?,
    val socialDisagreement1h: Double?,
    val normalizedAttention30d: Double?,
    val crossSectionalAttentionRank: Double?,
    val newsCount24h: Int,
    val newsNegativity24h: Double?,
)

/**
 * Frozen causal feature compiler for the social program.
 *
 * The compiler sees only records whose model score existed by the decision
 * clock. It applies updates/deletes, exact-text deduplication within each source
 * for 24 hours, and a maximum of three messages per author/asset/hour before
 * calculating source-balanced aggregates.
 */
internal class CausalFeatureAggregator {
    fun compile(
        input: List<ScoredInformationRecord>,
        instruments: List<String>,
        decisionTimeEpochMillis: Long,
    ): List<InformationFeatureRow> {
        require(decisionTimeEpochMillis > 0L)
        require(instruments.isNotEmpty() && instruments == instruments.distinct().sorted())
        val asOf =
            latestMutations(input, decisionTimeEpochMillis)
                .filter { it.mutation != InformationMutation.DELETE && it.score != null }
        val deduplicated = deduplicate(asOf)
        val rows = instruments.map { instrument ->
            val capped = capAuthors(deduplicated.filter { instrument in it.matchedInstruments })
            featureRow(instrument, capped, decisionTimeEpochMillis)
        }
        val ordered = rows.sortedWith(compareBy(InformationFeatureRow::socialAttention24h, InformationFeatureRow::instrument))
        val rankByInstrument =
            ordered
                .groupBy(InformationFeatureRow::socialAttention24h)
                .flatMap { (_, tied) ->
                    val indices = tied.map { row -> ordered.indexOf(row) }
                    val averageIndex = indices.average()
                    tied.map { row ->
                        row.instrument to
                            if (ordered.size == 1) 0.5 else averageIndex / (ordered.size - 1)
                    }
                }.toMap()
        return rows.map { row ->
            row.copy(crossSectionalAttentionRank = rankByInstrument.getValue(row.instrument))
        }
    }

    private fun latestMutations(
        input: List<ScoredInformationRecord>,
        decision: Long,
    ): List<ScoredInformationRecord> =
        input
            .asSequence()
            .filter { effectiveAvailability(it) <= decision }
            .sortedWith(
                compareBy<ScoredInformationRecord>(
                    ::effectiveAvailability,
                    ScoredInformationRecord::source,
                    ScoredInformationRecord::sourceEventId,
                ),
            )
            .fold(linkedMapOf<String, ScoredInformationRecord>()) { current, row ->
                current["${row.source}\u001f${row.sourceEventId}"] = row
                current
            }
            .values
            .toList()

    private fun deduplicate(rows: List<ScoredInformationRecord>): List<ScoredInformationRecord> {
        val accepted = mutableListOf<ScoredInformationRecord>()
        val lastByText = mutableMapOf<String, Long>()
        rows
            .sortedWith(
                compareBy<ScoredInformationRecord>(
                    ::effectiveAvailability,
                    ScoredInformationRecord::source,
                    ScoredInformationRecord::sourceEventId,
                ),
            )
            .forEach { row ->
                val textHash = row.textSha256 ?: return@forEach
                val key = "${row.source}\u001f$textHash"
                val at = effectiveAvailability(row)
                val prior = lastByText[key]
                if (prior == null || at - prior >= DAY_MILLIS) {
                    accepted += row
                    lastByText[key] = at
                }
            }
        return accepted
    }

    private fun capAuthors(rows: List<ScoredInformationRecord>): List<ScoredInformationRecord> {
        val counts = mutableMapOf<String, Int>()
        return rows
            .sortedWith(
                compareBy<ScoredInformationRecord>(
                    ::effectiveAvailability,
                    ScoredInformationRecord::source,
                    ScoredInformationRecord::sourceEventId,
                ),
            )
            .filter { row ->
                val author = row.authorIdHash ?: "event:${row.sourceEventId}"
                val hour = effectiveAvailability(row) / HOUR_MILLIS
                val key = "${row.source}\u001f$author\u001f$hour"
                val next = (counts[key] ?: 0) + 1
                counts[key] = next
                next <= MAXIMUM_AUTHOR_MESSAGES_PER_HOUR
            }
    }

    private fun featureRow(
        instrument: String,
        rows: List<ScoredInformationRecord>,
        decision: Long,
    ): InformationFeatureRow {
        val socialQuarterHour =
            rows.filter {
                it.channel == InformationChannel.SOCIAL &&
                    effectiveAvailability(it) > decision - QUARTER_HOUR_MILLIS
            }
        val socialHour =
            rows.filter {
                it.channel == InformationChannel.SOCIAL &&
                    effectiveAvailability(it) > decision - HOUR_MILLIS
            }
        val socialPriorHour =
            rows.filter {
                it.channel == InformationChannel.SOCIAL &&
                    effectiveAvailability(it) > decision - 2L * HOUR_MILLIS &&
                    effectiveAvailability(it) <= decision - HOUR_MILLIS
            }
        val socialSixHours =
            rows.filter {
                it.channel == InformationChannel.SOCIAL &&
                    effectiveAvailability(it) > decision - 6L * HOUR_MILLIS
            }
        val socialDay =
            rows.filter {
                it.channel == InformationChannel.SOCIAL &&
                    effectiveAvailability(it) > decision - DAY_MILLIS
            }
        val newsDay =
            rows.filter {
                it.channel == InformationChannel.NEWS &&
                    effectiveAvailability(it) > decision - DAY_MILLIS
            }
        val socialPolarity =
            sourceBalancedMean(socialHour) { checkNotNull(it.score).polarity }
        val disagreement =
            sourceBalancedMean(
                socialHour.groupBy(ScoredInformationRecord::source).mapNotNull { (_, sourceRows) ->
                    if (sourceRows.size < 2) {
                        null
                    } else {
                        val values = sourceRows.map { checkNotNull(it.score).polarity }
                        variance(values)
                    }
                },
            )
        val negativity =
            sourceBalancedMean(newsDay) { checkNotNull(it.score).negativeProbability }
        val normalized =
            normalizedAttention(
                rows.filter {
                    it.channel == InformationChannel.SOCIAL &&
                        effectiveAvailability(it) > decision - THIRTY_DAYS_MILLIS
                },
                decision,
            )
        return InformationFeatureRow(
            rowId = sha256("$instrument\u001f$decision"),
            instrument = instrument,
            decisionTimeEpochMillis = decision,
            socialAttention15m = socialQuarterHour.size,
            socialAttention1h = socialHour.size,
            socialAttention6h = socialSixHours.size,
            socialAttention24h = socialDay.size,
            socialHasPosts15m = socialQuarterHour.isNotEmpty(),
            socialUniqueAuthors1h = socialHour.map(::authorIdentity).distinct().size,
            socialUniqueAuthors24h = socialDay.map(::authorIdentity).distinct().size,
            socialSourcesWithPosts15m = socialQuarterHour.map(ScoredInformationRecord::source).distinct().size,
            socialSourceCount1h = socialHour.map(ScoredInformationRecord::source).distinct().size,
            socialMinutesSincePost =
                rows.filter { it.channel == InformationChannel.SOCIAL }
                    .maxOfOrNull(::effectiveAvailability)
                    ?.let { (decision - it) / 60_000.0 }
                    ?.coerceIn(0.0, 43_200.0)
                    ?: 43_200.0,
            socialAttentionChange1h = socialHour.size - socialPriorHour.size,
            socialPolarity1h = socialPolarity,
            socialDisagreement1h = disagreement,
            normalizedAttention30d = normalized,
            crossSectionalAttentionRank = null,
            newsCount24h = newsDay.size,
            newsNegativity24h = negativity,
        )
    }

    private fun normalizedAttention(
        rows: List<ScoredInformationRecord>,
        decision: Long,
    ): Double? {
        val sourceScores =
            rows.groupBy(ScoredInformationRecord::source).mapNotNull { (_, sourceRows) ->
                val counts =
                    sourceRows
                        .groupingBy { effectiveAvailability(it) / HOUR_MILLIS }
                        .eachCount()
                val decisionHour = decision / HOUR_MILLIS
                val current = counts[decisionHour - 1]?.toDouble() ?: 0.0
                val history =
                    ((decisionHour - THIRTY_DAYS_HOURS) until (decisionHour - 1))
                        .map { counts[it]?.toDouble() ?: 0.0 }
                val standardDeviation = sqrt(variance(history))
                if (standardDeviation == 0.0) null else (current - history.average()) / standardDeviation
            }
        return sourceScores.takeIf { it.size >= MINIMUM_SOURCES_FOR_COMPOSITE }?.average()
    }

    private fun sourceBalancedMean(
        rows: List<ScoredInformationRecord>,
        value: (ScoredInformationRecord) -> Double,
    ): Double? {
        val sourceMeans =
            rows.groupBy(ScoredInformationRecord::source)
                .values
                .map { sourceRows -> sourceRows.map(value).average() }
        return sourceMeans.takeIf(List<Double>::isNotEmpty)?.average()
    }

    private fun sourceBalancedMean(values: List<Double>): Double? =
        values.takeIf(List<Double>::isNotEmpty)?.average()

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { value -> (value - mean) * (value - mean) } / (values.size - 1)
    }

    private fun effectiveAvailability(row: ScoredInformationRecord): Long =
        maxOf(row.availableAtEpochMillis, row.score?.scoredAt?.epochMillis ?: row.availableAtEpochMillis)

    private fun authorIdentity(row: ScoredInformationRecord): String =
        row.authorIdHash ?: "${row.source}:${row.sourceEventId}"

    private companion object {
        const val MAXIMUM_AUTHOR_MESSAGES_PER_HOUR = 3
        const val MINIMUM_SOURCES_FOR_COMPOSITE = 2
        const val HOUR_MILLIS = 60L * 60L * 1_000L
        const val QUARTER_HOUR_MILLIS = 15L * 60L * 1_000L
        const val DAY_MILLIS = 24L * HOUR_MILLIS
        const val THIRTY_DAYS_HOURS = 30L * 24L
        const val THIRTY_DAYS_MILLIS = 30L * DAY_MILLIS

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
