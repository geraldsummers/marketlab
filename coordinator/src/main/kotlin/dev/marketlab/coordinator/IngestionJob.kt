package dev.marketlab.coordinator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

enum class RestDataKind {
    CANDLES,
    FUNDING,
    L2_BOOK,
    OPEN_INTEREST,
    MARK_ORACLE,
}

data class HyperliquidRestIngestionJob(
    val instruments: List<String>,
    val dataKinds: List<RestDataKind>,
    val startAt: Instant?,
    val endAt: Instant?,
    val candleInterval: String,
    val l2SignificantFigures: Int?,
    val l2Mantissa: Int?,
) {
    fun operations(jobId: UUID): List<IngestionOperation> {
        val operations = mutableListOf<IngestionOperation>()
        dataKinds.forEach { kind ->
            when (kind) {
                RestDataKind.CANDLES,
                RestDataKind.FUNDING,
                RestDataKind.L2_BOOK,
                -> instruments.forEach { instrument ->
                    operations +=
                        IngestionOperation(
                            jobId = jobId,
                            ordinal = operations.size,
                            kind = kind,
                            instruments = listOf(instrument),
                            startAt = startAt,
                            endAt = endAt,
                            candleInterval = candleInterval,
                            l2SignificantFigures = l2SignificantFigures,
                            l2Mantissa = l2Mantissa,
                        )
                }

                RestDataKind.OPEN_INTEREST,
                RestDataKind.MARK_ORACLE,
                -> operations +=
                    IngestionOperation(
                        jobId = jobId,
                        ordinal = operations.size,
                        kind = kind,
                        instruments = instruments,
                        startAt = null,
                        endAt = null,
                        candleInterval = candleInterval,
                        l2SignificantFigures = null,
                        l2Mantissa = null,
                    )
            }
        }
        return operations
    }
}

