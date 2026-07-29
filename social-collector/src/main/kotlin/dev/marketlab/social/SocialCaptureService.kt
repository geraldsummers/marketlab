package dev.marketlab.social

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SocialCaptureService(
    private val config: SocialCollectorConfig,
    private val adapters: List<InformationSourceAdapter>,
    private val metadataRefresher: HyperliquidAssetMetadataRefresher,
    private val resolver: CryptoEntityResolver,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val requiredSources =
        buildSet {
            add("bluesky-jetstream")
            add("nostr-public-relays")
            if (config.farcasterEnabled) add("farcaster-snapchain")
            add("gdelt-gkg-2.1")
            add("official-crypto-rss")
            add("hyperliquid-asset-metadata")
        }

    suspend fun run() =
        coroutineScope {
            require(adapters.map(InformationSourceAdapter::source).distinct().size == adapters.size) {
                "information adapters must have unique sources"
            }
            val queue = Channel<SourceItem>(config.queueCapacity)
            val store = SocialSegmentStore(config, resolver, clock)
            val matchedIds = LinkedHashSet<String>()
            val readySources = mutableSetOf<String>()
            var readyPublished = false
            val producers =
                adapters.map { adapter ->
                    launch(CoroutineName("social-${adapter.source}")) {
                        adapter.collect(queue::send)
                    }
                } +
                    launch(CoroutineName("social-hyperliquid-universe")) {
                        metadataRefresher.run(queue::send)
                    }
            val rotation =
                launch(CoroutineName("social-segment-rotation")) {
                    while (true) {
                        delay(config.maximumSegmentDuration.toMillis())
                        queue.send(
                            SourceItem.Quality(
                                source = "marketlab-social-collector",
                                observedAt = clock.instant(),
                                sourceUri = java.net.URI.create("https://localhost.invalid/rotation"),
                                issueKind = "ROTATION_HEARTBEAT",
                                severity = "INFO",
                                message = "periodic segment rotation heartbeat",
                            ),
                        )
                    }
                }
            try {
                while (true) {
                    val item = queue.receive()
                    if (!shouldStore(item, matchedIds)) continue
                    store.append(item)
                    if (item is SourceItem.Ready) {
                        readySources += item.source
                        if (!readyPublished && readySources.containsAll(requiredSources)) {
                            store.markReady(requiredSources, clock.instant())
                            readyPublished = true
                        }
                    }
                    store.rotateIfDue(clock.instant())
                }
            } catch (exception: CancellationException) {
                throw exception
            } finally {
                withContext(NonCancellable) {
                    rotation.cancelAndJoin()
                    producers.forEach { it.cancel() }
                    producers.joinAll()
                    queue.close()
                    while (true) {
                        val item = queue.tryReceive().getOrNull() ?: break
                        if (shouldStore(item, matchedIds)) store.append(item)
                    }
                    store.close()
                }
            }
        }

    private fun shouldStore(item: SourceItem, matchedIds: MutableSet<String>): Boolean {
        if (item !is SourceItem.Event) return true
        if (item.channel == InformationChannel.NEWS) return true
        if (item.mutation == InformationMutation.DELETE) {
            return matchedIds.remove(item.sourceEventId)
        }
        val matched = !item.text.isNullOrBlank() && resolver.resolve(item.text).isNotEmpty()
        if (matched) matchedIds += item.sourceEventId
        return matched
    }
}
