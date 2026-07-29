package dev.marketlab.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoEntityResolverTest {
    @Test
    fun `explicit names and cashtags resolve while ambiguous bare tokens do not`() {
        val resolver = CryptoEntityResolver()

        assertEquals(
            listOf("hyperliquid:perpetual:BTC", "hyperliquid:perpetual:LINK"),
            resolver.resolve("Bitcoin rallied while \$LINK followed").map { it.value },
        )
        assertTrue(resolver.resolve("send me a link to that card").isEmpty())
        assertEquals(
            listOf("hyperliquid:perpetual:HYPE"),
            resolver.resolve("new activity on Hyperliquid").map { it.value },
        )
    }
}
