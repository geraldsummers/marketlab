package dev.marketlab.data.hyperliquid

import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.data.DataObjectManifest
import dev.marketlab.contracts.data.DataQualityIssue
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.contracts.data.SourceRequest
import dev.marketlab.contracts.market.Candle
import dev.marketlab.contracts.market.Funding
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.contracts.market.InstrumentRef
import dev.marketlab.contracts.market.L2Book
import dev.marketlab.contracts.market.MarkOracle
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.contracts.market.OpenInterest
import dev.marketlab.data.quality.HyperliquidQuality
import dev.marketlab.data.storage.ContentAddressedDataStore
import dev.marketlab.data.storage.RawObjectDescriptor
import java.io.Closeable
import java.time.Clock
import java.time.Instant

data class IngestionResult<T : MarketEvent>(
    val snapshot: DataSnapshot,
    val observations: List<T>,
)

data class HyperliquidInstrumentDescriptor(
    val exchangeIndex: Int,
    val instrument: InstrumentRef,
    val sizeDecimals: Int,
    val maxLeverage: Int,
    val marginTableId: Int,
    val delisted: Boolean,
    val isolatedOnly: Boolean,
)

data class HyperliquidInstrumentUniverse(
    val manifest: DataObjectManifest,
    val instruments: List<HyperliquidInstrumentDescriptor>,
)

/**
 * Public, production-only facade. Raw wire models remain module-private; callers
 * receive the shared contracts and an immutable snapshot that proves provenance.
 */
