package dev.marketlab.sentiment

import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import dev.marketlab.contracts.data.SentimentModelIdentity
import dev.marketlab.contracts.data.SentimentScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CausalFeatureAggregatorTest {
    @Test
    fun `compiler is causal deduplicated author capped and source balanced`() {
        val decision = 40L * DAY
        val instrument = "hyperliquid:perpetual:BTC"
        val rows =
            buildList {
                repeat(5) { index ->
                    add(
                        row(
                            id = "author-a-$index",
                            source = "bluesky",
                            author = "author-a",
                            at = decision - 10_000L + index,
                            textHash = digest("text-$index"),
                            positive = 0.9,
                            negative = 0.05,
                            instrument = instrument,
                        ),
                    )
                }
                add(
                    row(
                        id = "duplicate",
                        source = "bluesky",
                        author = "author-b",
                        at = decision - 5_000L,
                        textHash = digest("text-0"),
                        positive = 0.1,
                        negative = 0.8,
                        instrument = instrument,
                    ),
                )
                add(
                    row(
                        id = "nostr-one",
                        source = "nostr",
                        author = "author-c",
                        at = decision - 4_000L,
                        textHash = digest("nostr"),
                        positive = 0.1,
                        negative = 0.8,
                        instrument = instrument,
                    ),
                )
                add(
                    row(
                        id = "future",
                        source = "nostr",
                        author = "future",
                        at = decision + 1L,
                        textHash = digest("future"),
                        positive = 1.0,
                        negative = 0.0,
                        instrument = instrument,
                    ),
                )
            }

        val feature =
            CausalFeatureAggregator()
                .compile(rows, listOf(instrument), decision)
                .single()

        assertEquals(4, feature.socialAttention1h)
        assertEquals(2, feature.socialSourceCount1h)
        assertEquals(0.075, checkNotNull(feature.socialPolarity1h), 1.0e-12)
        assertEquals(0.0, checkNotNull(feature.socialDisagreement1h))
        assertEquals(0.5, feature.crossSectionalAttentionRank)
    }

    @Test
    fun `delete removes an earlier scored message`() {
        val instrument = "hyperliquid:perpetual:BTC"
        val created = row("same", "nostr", "author", 1_000L, digest("text"), 0.8, 0.1, instrument)
        val deleted =
            created.copy(
                mutation = InformationMutation.DELETE,
                receivedAtEpochMillis = 2_000L,
                availableAtEpochMillis = 2_000L,
                textSha256 = null,
                score = null,
                matchedInstruments = emptyList(),
            )

        val feature =
            CausalFeatureAggregator()
                .compile(listOf(created, deleted), listOf(instrument), 3_000L)
                .single()

        assertEquals(0, feature.socialAttention1h)
        assertNull(feature.socialPolarity1h)
    }

    @Test
    fun `frozen language detector rejects explicit non English tags`() {
        val detector =
            FrozenLanguageDetector(
                LockedLanguageDetector(
                    artifact = "com.github.pemistahl:lingua:1.2.2",
                    languages = listOf("ENGLISH", "GERMAN", "SPANISH"),
                    minimumRelativeDistance = 0.10,
                ),
            )
        assertTrue(detector.acceptsEnglish("en-US", "Bitcoin ist wunderbar"))
        assertTrue(!detector.acceptsEnglish("de", "Bitcoin is wonderful"))
        assertTrue(detector.acceptsEnglish(null, "Bitcoin markets are rising strongly today."))
    }

    private fun row(
        id: String,
        source: String,
        author: String,
        at: Long,
        textHash: String,
        positive: Double,
        negative: Double,
        instrument: String,
    ): ScoredInformationRecord {
        val neutral = 1.0 - positive - negative
        return ScoredInformationRecord(
            source = source,
            sourceEventId = id,
            channel = InformationChannel.SOCIAL,
            mutation = InformationMutation.CREATE,
            eventTimeEpochMillis = at,
            receivedAtEpochMillis = at,
            availableAtEpochMillis = at,
            authorIdHash = author,
            language = "en",
            textSha256 = textHash,
            matchedInstruments = listOf(instrument),
            sourceMetrics = emptyMap(),
            score =
                SentimentScore(
                    sourceEventId = id,
                    model =
                        SentimentModelIdentity(
                            key = "test",
                            upstreamRevision = "a".repeat(40),
                            modelSha256 = Sha256Digest("b".repeat(64)),
                            tokenizerSha256 = Sha256Digest("c".repeat(64)),
                            preprocessingSha256 = Sha256Digest("d".repeat(64)),
                        ),
                    scoredAt = MarketTimestamp(at),
                    negativeProbability = negative,
                    neutralProbability = neutral,
                    positiveProbability = positive,
                ),
        )
    }

    private fun digest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
    }
}
