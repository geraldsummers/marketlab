package dev.marketlab.collector

import io.ktor.client.HttpClient
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HyperliquidTopUniverseSelectorTest {
    private val selector =
        HyperliquidTopUniverseSelector(
            client = HttpClient(),
            universeRoot = Path.of("/tmp/marketlab-universe-test"),
            sourceRevision = "a".repeat(64),
        )

    @Test
    fun `meta parser returns only valid uppercase unique symbols`() {
        val symbols =
            selector.parseSymbols(
                """{"universe":[{"name":"BTC"},{"name":"ETH"},{"name":"btc"},{"name":"BTC"}]}""",
            )
        assertEquals(listOf("BTC", "ETH"), symbols)
    }

    @Test
    fun `daily candles produce exact frozen notional and listing age gate`() {
        val selectedAt = Instant.parse("2026-07-28T12:00:00Z")
        val start = selectedAt.minusSeconds(92L * 86_400L).toEpochMilli()
        val candles =
            (0 until 92).joinToString(prefix = "[", postfix = "]") { day ->
                val open = start + day * 86_400_000L
                """{"t":$open,"T":${open + 86_399_999L},"o":"10","h":"14","l":"8","c":"12","v":"2","n":1}"""
            }

        val result = selector.parseObservation("BTC", candles, selectedAt)

        assertEquals("BTC", result?.symbol)
        assertEquals(92, result?.listingAgeDays)
        assertEquals(660.0, checkNotNull(result).notional, 1.0e-9)
        val shortHistory =
            (0 until 10).joinToString(prefix = "[", postfix = "]") { day ->
                val open = selectedAt.minusSeconds((10L - day) * 86_400L).toEpochMilli()
                """{"t":$open,"T":${open + 86_399_999L},"o":"10","h":"14","l":"8","c":"12","v":"2"}"""
            }
        assertNull(
            selector.parseObservation(
                "NEW",
                shortHistory,
                selectedAt,
            ),
        )
    }
}
