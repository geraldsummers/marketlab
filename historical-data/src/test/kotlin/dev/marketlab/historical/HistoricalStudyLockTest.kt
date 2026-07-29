package dev.marketlab.historical

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoricalStudyLockTest {
    @Test
    fun `lock drives deterministic universe and boundaries`() {
        val lock = HistoricalStudyLock.parse(validLock())
        assertEquals(listOf("BTC", "ETH"), lock.assets.map(HistoricalAsset::symbol))
        assertEquals(
            Instant.parse("2025-01-03T00:00:00Z"),
            lock.schedule.boundaries(lock.startInclusive).developmentStartInclusive,
        )
    }

    @Test
    fun `schedule must cover the registered period`() {
        assertFailsWith<IllegalArgumentException> {
            HistoricalStudyLock.parse(validLock().replace("\"holdoutDays\":1", "\"holdoutDays\":2"))
        }
    }

    private fun validLock() =
        """
        {
          "schemaVersion":"marketlab.social-backfill-program-lock.v2",
          "programId":"test",
          "period":{"startInclusive":"2025-01-01T00:00:00Z","endExclusive":"2025-01-05T00:00:00Z"},
          "sources":{
            "social":{
              "queries":{"BTC":"bitcoin","ETH":"ethereum"},
              "matchTerms":{"BTC":["bitcoin"],"ETH":["ethereum","ether"]},
              "plainSymbols":["BTC"]
            },
            "market":{"months":["2025-01"]}
          },
          "universe":{"symbols":["BTC","ETH"]},
          "schedule":{
            "featureWarmupDays":1,
            "trainingDays":1,
            "developmentDays":1,
            "holdoutDays":1,
            "unusedTailDays":0
          }
        }
        """.trimIndent()
}
