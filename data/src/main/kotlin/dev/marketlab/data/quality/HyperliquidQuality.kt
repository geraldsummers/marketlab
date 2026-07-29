package dev.marketlab.data.quality

import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.TimeRange
import dev.marketlab.contracts.data.DataQualityIssue
import dev.marketlab.contracts.data.DataQualityReport
import dev.marketlab.contracts.data.QualityIssueKind
import dev.marketlab.contracts.data.QualitySeverity
import dev.marketlab.contracts.market.MarketEvent
import dev.marketlab.data.hyperliquid.HyperliquidCandle
import dev.marketlab.data.hyperliquid.HyperliquidFunding
import dev.marketlab.data.hyperliquid.HyperliquidL2Book
import java.math.BigDecimal
import java.time.Instant

internal object HyperliquidQuality {
    fun candles(
        candles: List<HyperliquidCandle>,
        checkedAt: Instant,
        expectedCoin: String,
        expectedInterval: String,
        unfinishedRowsRemoved: Int = 0,
    ): DataQualityReport {
        val issues = mutableListOf<DataQualityIssue>()
        if (candles.isEmpty()) {
            issues += fatal(
                QualityIssueKind.INSUFFICIENT_COVERAGE,
                "Hyperliquid returned no closed candles for the requested range",
            )
        }
        if (unfinishedRowsRemoved > 0) {
            issues += fatal(
                QualityIssueKind.UNFINISHED_CANDLE,
                "Rejected $unfinishedRowsRemoved unfinished candle(s)",
            )
        }
        candles.groupBy { it.openTime }
            .filterValues { it.size > 1 }
            .keys
            .forEach { duplicateTime ->
                issues += fatal(
                    QualityIssueKind.DUPLICATE,
                    "Duplicate candle at ${duplicateTime.toEpochMilli()}",
                    instantRange(duplicateTime),
                )
            }
        val ordered = candles.sortedBy { it.openTime }
        ordered.forEach { candle ->
            val maxBody = maxOf(candle.open, candle.close, candle.low)
            val minBody = minOf(candle.open, candle.close, candle.high)
            if (
                candle.coin != expectedCoin ||
                candle.interval != expectedInterval ||
                candle.open <= BigDecimal.ZERO ||
                candle.close <= BigDecimal.ZERO ||
                candle.high < maxBody ||
                candle.low > minBody ||
                candle.volume < BigDecimal.ZERO ||
                candle.tradeCount <= 0L ||
                !candle.openTime.isBefore(candle.closeTime)
            ) {
                issues += fatal(
                    QualityIssueKind.INVALID_CANDLE,
                    "Invalid $expectedCoin/$expectedInterval candle at ${candle.openTime.toEpochMilli()}",
                    TimeRange(
                        MarketTimestamp(candle.openTime.toEpochMilli()),
                        MarketTimestamp(Math.addExact(candle.closeTime.toEpochMilli(), 1L)),
                    ),
                )
            }
            issues += implausibleTimestamp(candle.openTime, checkedAt)
            if (!candle.closeTime.isBefore(checkedAt)) {
                issues += fatal(
                    QualityIssueKind.UNFINISHED_CANDLE,
                    "Candle closes at or after its quality-check time",
                    instantRange(candle.closeTime),
                )
            }
        }
        ordered.zipWithNext().forEach { (left, right) ->
            val expectedNext = Math.addExact(left.closeTime.toEpochMilli(), 1L)
            if (right.openTime.toEpochMilli() != expectedNext) {
                issues += fatal(
                    QualityIssueKind.SEQUENCE_GAP,
                    "Candle discontinuity: expected $expectedNext, got ${right.openTime.toEpochMilli()}",
                    TimeRange(
                        MarketTimestamp(minOf(expectedNext, right.openTime.toEpochMilli())),
                        MarketTimestamp(maxOf(expectedNext, right.openTime.toEpochMilli()) + 1L),
                    ),
                )
            }
        }
        return DataQualityReport(MarketTimestamp(checkedAt.toEpochMilli()), issues.distinct())
    }

    fun funding(
        rows: List<HyperliquidFunding>,
        checkedAt: Instant,
        expectedCoin: String,
        rejectedRowsRemoved: Int = 0,
    ): DataQualityReport {
        val issues = mutableListOf<DataQualityIssue>()
        if (rows.isEmpty()) {
            issues += fatal(
                QualityIssueKind.INSUFFICIENT_COVERAGE,
                "Hyperliquid returned no funding observations for the requested range",
            )
        }
        if (rejectedRowsRemoved > 0) {
            issues += fatal(
                QualityIssueKind.MISSING_METADATA,
                "Rejected $rejectedRowsRemoved funding observation(s) outside the requested instrument or range",
            )
        }
        rows.groupBy { it.time }
            .filterValues { it.size > 1 }
            .keys
            .forEach { duplicateTime ->
                issues += fatal(
                    QualityIssueKind.DUPLICATE,
                    "Duplicate funding observation at ${duplicateTime.toEpochMilli()}",
                    instantRange(duplicateTime),
                )
            }
        val ordered = rows.sortedBy { it.time }
        ordered.forEach { row ->
            if (row.coin != expectedCoin) {
                issues += fatal(
                    QualityIssueKind.MISSING_METADATA,
                    "Unexpected funding instrument ${row.coin}; expected $expectedCoin",
                    instantRange(row.time),
                )
            }
            issues += implausibleTimestamp(row.time, checkedAt)
        }
        ordered.zipWithNext().forEach { (left, right) ->
            val delta = right.time.toEpochMilli() - left.time.toEpochMilli()
            if (delta > MAX_FUNDING_GAP_MILLIS) {
                issues += fatal(
                    QualityIssueKind.SEQUENCE_GAP,
                    "Funding gap of $delta ms exceeds the allowed cadence",
                    TimeRange(
                        MarketTimestamp(left.time.toEpochMilli()),
                        MarketTimestamp(right.time.toEpochMilli()),
                    ),
                )
            }
        }
        return DataQualityReport(MarketTimestamp(checkedAt.toEpochMilli()), issues.distinct())
    }