class HyperliquidDataIngestor private constructor(
    private val store: ContentAddressedDataStore,
    private val rest: HyperliquidRestClient,
    private val clock: Clock,
) : Closeable {
    suspend fun ingestInstrumentUniverse(): HyperliquidInstrumentUniverse {
        val capture = rest.metaAndAssetContexts()
        require(capture.value.assets.isNotEmpty()) { "Hyperliquid returned an empty instrument universe" }
        val range = instantRange(capture.receivedAt)
        val manifest = storeCapture(
            capture = capture,
            rows = capture.value.assets,
            eventRange = range,
            availabilityRange = range,
        )
        return HyperliquidInstrumentUniverse(
            manifest = manifest,
            instruments = capture.value.assets.map { asset ->
                HyperliquidInstrumentDescriptor(
                    exchangeIndex = asset.index,
                    instrument = InstrumentRef(
                        id = InstrumentId("hyperliquid:perpetual:${asset.name}"),
                        venue = HyperliquidEventAdapter.sourceId,
                        symbol = asset.name,
                        baseAsset = asset.name,
                        quoteAsset = "USDC",
                        kind = InstrumentKind.PERPETUAL,
                    ),
                    sizeDecimals = asset.sizeDecimals,
                    maxLeverage = asset.maxLeverage,
                    marginTableId = asset.marginTableId,
                    delisted = asset.isDelisted,
                    isolatedOnly = asset.onlyIsolated,
                )
            },
        )
    }

    suspend fun ingestCandles(
        coin: String,
        interval: String,
        startInclusive: Instant,
        endExclusive: Instant,
        requirement: DataRequirement,
    ): IngestionResult<Candle> {
        validateRequirement(requirement, ObservationKind.CANDLE)
        val captures = rest.candles(coin, interval, startInclusive, endExclusive)
        val rows = captures.flatMap { it.value }
        val removed = captures.sumOf { capture ->
            HyperliquidJson.parseCandles(capture.rawBody).size - capture.value.size
        }
        val checkedAt = clock.instant()
        val events = captures.flatMap(HyperliquidEventAdapter::historicalCandles)
        val coverageReport = enforceMinimumCoverage(
            report = HyperliquidQuality.candles(rows, checkedAt, coin, interval, removed),
            coverageMillis = if (rows.isEmpty()) {
                0L
            } else {
                Math.addExact(
                    rows.maxOf { it.closeTime.toEpochMilli() } -
                        rows.minOf { it.openTime.toEpochMilli() },
                    1L,
                )
            },
            requirement = requirement,
        )
        val report = enforceMaximumAvailabilityLag(coverageReport, events, requirement, checkedAt)
        HyperliquidQuality.requireUsable(report)
        val manifests = captures
            .filter { it.value.isNotEmpty() }
            .map { capture ->
                storeCapture(
                    capture = capture,
                    rows = capture.value,
                    eventRange = eventRange(capture.value),
                    availabilityRange = candleAvailabilityRange(capture.value),
                )
            }
        return IngestionResult(
            snapshot = store.createSnapshot(checkedAt, listOf(requirement), manifests, report),
            observations = events,
        )
    }

    suspend fun ingestFunding(
        coin: String,
        startInclusive: Instant,
        endExclusive: Instant,
        requirement: DataRequirement,
    ): IngestionResult<Funding> {
        validateRequirement(requirement, ObservationKind.FUNDING)
        val captures = rest.fundingHistory(coin, startInclusive, endExclusive)
        val rows = captures.flatMap { it.value }
        val removed = captures.sumOf { capture ->
            HyperliquidJson.parseFunding(capture.rawBody).size - capture.value.size
        }
        val checkedAt = clock.instant()
        val events = captures.flatMap(HyperliquidEventAdapter::historicalFunding)
        val coverageReport = enforceMinimumCoverage(
            report = HyperliquidQuality.funding(rows, checkedAt, coin, removed),
            coverageMillis = if (rows.isEmpty()) {
                0L
            } else {
                Math.addExact(
                    rows.maxOf { it.time.toEpochMilli() } - rows.minOf { it.time.toEpochMilli() },
                    FUNDING_INTERVAL_MILLIS,
                )
            },
            requirement = requirement,
        )
        val report = enforceMaximumAvailabilityLag(coverageReport, events, requirement, checkedAt)
        HyperliquidQuality.requireUsable(report)
        val manifests = captures
            .filter { it.value.isNotEmpty() }
            .map { capture ->
                val range = fundingRange(capture.value)
                storeCapture(capture, capture.value, range, range)
            }
        return IngestionResult(
            snapshot = store.createSnapshot(checkedAt, listOf(requirement), manifests, report),
            observations = events,
        )
    }

    suspend fun ingestL2Book(
        coin: String,
        requirement: DataRequirement,
        significantFigures: Int? = null,
        mantissa: Int? = null,
    ): IngestionResult<L2Book> {
        validateRequirement(requirement, ObservationKind.L2_BOOK)
        val capture = rest.l2Book(coin, significantFigures, mantissa)
        val checkedAt = clock.instant()
        val event = HyperliquidEventAdapter.l2Book(capture)
        val coverageReport = enforceMinimumCoverage(
            HyperliquidQuality.l2Book(capture.value, checkedAt),
            SINGLE_OBSERVATION_COVERAGE_MILLIS,
            requirement,
        )
        val report =
            enforceMaximumAvailabilityLag(coverageReport, listOf(event), requirement, checkedAt)
        HyperliquidQuality.requireUsable(report)
        val range = instantRange(capture.value.time)
        val manifest = storeCapture(
            capture = capture,
            rows = listOf(capture.value),
            eventRange = range,
            availabilityRange = instantRange(capture.receivedAt),
        )
        return IngestionResult(
            snapshot = store.createSnapshot(checkedAt, listOf(requirement), listOf(manifest), report),
            observations = listOf(event),
        )
    }

    suspend fun ingestAssetContexts(
        requirement: DataRequirement,
        instruments: Set<String>? = null,
    ): IngestionResult<MarketEvent> {
        require(requirement.observation in setOf(ObservationKind.MARK_ORACLE, ObservationKind.OPEN_INTEREST)) {
            "asset contexts satisfy MARK_ORACLE or OPEN_INTEREST requirements"
        }
        require(instruments == null || instruments.isNotEmpty()) {
            "an explicit asset-context selection cannot be empty"
        }
        require(instruments == null || instruments.all(INSTRUMENT_SYMBOL::matches)) {
            "asset-context selection contains an invalid instrument"
        }
        validateSource(requirement)
        val capture = rest.metaAndAssetContexts()
        val checkedAt = clock.instant()
        val selectedContexts =
            capture.value.contexts.filter { context ->
                instruments == null || context.name in instruments
            }
        val selectedAssets =
            capture.value.assets.filter { asset ->
                instruments == null || asset.name in instruments
            }
        if (instruments != null) {
            require(
                selectedContexts.size == instruments.size &&
                    selectedContexts.map { it.name }.toSet() == instruments
            ) {
                "Hyperliquid asset contexts do not contain the complete requested instrument set"
            }
        }
        val selectedCapture =
            capture.copy(
                value =
                    MetaAndAssetContexts(
                        assets = selectedAssets,
                        contexts = selectedContexts,
                    ),
            )
        val allEvents = HyperliquidEventAdapter.contexts(selectedCapture)
        val requestedEvents = when (requirement.observation) {
            ObservationKind.MARK_ORACLE -> allEvents.filterIsInstance<MarkOracle>()
            ObservationKind.OPEN_INTEREST -> allEvents.filterIsInstance<OpenInterest>()
            else -> error("validated above")
        }
        val issues = if (requestedEvents.isEmpty()) {
            listOf(
                DataQualityIssue(
                    kind = QualityIssueKind.MISSING_METADATA,
                    severity = QualitySeverity.FATAL,
                    message = "Hyperliquid asset contexts contained no requested observations",
                ),
            )
        } else {
            emptyList()
        }
        val coverageReport = enforceMinimumCoverage(
            DataQualityReport(MarketTimestamp(checkedAt.toEpochMilli()), issues),
            SINGLE_OBSERVATION_COVERAGE_MILLIS,
            requirement,
        )
        val report =
            enforceMaximumAvailabilityLag(
                coverageReport,
                requestedEvents,
                requirement,
                checkedAt,
            )
        HyperliquidQuality.requireUsable(report)
        val range = instantRange(capture.receivedAt)
        val manifest =
            storeCapture(
                capture = capture,
                rows = selectedContexts,
                eventRange = range,
                availabilityRange = range,
                provenanceParameters =
                    instruments
                        ?.toSortedSet()
                        ?.joinToString(",")
                        ?.let { mapOf(SELECTED_INSTRUMENTS_PARAMETER to it) }
                        ?: emptyMap(),
            )
        return IngestionResult(
            snapshot = store.createSnapshot(checkedAt, listOf(requirement), listOf(manifest), report),
            observations = requestedEvents,
        )
    }

    override fun close() {
        rest.close()
    }

    private fun validateRequirement(requirement: DataRequirement, expected: ObservationKind) {
        require(requirement.observation == expected) {
            "expected a $expected requirement, got ${requirement.observation}"
        }
        validateSource(requirement)
    }

    private fun validateSource(requirement: DataRequirement) {
        require(HyperliquidEventAdapter.sourceId in requirement.sourcePreference) {
            "requirement does not permit Hyperliquid mainnet"
        }
    }

    private fun <T> storeCapture(
        capture: CapturedResponse<T>,
        rows: Collection<*>,
        eventRange: TimeRange,
        availabilityRange: TimeRange,
        provenanceParameters: Map<String, String> = emptyMap(),
    ): DataObjectManifest {
        require(rows.isNotEmpty()) { "cannot store an empty API capture" }
        require("body" !in provenanceParameters) {
            "additional provenance parameters cannot replace the captured request body"
        }
        return store.putRaw(
            bytes = capture.rawBody,
            descriptor = RawObjectDescriptor(
                source = HyperliquidEventAdapter.sourceId,
                request = SourceRequest(
                    method = "POST",
                    uri = HyperliquidRestClient.MAINNET_INFO_URL,
                    parameters = mapOf("body" to capture.requestBody) + provenanceParameters,
                ),
                retrievedAt = capture.receivedAt,
                schemaVersion = WIRE_SCHEMA_VERSION,
                adapterVersion = HyperliquidRestClient.ADAPTER_VERSION,
                rowCount = rows.size.toLong(),
                eventTimeRange = eventRange,
                availabilityTimeRange = availabilityRange,
                production = true,
            ),
        )
    }

    private fun eventRange(rows: List<HyperliquidCandle>): TimeRange =
        TimeRange(
            fromInclusive = MarketTimestamp(rows.minOf { it.openTime.toEpochMilli() }),
            toExclusive = MarketTimestamp(
                Math.addExact(rows.maxOf { it.closeTime.toEpochMilli() }, 1L),
            ),
        )

    private fun fundingRange(rows: List<HyperliquidFunding>): TimeRange =
        TimeRange(
            fromInclusive = MarketTimestamp(rows.minOf { it.time.toEpochMilli() }),
            toExclusive = MarketTimestamp(Math.addExact(rows.maxOf { it.time.toEpochMilli() }, 1L)),
        )

    private fun candleAvailabilityRange(rows: List<HyperliquidCandle>): TimeRange {
        val firstAvailable = Math.addExact(rows.minOf { it.closeTime.toEpochMilli() }, 1L)
        val lastAvailable = Math.addExact(rows.maxOf { it.closeTime.toEpochMilli() }, 1L)
        return TimeRange(
            fromInclusive = MarketTimestamp(firstAvailable),
            toExclusive = MarketTimestamp(Math.addExact(lastAvailable, 1L)),
        )
    }

    private fun instantRange(value: Instant): TimeRange {
        val millis = value.toEpochMilli()
        return TimeRange(MarketTimestamp(millis), MarketTimestamp(Math.addExact(millis, 1L)))
    }

    private fun enforceMinimumCoverage(
        report: DataQualityReport,
        coverageMillis: Long,
        requirement: DataRequirement,
    ): DataQualityReport {
        if (coverageMillis >= requirement.minimumHistoryMillis) return report
        return report.copy(
            issues = report.issues + DataQualityIssue(
                kind = QualityIssueKind.INSUFFICIENT_COVERAGE,
                severity = QualitySeverity.FATAL,
                message = "Observed $coverageMillis ms, below required ${requirement.minimumHistoryMillis} ms",
            ),
        )
    }

    private fun enforceMaximumAvailabilityLag(
        report: DataQualityReport,
        events: List<MarketEvent>,
        requirement: DataRequirement,
        checkedAt: Instant,
    ): DataQualityReport {
        val maximumLag = requirement.maximumAvailabilityLagMillis ?: return report
        return HyperliquidQuality.merge(
            checkedAt,
            listOf(
                report,
                HyperliquidQuality.availability(events, checkedAt, maximumLag),
            ),
        )
    }

    companion object {
        private const val WIRE_SCHEMA_VERSION = "hyperliquid-info-json-v1"
        private const val FUNDING_INTERVAL_MILLIS = 3_600_000L
        private const val SINGLE_OBSERVATION_COVERAGE_MILLIS = 1L
        internal const val SELECTED_INSTRUMENTS_PARAMETER = "selectedInstruments"
        private val INSTRUMENT_SYMBOL = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}")

        fun mainnet(
            store: ContentAddressedDataStore,
            clock: Clock = Clock.systemUTC(),
        ): HyperliquidDataIngestor =
            HyperliquidDataIngestor(
                store = store,
                rest = HyperliquidRestClient.mainnet(clock = clock),
                clock = clock,
            )

        internal fun create(
            store: ContentAddressedDataStore,
            rest: HyperliquidRestClient,
            clock: Clock,
        ): HyperliquidDataIngestor =
            HyperliquidDataIngestor(store, rest, clock)
    }
}