data class IngestionOperation(
    val jobId: UUID,
    val ordinal: Int,
    val kind: RestDataKind,
    val instruments: List<String>,
    val startAt: Instant?,
    val endAt: Instant?,
    val candleInterval: String,
    val l2SignificantFigures: Int?,
    val l2Mantissa: Int?,
) {
    init {
        require(ordinal >= 0)
        require(instruments.isNotEmpty())
    }

    val operationKey: String by lazy {
        val stableIdentity =
            listOf(
                "marketlab-hyperliquid-rest-v1",
                jobId.toString(),
                ordinal.toString(),
                kind.name,
                instruments.joinToString(","),
                startAt?.toString().orEmpty(),
                endAt?.toString().orEmpty(),
                candleInterval,
                l2SignificantFigures?.toString().orEmpty(),
                l2Mantissa?.toString().orEmpty(),
            ).joinToString(separator = "\u001f")
        MessageDigest.getInstance("SHA-256")
            .digest(stableIdentity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    val displayInstrument: String
        get() = if (instruments.size == 1) instruments.single() else "ALL"
}

object IngestionJobParser {
    val SUPPORTED_CANDLE_INTERVALS: Set<String> =
        setOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "3d", "1w", "1M")

    fun parse(
        payload: JsonObject,
        defaultCandleInterval: String,
        maximumRange: Duration,
    ): HyperliquidRestIngestionJob {
        rejectUnknownFields(payload)
        val source = payload.requiredString("source")
        if (source != HYPERLIQUID_REST) {
            throw PermanentJobException(
                "UNSUPPORTED_INGESTION_SOURCE",
                "Only HYPERLIQUID_REST ingestion is supported by this coordinator",
            )
        }

        val instruments =
            payload.requiredStringArray("instruments").also {
                requireBoundedUnique("instruments", it, MAX_INSTRUMENTS)
                it.forEach { instrument ->
                    if (!SYMBOL.matches(instrument)) {
                        throw PermanentJobException(
                            "INVALID_INGESTION_PAYLOAD",
                            "Invalid Hyperliquid instrument",
                        )
                    }
                }
            }
        val rawKinds =
            payload.requiredStringArray("dataKinds").also {
                requireBoundedUnique("dataKinds", it, MAX_DATA_KINDS)
            }
        val dataKinds = expandAndValidateKinds(rawKinds)
        val historical = dataKinds.any { it == RestDataKind.CANDLES || it == RestDataKind.FUNDING }
        val startAt = payload.optionalInstant("startAt")
        val endAt = payload.optionalInstant("endAt")
        if (historical && (startAt == null || endAt == null)) {
            throw PermanentJobException(
                "INVALID_INGESTION_RANGE",
                "Historical candles and funding require both startAt and endAt",
            )
        }
        if ((startAt == null) != (endAt == null)) {
            throw PermanentJobException(
                "INVALID_INGESTION_RANGE",
                "startAt and endAt must be supplied together",
            )
        }
        if (startAt != null && endAt != null) {
            if (!startAt.isBefore(endAt)) {
                throw PermanentJobException(
                    "INVALID_INGESTION_RANGE",
                    "endAt must be later than startAt",
                )
            }
            val range =
                try {
                    Duration.between(startAt, endAt)
                } catch (_: ArithmeticException) {
                    throw PermanentJobException(
                        "INVALID_INGESTION_RANGE",
                        "Ingestion time range is outside the supported bounds",
                    )
                }
            if (range > maximumRange) {
                throw PermanentJobException(
                    "INVALID_INGESTION_RANGE",
                    "Ingestion time range exceeds the configured maximum",
                )
            }
        }

        val candleInterval =
            payload.optionalString("candleInterval")
                ?: payload.optionalString("interval")
                ?: defaultCandleInterval
        if (candleInterval !in SUPPORTED_CANDLE_INTERVALS) {
            throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "Unsupported Hyperliquid candle interval",
            )
        }

        val significantFigures = payload.optionalInt("l2SignificantFigures")
        val mantissa = payload.optionalInt("l2Mantissa")
        if (significantFigures != null && significantFigures !in 2..5) {
            throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "l2SignificantFigures must be between 2 and 5",
            )
        }
        if (mantissa != null && (significantFigures != 5 || mantissa !in setOf(1, 2, 5))) {
            throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "l2Mantissa requires five significant figures and must be 1, 2, or 5",
            )
        }
        return HyperliquidRestIngestionJob(
            instruments = instruments,
            dataKinds = dataKinds,
            startAt = startAt,
            endAt = endAt,
            candleInterval = candleInterval,
            l2SignificantFigures = significantFigures,
            l2Mantissa = mantissa,
        )
    }

    private fun expandAndValidateKinds(rawKinds: List<String>): List<RestDataKind> {
        val expanded = linkedSetOf<RestDataKind>()
        rawKinds.forEach { kind ->
            when (kind) {
                "CANDLES" -> expanded += RestDataKind.CANDLES
                "FUNDING" -> expanded += RestDataKind.FUNDING
                "L2_BOOK" -> expanded += RestDataKind.L2_BOOK
                "OPEN_INTEREST" -> expanded += RestDataKind.OPEN_INTEREST
                "ORACLE_MARK" -> expanded += RestDataKind.MARK_ORACLE
                "ASSET_CONTEXT" -> {
                    expanded += RestDataKind.MARK_ORACLE
                    expanded += RestDataKind.OPEN_INTEREST
                }
                "TRADES", "BBO", "METADATA" ->
                    throw PermanentJobException(
                        "UNSUPPORTED_INGESTION_KIND",
                        "$kind is not available from the implemented Hyperliquid REST adapter",
                    )
                else ->
                    throw PermanentJobException(
                        "INVALID_INGESTION_PAYLOAD",
                        "Unknown ingestion data kind",
                    )
            }
        }
        return expanded.toList()
    }

    private fun rejectUnknownFields(payload: JsonObject) {
        val unknown = payload.keys - ALLOWED_FIELDS
        if (unknown.isNotEmpty()) {
            throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "Unknown ingestion payload field",
            )
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name)
            ?: throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must be a string",
            )

    private fun JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        val primitive = element as? JsonPrimitive
            ?: throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must be a string",
            )
        if (!primitive.isString || primitive.contentOrNull.isNullOrBlank()) {
            throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must be a non-blank string",
            )
        }
        return primitive.content
    }

    private fun JsonObject.requiredStringArray(name: String): List<String> {
        val array = this[name] as? JsonArray
            ?: throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must be an array",
            )
        return array.map {
            val primitive = it as? JsonPrimitive
            if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
                throw PermanentJobException(
                    "INVALID_INGESTION_PAYLOAD",
                    "$name must contain only non-blank strings",
                )
            }
            primitive.content
        }
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = this[name] ?: return null
        val primitive = element as? JsonPrimitive
            ?: throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must be an integer",
            )
        return primitive.content.toIntOrNull()
            ?: throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must be an integer",
            )
    }

    private fun JsonObject.optionalInstant(name: String): Instant? {
        val value = optionalString(name) ?: return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            throw PermanentJobException(
                "INVALID_INGESTION_RANGE",
                "$name must be an ISO-8601 UTC instant",
            )
        }
    }

    private fun requireBoundedUnique(
        name: String,
        values: List<String>,
        maximum: Int,
    ) {
        if (values.isEmpty() || values.size > maximum || values.distinct().size != values.size) {
            throw PermanentJobException(
                "INVALID_INGESTION_PAYLOAD",
                "$name must contain 1..$maximum unique values",
            )
        }
    }

    private const val HYPERLIQUID_REST = "HYPERLIQUID_REST"
    private const val MAX_INSTRUMENTS = 64
    private const val MAX_DATA_KINDS = 9
    private val SYMBOL = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,63}")
    private val ALLOWED_FIELDS =
        setOf(
            "source",
            "instruments",
            "dataKinds",
            "startAt",
            "endAt",
            "interval",
            "candleInterval",
            "l2SignificantFigures",
            "l2Mantissa",
        )
}
