package dev.marketlab.collector

import dev.marketlab.data.hyperliquid.HyperliquidWebSocketCollector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main() {
    val config = CollectorConfig.fromEnvironment()
    val clock = Clock.systemUTC()
    val rootJob = Job()
    val shuttingDown = AtomicBoolean(false)
    val finished = CountDownLatch(1)
    val shutdownHook =
        Thread(
            {
                shuttingDown.set(true)
                rootJob.cancel(CancellationException("JVM shutdown requested"))
                if (!finished.await(50L, TimeUnit.SECONDS)) {
                    LOGGER.error("Timed out waiting for capture segment finalization")
                }
            },
            "marketlab-collector-shutdown",
        )
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        if (config.universeMode == UniverseMode.STATIC) {
            LOGGER.info(
                "Starting production Hyperliquid capture for {} at {} (revision {})",
                config.coin,
                config.rawRoot,
                config.sourceRevision,
            )
            HyperliquidWebSocketCollector.mainnet(
                clock = clock,
                maximumFrameBytes = config.maximumFrameBytes,
            ).use { collector ->
                runBlocking(rootJob) {
                    HyperliquidCaptureService(config, collector, clock).run()
                }
            }
        } else {
            LOGGER.info(
                "Starting dynamic Hyperliquid top-ten capture at {} (revision {})",
                config.rawRoot,
                config.sourceRevision,
            )
            HttpClient(CIO).use { client ->
                val selector =
                    HyperliquidTopUniverseSelector(
                        client = client,
                        universeRoot = checkNotNull(config.universeRoot),
                        sourceRevision = config.sourceRevision,
                        clock = clock,
                    )
                runBlocking(rootJob) {
                    runDynamicUniverse(config, selector, clock)
                }
            }
        }
    } catch (exception: CancellationException) {
        if (!shuttingDown.get()) throw exception
        LOGGER.info("Hyperliquid capture stopped after graceful segment finalization")
    } finally {
        finished.countDown()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // The JVM is already running shutdown hooks.
        }
    }
}

private suspend fun runDynamicUniverse(
    config: CollectorConfig,
    selector: HyperliquidTopUniverseSelector,
    clock: Clock,
): Nothing =
    coroutineScope {
        val captures = mutableMapOf<String, Job>()
        while (true) {
            val snapshot =
                try {
                    selector.selectAndPublish()
                } catch (exception: Exception) {
                    LOGGER.error("Hyperliquid universe selection failed; retrying", exception)
                    delay(Duration.ofMinutes(15).toMillis())
                    continue
                }
            val selected = snapshot.members.map { it.symbol }.toSet()
            captures.keys.filter { it !in selected }.forEach { symbol ->
                LOGGER.info("Stopping dynamic capture for {}", symbol)
                captures.remove(symbol)?.cancelAndJoin()
            }
            selected.sorted().forEach { symbol ->
                if (symbol !in captures) {
                    LOGGER.info("Starting dynamic capture for {}", symbol)
                    captures[symbol] =
                        launch {
                            HyperliquidWebSocketCollector.mainnet(
                                clock = clock,
                                maximumFrameBytes = config.maximumFrameBytes,
                            ).use { collector ->
                                HyperliquidCaptureService(
                                    config.copy(
                                        rawRoot = config.rawRoot.resolve(symbol),
                                        coin = symbol,
                                        universeMode = UniverseMode.STATIC,
                                        universeRoot = null,
                                    ),
                                    collector,
                                    clock,
                                ).run()
                            }
                        }
                }
            }
            val waitMillis =
                (snapshot.effectiveToExclusive.epochMillis - clock.instant().toEpochMilli())
                    .coerceAtLeast(Duration.ofMinutes(1).toMillis())
            delay(waitMillis)
        }
        @Suppress("UNREACHABLE_CODE")
        error("dynamic universe supervisor ended")
    }

private val LOGGER = LoggerFactory.getLogger("dev.marketlab.collector.Main")
