package dev.marketlab.coordinator

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.DataSnapshot
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.data.hyperliquid.HyperliquidDataIngestor
import dev.marketlab.persistence.JobRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration

class HyperliquidIngestionJobHandler(
    private val ingestor: HyperliquidDataIngestor,
    private val registrar: SnapshotRegistrar,
    private val defaultCandleInterval: String,
    private val maximumRange: Duration,
) {
    suspend fun execute(job: JobRow): JobExecutionResult {
        require(job.kind == INGESTION_JOB_KIND)
        if (job.resourceType != INGESTION_RESOURCE_TYPE || job.resourceId != job.id.toString()) {
            throw PermanentJobException(
                "INVALID_JOB_RESOURCE",
                "Ingestion job resource identity does not match its job id",
            )
        }
        val request =
            IngestionJobParser.parse(
                payload = job.payload,
                defaultCandleInterval = defaultCandleInterval,
                maximumRange = maximumRange,
            )
        val completed = mutableListOf<Pair<IngestionOperation, RegisteredSnapshot>>()
        request.operations(job.id).forEach { operation ->
            completed +=
                operation to
                    registrar.registerOrReuse(operation) {
                        fetch(operation)
                    }
        }
        return JobExecutionResult.Succeeded(
            buildJsonObject {
                put("source", HYPERLIQUID_REST)
                put("production", true)
                put(
                    "snapshots",
                    JsonArray(
                        completed.map { (operation, snapshot) ->
                            buildJsonObject {
                                put("id", snapshot.id.toString())
                                put("contractId", snapshot.contractId)
                                put("manifestHash", snapshot.manifestHash)
                                put("objectCount", snapshot.objectCount)
                                put("rowCount", snapshot.rowCount)
                                put("operationKey", operation.operationKey)
                                put("dataKind", operation.kind.name)
                                put(
                                    "instruments",
                                    JsonArray(operation.instruments.map(::JsonPrimitive)),
                                )
                            }
                        },
                    ),
                )
                put("snapshotCount", completed.size)
            },
        )
    }

    private suspend fun fetch(operation: IngestionOperation): DataSnapshot {
        val requirement = operation.requirement()
        return when (operation.kind) {
            RestDataKind.CANDLES ->
                ingestor.ingestCandles(
                    coin = operation.instruments.single(),
                    interval = operation.candleInterval,
                    startInclusive = requireNotNull(operation.startAt),
                    endExclusive = requireNotNull(operation.endAt),
                    requirement = requirement,
                ).snapshot

            RestDataKind.FUNDING ->
                ingestor.ingestFunding(
                    coin = operation.instruments.single(),
                    startInclusive = requireNotNull(operation.startAt),
                    endExclusive = requireNotNull(operation.endAt),
                    requirement = requirement,
                ).snapshot

            RestDataKind.L2_BOOK ->
                ingestor.ingestL2Book(
                    coin = operation.instruments.single(),
                    requirement = requirement,
                    significantFigures = operation.l2SignificantFigures,
                    mantissa = operation.l2Mantissa,
                ).snapshot

            RestDataKind.OPEN_INTEREST,
            RestDataKind.MARK_ORACLE,
            -> {
                val result =
                    ingestor.ingestAssetContexts(
                        requirement = requirement,
                        instruments = operation.instruments.toSet(),
                    )
                requireRequestedInstruments(operation, result.observations)
                result.snapshot
            }
        }
    }

    private fun requireRequestedInstruments(
        operation: IngestionOperation,
        observations: List<MarketEvent>,
    ) {
        val observed = observations.map { it.header.instrument.value }.toSet()
        val missing =
            operation.instruments.filter { instrument ->
                "hyperliquid:perpetual:$instrument" !in observed
            }
        if (missing.isNotEmpty()) {
            throw PermanentJobException(
                "UNKNOWN_HYPERLIQUID_INSTRUMENT",
                "Hyperliquid mainnet returned no requested asset context",
            )
        }
    }

    private fun IngestionOperation.requirement(): DataRequirement {
        val observation =
            when (kind) {
                RestDataKind.CANDLES -> ObservationKind.CANDLE
                RestDataKind.FUNDING -> ObservationKind.FUNDING
                RestDataKind.L2_BOOK -> ObservationKind.L2_BOOK
                RestDataKind.OPEN_INTEREST -> ObservationKind.OPEN_INTEREST
                RestDataKind.MARK_ORACLE -> ObservationKind.MARK_ORACLE
            }
        return DataRequirement(
            key = "ingestion-$operationKey",
            observation = observation,
            sourcePreference = listOf(DataSourceId(HYPERLIQUID_SOURCE)),
            instrumentKinds = listOf(InstrumentKind.PERPETUAL),
            sampling = sampling(),
            requiredFields = requiredFields(),
            minimumHistoryMillis = historyMillis(),
            maximumAvailabilityLagMillis =
                when (kind) {
                    RestDataKind.CANDLES -> candleCadenceMillis(candleInterval)
                    RestDataKind.FUNDING -> FUNDING_INTERVAL_MILLIS
                    RestDataKind.L2_BOOK,
                    RestDataKind.OPEN_INTEREST,
                    RestDataKind.MARK_ORACLE,
                    -> MAX_REST_OBSERVATION_LAG_MILLIS
                },
        )
    }

    private fun IngestionOperation.sampling(): Sampling =
        when (kind) {
            RestDataKind.CANDLES ->
                candleCadenceMillis(candleInterval)?.let(Sampling::FixedDuration)
                    ?: Sampling.EventTime
            RestDataKind.FUNDING -> Sampling.FixedDuration(FUNDING_INTERVAL_MILLIS)
            RestDataKind.L2_BOOK,
            RestDataKind.OPEN_INTEREST,
            RestDataKind.MARK_ORACLE,
            -> Sampling.EventTime
        }

    private fun IngestionOperation.requiredFields(): List<String> =
        when (kind) {
            RestDataKind.CANDLES ->
                listOf(
                    "exchangeTime",
                    "receivedAt",
                    "availableAt",
                    "open",
                    "high",
                    "low",
                    "close",
                    "baseVolume",
                    "tradeCount",
                )
            RestDataKind.FUNDING ->
                listOf("exchangeTime", "receivedAt", "availableAt", "rate", "premium")
            RestDataKind.L2_BOOK ->
                listOf("exchangeTime", "receivedAt", "availableAt", "bids", "asks")
            RestDataKind.OPEN_INTEREST ->
                listOf("exchangeTime", "receivedAt", "availableAt", "contracts")
            RestDataKind.MARK_ORACLE ->
                listOf("exchangeTime", "receivedAt", "availableAt", "markPrice", "oraclePrice")
        }

    private fun IngestionOperation.historyMillis(): Long =
        if (startAt != null && endAt != null) {
            Duration.between(startAt, endAt).toMillis().coerceAtLeast(1L)
        } else {
            1L
        }

    private fun candleCadenceMillis(interval: String): Long? =
        when (interval) {
            "1m" -> 60_000L
            "3m" -> 180_000L
            "5m" -> 300_000L
            "15m" -> 900_000L
            "30m" -> 1_800_000L
            "1h" -> 3_600_000L
            "2h" -> 7_200_000L
            "4h" -> 14_400_000L
            "8h" -> 28_800_000L
            "12h" -> 43_200_000L
            "1d" -> 86_400_000L
            "3d" -> 259_200_000L
            "1w" -> 604_800_000L
            "1M" -> null
            else -> error("parser admitted unsupported interval")
        }

    private companion object {
        const val INGESTION_JOB_KIND = "INGESTION"
        const val INGESTION_RESOURCE_TYPE = "ingestion"
        const val HYPERLIQUID_REST = "HYPERLIQUID_REST"
        const val HYPERLIQUID_SOURCE = "hyperliquid-mainnet"
        const val FUNDING_INTERVAL_MILLIS = 3_600_000L
        const val MAX_REST_OBSERVATION_LAG_MILLIS = 60_000L
    }
}
