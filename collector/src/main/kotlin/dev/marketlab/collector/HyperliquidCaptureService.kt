package dev.marketlab.collector

import dev.marketlab.data.hyperliquid.HyperliquidStreamItem
import dev.marketlab.data.hyperliquid.HyperliquidStreamSubscription
import dev.marketlab.data.hyperliquid.HyperliquidWebSocketCollector
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HyperliquidCaptureService(
    private val config: CollectorConfig,
    private val collector: HyperliquidWebSocketCollector,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val subscriptions =
        listOf(
            HyperliquidStreamSubscription.Trades(config.coin),
            HyperliquidStreamSubscription.Bbo(config.coin),
            HyperliquidStreamSubscription.L2Book(config.coin),
        )

    suspend fun run() =
        coroutineScope {
            val queue = Channel<CaptureCommand>(config.queueCapacity)
            val store = StreamSegmentStore(config, subscriptions, clock)
            val acknowledgedSubscriptions = mutableSetOf<String>()
            var readyPublished = false
            val producers =
                subscriptions.map { subscription ->
                    launch(CoroutineName("hyperliquid-${subscription.channel}-${config.coin}")) {
                        var connectionOrdinal = 0L
                        collector.stream(subscription).collect { item ->
                            val ordinal =
                                if (item is HyperliquidStreamItem.Connected) {
                                    connectionOrdinal = Math.addExact(connectionOrdinal, 1L)
                                    connectionOrdinal
                                } else {
                                    null
                                }
                            queue.send(
                                CaptureCommand.Record(
                                    StreamEnvelope(
                                        subscription = subscription,
                                        item = item,
                                        observedAt = clock.instant(),
                                        connectionOrdinal = ordinal,
                                    ),
                                ),
                            )
                        }
                        throw IllegalStateException(
                            "Hyperliquid ${subscription.channel} stream ended unexpectedly",
                        )
                    }
                }
            val rotation =
                launch(CoroutineName("hyperliquid-segment-rotation")) {
                    while (true) {
                        delay(config.maximumSegmentDuration.toMillis())
                        queue.send(CaptureCommand.Rotate(clock.instant()))
                    }
                }
            try {
                while (true) {
                    when (val command = queue.receive()) {
                        is CaptureCommand.Record -> {
                            store.append(command.envelope)
                            if (!readyPublished &&
                                command.envelope.item is
                                    HyperliquidStreamItem.SubscriptionAcknowledged
                            ) {
                                acknowledgedSubscriptions.add(
                                    command.envelope.subscription.channel,
                                )
                                if (acknowledgedSubscriptions.size == subscriptions.size) {
                                    store.markReady(acknowledgedSubscriptions, clock.instant())
                                    readyPublished = true
                                }
                            }
                        }
                        is CaptureCommand.Rotate -> store.rotateIfDue(command.at)
                    }
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
                        val command = queue.tryReceive().getOrNull() ?: break
                        when (command) {
                            is CaptureCommand.Record -> store.append(command.envelope)
                            is CaptureCommand.Rotate -> store.rotateIfDue(command.at)
                        }
                    }
                    store.close()
                }
            }
        }

    private sealed interface CaptureCommand {
        data class Record(val envelope: StreamEnvelope) : CaptureCommand

        data class Rotate(val at: java.time.Instant) : CaptureCommand
    }
}
