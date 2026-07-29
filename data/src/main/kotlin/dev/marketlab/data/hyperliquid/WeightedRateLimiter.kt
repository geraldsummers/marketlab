package dev.marketlab.data.hyperliquid

import java.time.Duration
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface RateLimitGate {
    suspend fun acquire(weight: Int)
}

/**
 * A coroutine-safe sliding-window limiter. Hyperliquid currently assigns a weight
 * to each REST request and applies one aggregate IP budget.
 */
internal class WeightedRateLimiter(
    private val capacity: Int = 1_200,
    window: Duration = Duration.ofMinutes(1),
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
) : RateLimitGate {
    private data class Charge(val timeNanos: Long, val weight: Int)

    private val windowNanos = window.toNanos()
    private val mutex = Mutex()
    private val charges = ArrayDeque<Charge>()
    private var chargedWeight = 0

    init {
        require(capacity > 0) { "rate-limit capacity must be positive" }
        require(!window.isZero && !window.isNegative) { "rate-limit window must be positive" }
    }

    override suspend fun acquire(weight: Int) {
        require(weight in 1..capacity) { "request weight must be within 1..$capacity" }
        while (true) {
            val waitNanos = mutex.withLock {
                val now = nanoTime()
                evictExpired(now)
                if (chargedWeight + weight <= capacity) {
                    charges.addLast(Charge(now, weight))
                    chargedWeight += weight
                    0L
                } else {
                    val oldest = checkNotNull(charges.firstOrNull())
                    (oldest.timeNanos + windowNanos - now).coerceAtLeast(1L)
                }
            }
            if (waitNanos == 0L) return
            val waitMillis = ((waitNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
                .coerceAtLeast(1L)
            sleepMillis(waitMillis)
        }
    }

    private fun evictExpired(now: Long) {
        while (charges.isNotEmpty()) {
            val oldest = charges.first()
            if (now - oldest.timeNanos < windowNanos) return
            chargedWeight -= charges.removeFirst().weight
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
