package dev.marketlab.data.hyperliquid

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class WeightedRateLimiterTest {
    @Test
    fun `waits until the oldest charge leaves the sliding window`() = runBlocking {
        var nowNanos = 0L
        val sleeps = mutableListOf<Long>()
        val limiter = WeightedRateLimiter(
            capacity = 3,
            window = Duration.ofMillis(100),
            nanoTime = { nowNanos },
            sleepMillis = { millis ->
                sleeps += millis
                nowNanos += millis * 1_000_000L
            },
        )

        limiter.acquire(2)
        limiter.acquire(1)
        limiter.acquire(2)

        assertEquals(listOf(100L), sleeps)
    }
}
