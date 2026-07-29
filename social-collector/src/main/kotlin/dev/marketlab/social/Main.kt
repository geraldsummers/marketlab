package dev.marketlab.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main() {
    val config = SocialCollectorConfig.fromEnvironment()
    val clock = Clock.systemUTC()
    val rootJob = Job()
    val shuttingDown = AtomicBoolean(false)
    val finished = CountDownLatch(1)
    val hook =
        Thread(
            {
                shuttingDown.set(true)
                rootJob.cancel(CancellationException("JVM shutdown requested"))
                if (!finished.await(50L, TimeUnit.SECONDS)) {
                    LOGGER.error("Timed out waiting for social segment finalization")
                }
            },
            "marketlab-social-shutdown",
        )
    Runtime.getRuntime().addShutdownHook(hook)
    val client =
        HttpClient(CIO) {
            install(WebSockets) {
                maxFrameSize = config.maximumFrameBytes
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000L
                requestTimeoutMillis = 120_000L
                socketTimeoutMillis = 120_000L
            }
            install(UserAgent) {
                agent = "MarketlabResearch/1.0 (+https://localhost.invalid/marketlab)"
            }
            expectSuccess = true
        }
    try {
        val resolver = CryptoEntityResolver()
        val adapters =
            buildList {
                add(
                BlueskyJetstreamAdapter(client, config.blueskyUris, clock),
                )
                add(
                NostrRelayAdapter(client, config.nostrUris, clock),
                )
                if (config.farcasterEnabled) {
                    add(
                        FarcasterSnapchainAdapter(
                            client,
                            config.farcasterEventsUri,
                            config.sourcePollInterval.toMillis(),
                            clock,
                        ),
                    )
                }
                add(
                GdeltGkgAdapter(
                    client,
                    config.gdeltLastUpdateUri,
                    config.sourcePollInterval.toMillis(),
                    clock,
                ),
                )
                add(
                RssNewsAdapter(
                    client,
                    config.rssUris,
                    config.sourcePollInterval.toMillis(),
                    clock,
                ),
                )
                add(
                MastodonHashtagAdapter(
                    client,
                    config.mastodonUris,
                    config.sourcePollInterval.toMillis(),
                    clock,
                ),
                )
            }
        val metadata =
            HyperliquidAssetMetadataRefresher(
                client,
                resolver,
                config.universeRefreshInterval.toMillis(),
                clock,
            )
        LOGGER.info(
            "Starting credential-free public-information capture at {} (revision {})",
            config.rawRoot,
            config.sourceRevision,
        )
        runBlocking(rootJob) {
            SocialCaptureService(config, adapters, metadata, resolver, clock).run()
        }
    } catch (exception: CancellationException) {
        if (!shuttingDown.get()) throw exception
        LOGGER.info("Social collector stopped after graceful segment finalization")
    } finally {
        client.close()
        finished.countDown()
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // JVM shutdown is already running.
        }
    }
}

private val LOGGER = LoggerFactory.getLogger("dev.marketlab.social.Main")
