package dev.marketlab.historical

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed, fail-closed registration for a historical acquisition and analysis.
 *
 * The lock is the sole source of study dates, assets, queries and market
 * archive slices. Application code may select a registered subset but cannot
 * silently expand or redefine the registered study.
 */
data class HistoricalStudyLock(
    val programId: String,
    val startInclusive: Instant,
    val endExclusive: Instant,
    val assets: List<HistoricalAsset>,
    val marketMonths: List<YearMonth>,
    val schedule: HistoricalSchedule,
) {
    init {
        require(programId.isNotBlank())
        require(startInclusive < endExclusive)
        require(assets.isNotEmpty())
        require(assets.map(HistoricalAsset::symbol).distinct().size == assets.size)
        require(assets.map(HistoricalAsset::symbol) == assets.map(HistoricalAsset::symbol).sorted()) {
            "registered assets must be sorted for deterministic sharding"
        }
        require(marketMonths.isNotEmpty() && marketMonths.distinct().size == marketMonths.size)
        require(marketMonths == marketMonths.sorted())
        val registeredDays = Duration.between(startInclusive, endExclusive).toDays()
        require(schedule.totalDays.toLong() == registeredDays) {
            "study schedule covers ${schedule.totalDays} days but period covers $registeredDays"
        }
    }

    val assetSymbols: Set<String> = assets.mapTo(linkedSetOf(), HistoricalAsset::symbol)

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun read(path: Path): HistoricalStudyLock {
            require(path.isAbsolute) { "study lock path must be absolute" }
            require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                "study lock must be a regular, non-symlink file: $path"
            }
            return parse(Files.readString(path))
        }

        fun parse(content: String): HistoricalStudyLock {
            val root = json.parseToJsonElement(content).jsonObject
            require(
                root.getValue("schemaVersion").jsonPrimitive.content ==
                    "marketlab.social-backfill-program-lock.v2",
            ) { "unsupported historical study schema" }
            val period = root.getValue("period").jsonObject
            val sources = root.getValue("sources").jsonObject
            val social = sources.getValue("social").jsonObject
            val queries =
                social.getValue("queries").jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
            val matchTerms =
                social.getValue("matchTerms").jsonObject
                    .mapValues { (_, terms) ->
                        terms.jsonArray.map { it.jsonPrimitive.content }
                    }
            val plainSymbols =
                social.getValue("plainSymbols").jsonArray
                    .mapTo(mutableSetOf()) { it.jsonPrimitive.content }
            val symbols =
                root.getValue("universe").jsonObject
                    .getValue("symbols").jsonArray
                    .map { it.jsonPrimitive.content }
            require(queries.keys == symbols.toSet()) { "query keys differ from registered universe" }
            require(matchTerms.keys == symbols.toSet()) {
                "match-term keys differ from registered universe"
            }
            require(plainSymbols.all { it in symbols }) {
                "plain-symbol matching is configured outside the universe"
            }
            val schedule = root.getValue("schedule").jsonObject
            return HistoricalStudyLock(
                programId = root.getValue("programId").jsonPrimitive.content,
                startInclusive = Instant.parse(period.getValue("startInclusive").jsonPrimitive.content),
                endExclusive = Instant.parse(period.getValue("endExclusive").jsonPrimitive.content),
                assets =
                    symbols.map { symbol ->
                        HistoricalAsset(
                            symbol = symbol,
                            query = queries.getValue(symbol),
                            matchTerms = matchTerms.getValue(symbol),
                            matchPlainSymbol = symbol in plainSymbols,
                        )
                    },
                marketMonths =
                    sources.getValue("market").jsonObject
                        .getValue("months").jsonArray
                        .map { YearMonth.parse(it.jsonPrimitive.content) },
                schedule =
                    HistoricalSchedule(
                        featureWarmupDays = schedule.getValue("featureWarmupDays").jsonPrimitive.int,
                        trainingDays = schedule.getValue("trainingDays").jsonPrimitive.int,
                        developmentDays = schedule.getValue("developmentDays").jsonPrimitive.int,
                        holdoutDays = schedule.getValue("holdoutDays").jsonPrimitive.int,
                        unusedTailDays = schedule.getValue("unusedTailDays").jsonPrimitive.int,
                    ),
            )
        }
    }
}

data class HistoricalAsset(
    val symbol: String,
    val query: String,
    val matchTerms: List<String>,
    val matchPlainSymbol: Boolean,
) {
    init {
        require(symbol.matches(Regex("[A-Z0-9]{2,12}")))
        require(query.isNotBlank())
        require(matchTerms.isNotEmpty() && matchTerms.all(String::isNotBlank))
    }
}

data class HistoricalSchedule(
    val featureWarmupDays: Int,
    val trainingDays: Int,
    val developmentDays: Int,
    val holdoutDays: Int,
    val unusedTailDays: Int,
) {
    init {
        require(
            listOf(
                featureWarmupDays,
                trainingDays,
                developmentDays,
                holdoutDays,
                unusedTailDays,
            ).all { it >= 0 },
        )
        require(trainingDays > 0 && developmentDays > 0 && holdoutDays > 0)
    }

    val totalDays =
        featureWarmupDays + trainingDays + developmentDays + holdoutDays + unusedTailDays

    fun boundaries(periodStart: Instant): HistoricalBoundaries {
        val trainingStart = periodStart.plus(Duration.ofDays(featureWarmupDays.toLong()))
        val developmentStart = trainingStart.plus(Duration.ofDays(trainingDays.toLong()))
        val holdoutStart = developmentStart.plus(Duration.ofDays(developmentDays.toLong()))
        val holdoutEnd = holdoutStart.plus(Duration.ofDays(holdoutDays.toLong()))
        return HistoricalBoundaries(trainingStart, developmentStart, holdoutStart, holdoutEnd)
    }
}

data class HistoricalBoundaries(
    val trainingStartInclusive: Instant,
    val developmentStartInclusive: Instant,
    val holdoutStartInclusive: Instant,
    val holdoutEndExclusive: Instant,
)