    fun l2Book(book: HyperliquidL2Book, checkedAt: Instant): DataQualityReport {
        val issues = mutableListOf<DataQualityIssue>()
        val invalidSide =
            book.bids.isEmpty() ||
                book.asks.isEmpty() ||
                book.bids.any { it.price <= BigDecimal.ZERO || it.size <= BigDecimal.ZERO || it.orderCount < 0 } ||
                book.asks.any { it.price <= BigDecimal.ZERO || it.size <= BigDecimal.ZERO || it.orderCount < 0 } ||
                book.bids.zipWithNext().any { (left, right) -> left.price <= right.price } ||
                book.asks.zipWithNext().any { (left, right) -> left.price >= right.price } ||
                (
                    book.bids.isNotEmpty() &&
                        book.asks.isNotEmpty() &&
                        book.bids.first().price >= book.asks.first().price
                    )
        if (invalidSide) {
            issues += fatal(
                QualityIssueKind.INVALID_BOOK,
                "L2 book is empty, crossed, unsorted, or contains an invalid level",
                instantRange(book.time),
            )
        }
        if (
            book.bids.map { it.price }.distinct().size != book.bids.size ||
            book.asks.map { it.price }.distinct().size != book.asks.size
        ) {
            issues += fatal(
                QualityIssueKind.DUPLICATE,
                "L2 book contains a duplicate price level",
                instantRange(book.time),
            )
        }
        issues += implausibleTimestamp(book.time, checkedAt)
        return DataQualityReport(MarketTimestamp(checkedAt.toEpochMilli()), issues.distinct())
    }

    fun merge(checkedAt: Instant, reports: Iterable<DataQualityReport>): DataQualityReport =
        DataQualityReport(
            checkedAt = MarketTimestamp(checkedAt.toEpochMilli()),
            issues = reports.flatMap { it.issues }.distinct(),
        )

    fun availability(
        events: List<MarketEvent>,
        checkedAt: Instant,
        maximumLagMillis: Long,
    ): DataQualityReport {
        require(maximumLagMillis >= 0L)
        val violations = events.mapNotNull { event ->
            val lag =
                Math.subtractExact(
                    event.header.availableAt.epochMillis,
                    event.header.exchangeTime.epochMillis,
                )
            if (lag < 0L || lag > maximumLagMillis) event to lag else null
        }
        val issues =
            if (violations.isEmpty()) {
                emptyList()
            } else {
                val observedMinimum = violations.minOf { it.second }
                val observedMaximum = violations.maxOf { it.second }
                listOf(
                    fatal(
                        QualityIssueKind.STALE_DATA,
                        "${violations.size} observation(s) violate the 0..$maximumLagMillis ms " +
                            "availability-lag requirement; observed $observedMinimum..$observedMaximum ms",
                    ),
                )
            }
        return DataQualityReport(MarketTimestamp(checkedAt.toEpochMilli()), issues)
    }

    fun requireUsable(report: DataQualityReport) {
        check(report.usable) {
            val summary = report.issues
                .filter { it.severity == QualitySeverity.FATAL }
                .joinToString(separator = "; ") { "${it.kind}: ${it.message}" }
            "Data failed closed: $summary"
        }
    }

    private fun implausibleTimestamp(value: Instant, checkedAt: Instant): List<DataQualityIssue> {
        val earliest = EARLIEST_PLAUSIBLE_TIMESTAMP
        val latest = checkedAt.plusSeconds(MAX_CLOCK_SKEW_SECONDS)
        return if (value.isBefore(earliest) || value.isAfter(latest)) {
            listOf(
                fatal(
                    QualityIssueKind.TIMESTAMP_UNIT_CHANGE,
                    "Implausible millisecond timestamp ${value.toEpochMilli()}",
                    instantRange(value),
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun fatal(
        kind: QualityIssueKind,
        message: String,
        range: TimeRange? = null,
    ): DataQualityIssue =
        DataQualityIssue(
            kind = kind,
            severity = QualitySeverity.FATAL,
            message = message,
            timeRange = range,
        )

    private fun instantRange(value: Instant): TimeRange {
        val millis = value.toEpochMilli()
        return TimeRange(MarketTimestamp(millis), MarketTimestamp(Math.addExact(millis, 1L)))
    }

    private const val MAX_FUNDING_GAP_MILLIS = 3_900_000L
    private const val MAX_CLOCK_SKEW_SECONDS = 300L
    private val EARLIEST_PLAUSIBLE_TIMESTAMP: Instant = Instant.parse("2018-01-01T00:00:00Z")
}
