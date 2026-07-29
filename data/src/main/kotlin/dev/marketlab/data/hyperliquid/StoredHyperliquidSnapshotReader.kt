package dev.marketlab.data.hyperliquid

import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.data.storage.ContentAddressedDataStore
import java.time.Instant

/**
 * Rehydrates immutable Hyperliquid wire captures into the shared event
 * contracts. This is the only supported path from a frozen snapshot back into
 * an experiment; it re-verifies every content hash before parsing.
 */
class StoredHyperliquidSnapshotReader(
    private val store: ContentAddressedDataStore,
) {
    fun events(snapshot: DataSnapshot): List<MarketEvent> {
        require(snapshot.quality.usable) { "cannot read a snapshot that failed its quality gate" }
        require(snapshot.requirements.isNotEmpty())
        store.verifySnapshot(snapshot)
        val observations = snapshot.requirements.map { it.observation }.toSet()
        require(observations.size == 1) {
            "a stored Hyperliquid snapshot reader requires one observation kind"
        }
        val observation = observations.single()
        val events = snapshot.objects.flatMap { manifest ->
            store.verifyObject(manifest)
            require(manifest.provenance.source == HyperliquidEventAdapter.sourceId)
            require(manifest.provenance.production)
            val raw = store.readRaw(manifest.contentHash)
            val requestBody = manifest.provenance.request.parameters["body"].orEmpty()
            val receivedAt = Instant.ofEpochMilli(manifest.provenance.retrievedAt.epochMillis)
            when (observation) {
                ObservationKind.CANDLE -> {
                    val parsed = HyperliquidJson.parseCandles(raw)
                        .filter {
                            val exchange = it.closeTime.toEpochMilli()
                            exchange >= manifest.eventTimeRange.fromInclusive.epochMillis &&
                                exchange < manifest.eventTimeRange.toExclusive.epochMillis
                        }
                    require(parsed.size.toLong() == manifest.rowCount) {
                        "stored candle row count differs from its immutable manifest"
                    }
                    HyperliquidEventAdapter.historicalCandles(
                        CapturedResponse(requestBody, raw, receivedAt, receivedAt, parsed),
                    )
                }

                ObservationKind.FUNDING -> {
                    val parsed = HyperliquidJson.parseFunding(raw)
                        .filter {
                            val exchange = it.time.toEpochMilli()
                            exchange >= manifest.eventTimeRange.fromInclusive.epochMillis &&
                                exchange < manifest.eventTimeRange.toExclusive.epochMillis
                        }
                    require(parsed.size.toLong() == manifest.rowCount) {
                        "stored funding row count differs from its immutable manifest"
                    }
                    HyperliquidEventAdapter.historicalFunding(
                        CapturedResponse(requestBody, raw, receivedAt, receivedAt, parsed),
                    )
                }

                ObservationKind.L2_BOOK -> {
                    require(manifest.rowCount == 1L)
                    listOf(
                        HyperliquidEventAdapter.l2Book(
                            CapturedResponse(
                                requestBody,
                                raw,
                                receivedAt,
                                receivedAt,
                                HyperliquidJson.parseL2Book(raw),
                            ),
                        ),
                    )
                }

                ObservationKind.MARK_ORACLE,
                ObservationKind.OPEN_INTEREST,
                -> {
                    val parsed = HyperliquidJson.parseMetaAndAssetContexts(raw)
                    val selectedNames =
                        manifest.provenance.request.parameters[
                            HyperliquidDataIngestor.SELECTED_INSTRUMENTS_PARAMETER
                        ]?.split(',')?.toSet()
                    val selectedContexts =
                        parsed.contexts.filter { context ->
                            selectedNames == null || context.name in selectedNames
                        }
                    if (selectedNames != null) {
                        require(selectedNames.isNotEmpty())
                        require(
                            selectedContexts.size == selectedNames.size &&
                                selectedContexts.map { it.name }.toSet() == selectedNames
                        ) {
                            "stored asset-context selection is absent from the immutable raw response"
                        }
                    }
                    require(selectedContexts.size.toLong() == manifest.rowCount) {
                        "stored asset-context row count differs from its immutable manifest"
                    }
                    val all = HyperliquidEventAdapter.contexts(
                        CapturedResponse(
                            requestBody,
                            raw,
                            receivedAt,
                            receivedAt,
                            parsed.copy(
                                assets =
                                    parsed.assets.filter { asset ->
                                        selectedNames == null || asset.name in selectedNames
                                    },
                                contexts = selectedContexts,
                            ),
                        ),
                    )
                    when (observation) {
                        ObservationKind.MARK_ORACLE ->
                            all.filterIsInstance<dev.marketlab.contracts.market.MarkOracle>()
                        ObservationKind.OPEN_INTEREST ->
                            all.filterIsInstance<dev.marketlab.contracts.market.OpenInterest>()
                        ObservationKind.CANDLE,
                        ObservationKind.FUNDING,
                        ObservationKind.L2_BOOK,
                        -> error("unreachable observation branch")
                    }
                }

                else -> error("stored Hyperliquid replay does not support $observation")
            }
        }
        require(events.map { it.header.id }.distinct().size == events.size) {
            "stored snapshot contains duplicate event ids"
        }
        return events.sortedWith(
            compareBy<MarketEvent>(
                { it.header.exchangeTime },
                { it.header.availableAt },
                { it.header.id.value },
            ),
        )
    }
}
