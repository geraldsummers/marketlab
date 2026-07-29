package dev.marketlab.backfill

import dev.marketlab.historical.HistoricalAsset
import dev.marketlab.historical.HistoricalStudyLock
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
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
    val study: HistoricalStudyLock,
) {
    init {
        listOfNotNull(outputRoot, modelsRoot, modelLock, programLock, analysisLock).forEach {
            require(it.isAbsolute) { "backfill paths must be absolute" }
        }
        require(outputRoot.normalize().nameCount >= 2) { "backfill output root is too broad" }
        require(start < endExclusive) { "backfill start must precede end" }
        require(sources.isNotEmpty() && sources.all { it in setOf("social", "market", "analysis") })
        require(start == study.startInclusive && endExclusive == study.endExclusive) {
            "command period differs from the registered study"
        }
        require(assets.isNotEmpty() && assets.all { it in study.assetSymbols }) {
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
            val programLock = Path.of(required("program-lock")).toAbsolutePath().normalize()
            val study = HistoricalStudyLock.read(programLock)
            return BackfillConfig(
                outputRoot = Path.of(required("output-root")).toAbsolutePath().normalize(),
                modelsRoot = Path.of(required("models-root")).toAbsolutePath().normalize(),
                modelLock = Path.of(required("model-lock")).toAbsolutePath().normalize(),
                programLock = programLock,
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
                        ?: study.assetSymbols,
                study = study,
            )
        }
    }
}

internal val HistoricalAsset.binanceSymbol: String
    get() = "${symbol}USDT"

internal val HistoricalAsset.match: Regex
    get() {
        val alternatives =
            buildList {
                add("""\${'$'}${Regex.escape(symbol)}\b""")
                if (matchPlainSymbol) add("""\b${Regex.escape(symbol)}\b""")
                matchTerms.forEach { add("""\b${Regex.escape(it)}\b""") }
            }
        return Regex("(?i)(${alternatives.joinToString("|")})")
    }

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
