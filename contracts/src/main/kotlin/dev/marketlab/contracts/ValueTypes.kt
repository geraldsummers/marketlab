package dev.marketlab.contracts

import kotlinx.serialization.Serializable
import java.math.BigDecimal

private val identifierPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")
private val sha256Pattern = Regex("[0-9a-f]{64}")

private fun requireIdentifier(kind: String, value: String) {
    require(identifierPattern.matches(value)) {
        "$kind must contain 1..128 portable identifier characters"
    }
}

@Serializable
@JvmInline
value class DataSourceId(val value: String) {
    init {
        requireIdentifier("data source id", value)
    }
}

@Serializable
@JvmInline
value class InstrumentId(val value: String) {
    init {
        requireIdentifier("instrument id", value)
    }
}

@Serializable
@JvmInline
value class MarketEventId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 256) {
            "market event id must contain 1..256 characters"
        }
    }
}

@Serializable
@JvmInline
value class SnapshotId(val value: String) {
    init {
        requireIdentifier("snapshot id", value)
    }
}

@Serializable
@JvmInline
value class ArtifactId(val value: String) {
    init {
        requireIdentifier("artifact id", value)
    }
}

@Serializable
@JvmInline
value class JobId(val value: String) {
    init {
        requireIdentifier("job id", value)
    }
}

@Serializable
@JvmInline
value class AttemptId(val value: String) {
    init {
        requireIdentifier("attempt id", value)
    }
}

@Serializable
@JvmInline
value class CapabilityId(val value: String) {
    init {
        requireIdentifier("capability id", value)
    }
}

@Serializable
@JvmInline
value class RunId(val value: String) {
    init {
        requireIdentifier("run id", value)
    }
}

@Serializable
@JvmInline
value class TrialId(val value: String) {
    init {
        requireIdentifier("trial id", value)
    }
}

@Serializable
@JvmInline
value class TheoryId(val value: String) {
    init {
        requireIdentifier("theory id", value)
    }
}

@Serializable
@JvmInline
value class PaperSessionId(val value: String) {
    init {
        requireIdentifier("paper session id", value)
    }
}

@Serializable
@JvmInline
value class OrderId(val value: String) {
    init {
        requireIdentifier("order id", value)
    }
}

@Serializable
@JvmInline
value class FillId(val value: String) {
    init {
        requireIdentifier("fill id", value)
    }
}

@Serializable
@JvmInline
value class LedgerEntryId(val value: String) {
    init {
        requireIdentifier("ledger entry id", value)
    }
}

@Serializable
@JvmInline
value class WorkerRequestId(val value: String) {
    init {
        requireIdentifier("worker request id", value)
    }
}

/**
 * UTC epoch time with millisecond precision. Exchange, receive, and availability
 * timestamps remain distinct throughout the system.
 */
@Serializable
@JvmInline
value class MarketTimestamp(val epochMillis: Long) : Comparable<MarketTimestamp> {
    override fun compareTo(other: MarketTimestamp): Int = epochMillis.compareTo(other.epochMillis)
}

/**
 * Exact wire/storage decimal. Only the canonical non-exponent representation is accepted.
 * Calculations can explicitly convert to [BigDecimal] or floating point downstream.
 */
@Serializable
@JvmInline
value class DecimalValue(val canonical: String) : Comparable<DecimalValue> {
    init {
        require(canonical == canonicalDecimal(canonical)) {
            "decimal must use canonical non-exponent notation"
        }
    }

    fun toBigDecimal(): BigDecimal = BigDecimal(canonical)

    override fun compareTo(other: DecimalValue): Int = toBigDecimal().compareTo(other.toBigDecimal())

    companion object {
        val ZERO: DecimalValue = DecimalValue("0")

        fun of(value: String): DecimalValue = DecimalValue(canonicalDecimal(value))

        fun of(value: BigDecimal): DecimalValue = DecimalValue(canonicalDecimal(value.toPlainString()))
    }
}

private fun canonicalDecimal(value: String): String {
    require(value.isNotBlank()) { "decimal cannot be blank" }
    val parsed = try {
        BigDecimal(value)
    } catch (exception: NumberFormatException) {
        throw IllegalArgumentException("invalid decimal", exception)
    }
    val normalized = if (parsed.compareTo(BigDecimal.ZERO) == 0) {
        BigDecimal.ZERO
    } else {
        parsed.stripTrailingZeros()
    }
    return normalized.toPlainString()
}

@Serializable
@JvmInline
value class Sha256Digest(val hex: String) {
    init {
        require(sha256Pattern.matches(hex)) { "SHA-256 digest must be 64 lowercase hexadecimal characters" }
    }
}

@Serializable
@JvmInline
value class FiniteDouble(val value: Double) {
    init {
        require(value.isFinite()) { "value must be finite" }
    }
}

@Serializable
data class TimeRange(
    val fromInclusive: MarketTimestamp,
    val toExclusive: MarketTimestamp,
) {
    init {
        require(fromInclusive < toExclusive) { "time range must be non-empty" }
    }
}
