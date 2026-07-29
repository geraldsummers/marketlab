package dev.marketlab.backfill

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.Serializable

internal data class BackfillConfig(
    val outputRoot: Path,
    val modelsRoot: Path,
    val modelLock: Path,
    val programLock: Path,
    val analysisLock: Path?,
    val start: Instant,
    val endExclusive: Instant,
    val sources: Set<String>,
    val assets: Set<String>,
) {
    init {
        listOfNotNull(outputRoot, modelsRoot, modelLock, programLock, analysisLock).forEach {
            require(it.isAbsolute) { "backfill paths must be absolute" }
        }
        require(outputRoot.normalize().nameCount >= 2) { "backfill output root is too broad" }
        require(start < endExclusive) { "backfill start must precede end" }
        require(sources.isNotEmpty() && sources.all { it in setOf("social", "market", "analysis") })
        require(assets.isNotEmpty() && assets.all { requested -> ASSETS.any { it.symbol == requested } }) {
            "unknown or empty asset subset"
        }
        require("analysis" !in sources || analysisLock != null) {
            "--analysis-lock is required for analysis"
        }
    }

    companion object {
        fun parse(arguments: Array<String>): BackfillConfig {
            val values = linkedMapOf<String, String>()
            var index = 0
            while (index < arguments.size) {
                val key = arguments[index]
                require(key.startsWith("--") && index + 1 < arguments.size) {
                    "arguments must be --key value pairs"
                }
                require(values.put(key.removePrefix("--"), arguments[index + 1]) == null) {
                    "duplicate argument: $key"
                }
                index += 2
            }
            val expected =
                setOf(
                    "output-root",
                    "models-root",
                    "model-lock",
                    "program-lock",
                    "analysis-lock",
                    "start",
                    "end",
                    "sources",
                    "assets",
                )
            require(values.keys.all { it in expected }) {
                "unknown arguments: ${values.keys - expected}"
            }
            fun required(key: String) =
                requireNotNull(values[key]?.trim()?.takeIf(String::isNotEmpty)) {
                    "--$key is required"
                }
            return BackfillConfig(
                outputRoot = Path.of(required("output-root")).toAbsolutePath().normalize(),
                modelsRoot = Path.of(required("models-root")).toAbsolutePath().normalize(),
                modelLock = Path.of(required("model-lock")).toAbsolutePath().normalize(),
                programLock = Path.of(required("program-lock")).toAbsolutePath().normalize(),
                analysisLock =
                    values["analysis-lock"]
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { Path.of(it).toAbsolutePath().normalize() },
                start = Instant.parse(required("start")),
                endExclusive = Instant.parse(required("end")),
                sources =
                    required("sources")
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toSet(),
                assets =
                    values["assets"]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?.toSet()
                        ?: ASSETS.map(AssetSpec::symbol).toSet(),
            )
        }
    }
}

internal data class AssetSpec(
    val symbol: String,
    val query: String,
    val match: Regex,
) {
    val binanceSymbol: String = "${symbol}USDT"
}

internal val ASSETS =
    listOf(
        AssetSpec("BTC", "bitcoin", Regex("""(?i)(\${'$'}BTC\b|\bbitcoin\b)""")),
        AssetSpec("ETH", "ethereum", Regex("""(?i)(\${'$'}ETH\b|\bethereum\b|\bether\b)""")),
        AssetSpec("HYPE", "hyperliquid", Regex("""(?i)(\${'$'}HYPE\b|\bhyperliquid\b)""")),
        AssetSpec("LIT", "lighter crypto", Regex("""(?i)(\${'$'}LIT\b|\blighter\b)""")),
        AssetSpec("NEAR", "near protocol", Regex("""(?i)(\${'$'}NEAR\b|\bnear protocol\b)""")),
        AssetSpec("PUMP", "pump.fun", Regex("""(?i)(\${'$'}PUMP\b|\bpump\.fun\b)""")),
        AssetSpec("SOL", "solana", Regex("""(?i)(\${'$'}SOL\b|\bsolana\b)""")),
        AssetSpec("WLD", "worldcoin", Regex("""(?i)(\${'$'}WLD\b|\bworldcoin\b|\bworld network\b)""")),
        AssetSpec("XRP", "xrp ripple", Regex("""(?i)(\${'$'}XRP\b|\bXRP\b|\bripple\b)""")),
        AssetSpec("ZEC", "zcash", Regex("""(?i)(\${'$'}ZEC\b|\bzcash\b)""")),
    )

internal val MONTHS =
    listOf(
        "2025-10",
        "2025-11",
        "2025-12",
        "2026-01",
        "2026-02",
        "2026-03",
        "2026-04",
        "2026-05",
        "2026-06",
    ).map(YearMonth::parse)

@Serializable
internal data class RawResponseManifest(
    val schemaVersion: String = "marketlab.backfill-raw-response.v1",
    val requestUri: String,
    val retrievedAtEpochMillis: Long,
    val responseSha256: String,
    val responseBytes: Long,
)

@Serializable
internal data class SocialObservation(
    val schemaVersion: String = "marketlab.retrospective-social-observation.v1",
    val symbol: String,
    val sourceEventId: String,
    val authorIdSha256: String,
    val createdAtEpochMillis: Long,
    val indexedAtAvailabilityProxyEpochMillis: Long,
    val retrievedAtEpochMillis: Long,
    val textSha256: String,
    val language: String?,
    val negativeProbability: Double,
    val neutralProbability: Double,
    val positiveProbability: Double,
    val polarity: Double,
    val rawResponseSha256: String,
)

@Serializable
internal data class SocialDayManifest(
    val schemaVersion: String = "marketlab.retrospective-social-day.v2",
    val symbol: String,
    val date: String,
    val query: String,
    val initialWindowMillis: Long = 24L * 60L * 60L * 1_000L,
    val saturationThreshold: Int = 100,
    val cursorPaginationSupported: Boolean = false,
    val responseObjects: List<RawResponseManifest>,
    val outputUri: String,
    val outputSha256: String,
    val observationCount: Int,
    val completedAtEpochMillis: Long,
)

@Serializable
internal data class MarketBar15m(
    val schemaVersion: String = "marketlab.retrospective-market-bar-15m.v1",
    val symbol: String,
    val openTimeEpochMillis: Long,
    val closeTimeExclusiveEpochMillis: Long,
    val open: Double,
    val close: Double,
    val realizedVariance1m: Double,
    val minuteCount: Int,
)

@Serializable
internal data class MarketAssetManifest(
    val schemaVersion: String = "marketlab.retrospective-market-asset.v1",
    val symbol: String,
    val archiveObjects: List<RawResponseManifest>,
    val outputUri: String,
    val outputSha256: String,
    val barCount: Int,
    val gapCount: Int = 0,
    val missingMinuteCount: Long = 0,
    val firstOpenTimeEpochMillis: Long,
    val lastCloseTimeExclusiveEpochMillis: Long,
    val completedAtEpochMillis: Long,
)

internal fun dates(start: Instant, endExclusive: Instant): Sequence<LocalDate> {
    val first = start.atZone(java.time.ZoneOffset.UTC).toLocalDate()
    val end = endExclusive.atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return generateSequence(first) { prior -> prior.plusDays(1).takeIf { it < end } }
}
