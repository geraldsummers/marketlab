package dev.marketlab.backfill

import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.evidence.ImmutableEvidenceStore
import dev.marketlab.engine.FeatureVector
import dev.marketlab.engine.ForecastMetrics
import dev.marketlab.engine.LabeledObservation
import dev.marketlab.engine.LinearEstimator
import dev.marketlab.engine.LinearFittedEstimator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.TreeMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hipparchus.special.Erf

internal class RetrospectiveAnalysis(
    private val config: BackfillConfig,
    private val store: ImmutableEvidenceStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val assets = config.study.assets
    private val boundaries = config.study.schedule.boundaries(config.study.startInclusive)
    private val trainingStart = boundaries.trainingStartInclusive.toEpochMilli()
    private val developmentStart = boundaries.developmentStartInclusive.toEpochMilli()
    private val holdoutStart = boundaries.holdoutStartInclusive.toEpochMilli()
    private val holdoutEnd = boundaries.holdoutEndExclusive.toEpochMilli()

    fun run() {
        val reportPath = store.root.resolve("analysis/report.json")
        if (Files.isRegularFile(reportPath)) {
            println("analysis already complete: $reportPath")
            return
        }
        val analysisLock = requireNotNull(config.analysisLock)
        verifyAnalysisLock(analysisLock)
        val acquisition = loadAcquisition()
        val social = acquisition.social.mapValues { (_, rows) -> filterSocial(rows) }
        val market = acquisition.market

        val attentionRows = hourlyRows(social, market, SocialFeature.ATTENTION)
        val disagreementRows = hourlyRows(social, market, SocialFeature.DISAGREEMENT)
        val polarityRows = polarityRows(social, market)
        val crossSectionRows = crossSectionRows(social, market)

        val results =
            mutableListOf(
                evaluate(
                    id = ATTENTION_ID,
                    rows = attentionRows,
                    candidateFeatures = listOf("log_rv_1h", "log_rv_24h", "social"),
                    controlFeatures = listOf("log_rv_1h", "log_rv_24h"),
                    socialFeature = "social",
                    lossKind = LossKind.QLIKE,
                    hacLag = 24,
                ),
                evaluate(
                    id = DISAGREEMENT_ID,
                    rows = disagreementRows,
                    candidateFeatures = listOf("log_rv_1h", "log_rv_24h", "social"),
                    controlFeatures = listOf("log_rv_1h", "log_rv_24h"),
                    socialFeature = "social",
                    lossKind = LossKind.QLIKE,
                    hacLag = 24,
                ),
                evaluate(
                    id = POLARITY_ID,
                    rows = polarityRows,
                    candidateFeatures = listOf("latest_return", "btc_return", "social"),
                    controlFeatures = listOf("latest_return", "btc_return"),
                    socialFeature = "social",
                    lossKind = LossKind.SQUARED_ERROR,
                    hacLag = 96,
                    additionalBaselines = true,
                ),
                evaluate(
                    id = CROSS_SECTION_ID,
                    rows = crossSectionRows,
                    candidateFeatures = listOf("momentum", "social"),
                    controlFeatures = listOf("momentum"),
                    socialFeature = "social",
                    lossKind = LossKind.SQUARED_ERROR,
                    hacLag = 7,
                    portfolioDiagnostic = true,
                ),
            )
        val adjusted = holm(results.map(TheoryScreenResult::primaryPValue))
        val corrected =
            results.mapIndexed { index, result ->
                val beatsAdditionalBaselines =
                    result.additionalBaselineMeanLosses
                        .filterKeys { it != "candidate" }
                        .values
                        .all { result.candidateMeanLoss < it }
                val portfolioPasses =
                    result.theoryId != CROSS_SECTION_ID ||
                        (result.grossTopMinusBottomMeanReturn ?: Double.NEGATIVE_INFINITY) > 0.0
                result.copy(
                    holmAdjustedPValue = adjusted[index],
                    passesDiscoveryScreen =
                        result.socialCoefficient > 0.0 &&
                            result.holdoutLossImprovement > 0.0 &&
                            adjusted[index] < 0.05 &&
                            beatsAdditionalBaselines &&
                            portfolioPasses,
                )
            }
        val report =
            RetrospectiveAnalysisReport(
                acquisitionProgramSha256 = store.sha256(config.programLock),
                analysisLockSha256 = store.sha256(analysisLock),
                inputManifestSetSha256 = acquisition.manifestSetSha256,
                completedAtEpochMillis = clock.instant().toEpochMilli(),
                socialObservationCounts = social.mapValues { it.value.size }.toSortedMap(),
                marketBarCounts = market.mapValues { it.value.size }.toSortedMap(),
                results = corrected,
            )
        val bytes = JSON.encodeToString(report).toByteArray()
        val objectRef = store.storeObject(bytes, "json")
        val envelope =
            AnalysisEnvelope(
                reportUri = objectRef.uri,
                reportSha256 = objectRef.sha256,
                analysisLockSha256 = report.analysisLockSha256,
                inputManifestSetSha256 = report.inputManifestSetSha256,
            )
        store.publish(JSON.encodeToString(envelope).toByteArray(), reportPath)
        println("analysis complete report=${objectRef.uri} sha256=${objectRef.sha256}")
    }

    private fun verifyAnalysisLock(path: Path) {
        val bytes = Files.readAllBytes(path)
        val root = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(
            root.getValue("schemaVersion").jsonPrimitive.content ==
                "marketlab.social-backfill-analysis-lock.v1",
        )
        require(
            root.getValue("acquisitionProgramId").jsonPrimitive.content ==
                config.study.programId,
        )
        val frozen = store.root.resolve("analysis-lock.json")
        if (Files.isRegularFile(frozen)) {
            require(store.sha256(frozen) == store.sha256(bytes)) {
                "analysis lock changed after analysis output creation"
            }
        } else {
            store.publish(bytes, frozen)
        }
    }

    private fun loadAcquisition(): Acquisition {
        val social = linkedMapOf<String, List<SocialObservation>>()
        val market = linkedMapOf<String, TreeMap<Long, MarketBar15m>>()
        val manifestHashes = mutableListOf<String>()
        for (asset in assets) {
            val marketManifestPath = store.root.resolve("market/manifests/${asset.symbol}.json")
            require(Files.isRegularFile(marketManifestPath)) {
                "missing market manifest for ${asset.symbol}"
            }
            manifestHashes += "market/${asset.symbol}:${store.sha256(marketManifestPath)}"
            val marketManifest =
                JSON.decodeFromString<MarketAssetManifest>(Files.readString(marketManifestPath))
            val marketBytes = verifiedObject(marketManifest.outputUri, marketManifest.outputSha256)
            val bars =
                marketBytes.decodeToString().lineSequence()
                    .filter(String::isNotBlank)
                    .map { JSON.decodeFromString<MarketBar15m>(it) }
                    .associateByTo(TreeMap(), MarketBar15m::openTimeEpochMillis)
            require(bars.size == marketManifest.barCount)
            market[asset.symbol] = bars

            val rows = mutableListOf<SocialObservation>()
            for (date in dates(config.start, config.endExclusive)) {
                val manifestPath =
                    store.root.resolve("social/manifests/${asset.symbol}/$date.json")
                require(Files.isRegularFile(manifestPath)) {
                    "social acquisition is incomplete: ${asset.symbol} $date"
                }
                manifestHashes +=
                    "social/${asset.symbol}/$date:${store.sha256(manifestPath)}"
                val manifest =
                    JSON.decodeFromString<SocialDayManifest>(Files.readString(manifestPath))
                val bytes = verifiedObject(manifest.outputUri, manifest.outputSha256)
                val dayRows =
                    bytes.decodeToString().lineSequence()
                        .filter(String::isNotBlank)
                        .map { JSON.decodeFromString<SocialObservation>(it) }
                        .toList()
                require(dayRows.size == manifest.observationCount)
                rows += dayRows
            }
            social[asset.symbol] = rows
        }
        val manifestDigest = store.sha256(manifestHashes.sorted().joinToString("\n").toByteArray())
        return Acquisition(social, market, manifestDigest)
    }

    private fun verifiedObject(uri: String, expectedHash: String): ByteArray {
        val path = store.root.resolve(uri).normalize()
        require(path.startsWith(store.root.normalize())) { "object URI escapes backfill root" }
        require(Files.isRegularFile(path)) { "missing content-addressed object: $uri" }
        require(store.sha256(path) == expectedHash) { "object hash mismatch: $uri" }
        return Files.readAllBytes(path)
    }

    private fun filterSocial(rows: List<SocialObservation>): List<SocialObservation> {
        val lastByText = mutableMapOf<String, Long>()
        val authorCounts = mutableMapOf<String, Int>()
        return rows
            .sortedWith(
                compareBy(
                    SocialObservation::indexedAtAvailabilityProxyEpochMillis,
                    SocialObservation::sourceEventId,
                ),
            )
            .filter { row ->
                val at = row.indexedAtAvailabilityProxyEpochMillis
                val prior = lastByText[row.textSha256]
                if (prior != null && at - prior < DAY) {
                    false
                } else {
                    lastByText[row.textSha256] = at
                    val authorHour = "${row.authorIdSha256}:${at / HOUR}"
                    val next = (authorCounts[authorHour] ?: 0) + 1
                    authorCounts[authorHour] = next
                    next <= 3
                }
            }
    }

    private fun hourlyRows(
        social: Map<String, List<SocialObservation>>,
        market: Map<String, TreeMap<Long, MarketBar15m>>,
        socialFeature: SocialFeature,
    ): List<ScreenRow> {
        val output = mutableListOf<ScreenRow>()
        for (asset in assets) {
            val bars = market.getValue(asset.symbol)
            val byHour =
                social.getValue(asset.symbol).groupBy {
                    completedBucket(it.indexedAtAvailabilityProxyEpochMillis, HOUR)
                }
            var decision = trainingStart
            while (decision < holdoutEnd) {
                val trailing1h = variance(bars, decision - HOUR, decision, 4)
                val trailing24h = variance(bars, decision - DAY, decision, 96)
                val target = variance(bars, decision, decision + HOUR, 4)
                if (trailing1h != null && trailing24h != null && target != null) {
                    val priorRows = byHour[decision - HOUR].orEmpty()
                    val feature =
                        when (socialFeature) {
                            SocialFeature.ATTENTION -> {
                                val history =
                                    (1..720).map { offset ->
                                        byHour[decision - HOUR - offset * HOUR].orEmpty().size.toDouble()
                                    }
                                zScore(priorRows.size.toDouble(), history)
                            }
                            SocialFeature.DISAGREEMENT ->
                                priorRows.map(SocialObservation::polarity)
                                    .takeIf { it.size >= 2 }
                                    ?.let(::sampleStandardDeviation)
                        }
                    if (feature != null) {
                        output +=
                            ScreenRow(
                                decision,
                                asset.symbol,
                                mapOf(
                                    "log_rv_1h" to ln(trailing1h.coerceAtLeast(EPSILON)),
                                    "log_rv_24h" to ln(trailing24h.coerceAtLeast(EPSILON)),
                                    "social" to feature,
                                ),
                                ln(target.coerceAtLeast(EPSILON)),
                                target,
                            )
                    }
                }
                decision += HOUR
            }
        }
        return output.sortedWith(compareBy(ScreenRow::decisionTime, ScreenRow::symbol))
    }

    private fun polarityRows(
        social: Map<String, List<SocialObservation>>,
        market: Map<String, TreeMap<Long, MarketBar15m>>,
    ): List<ScreenRow> {
        val output = mutableListOf<ScreenRow>()
        val btc = market.getValue("BTC")
        for (asset in assets) {
            val bars = market.getValue(asset.symbol)
            val byBucket =
                social.getValue(asset.symbol).groupBy {
                    completedBucket(it.indexedAtAvailabilityProxyEpochMillis, QUARTER_HOUR)
                }
            var decision = trainingStart
            while (decision < holdoutEnd) {
                val latest = bars[decision - QUARTER_HOUR]
                val btcLatest = btc[decision - QUARTER_HOUR]
                val target = bars[decision]
                val polarity = byBucket[decision - QUARTER_HOUR].orEmpty().map(SocialObservation::polarity)
                if (latest != null && btcLatest != null && target != null && polarity.isNotEmpty()) {
                    output +=
                        ScreenRow(
                            decision,
                            asset.symbol,
                            mapOf(
                                "latest_return" to barReturn(latest),
                                "btc_return" to barReturn(btcLatest),
                                "social" to polarity.average(),
                            ),
                            barReturn(target),
                        )
                }
                decision += QUARTER_HOUR
            }
        }
        return output.sortedWith(compareBy(ScreenRow::decisionTime, ScreenRow::symbol))
    }

    private fun crossSectionRows(
        social: Map<String, List<SocialObservation>>,
        market: Map<String, TreeMap<Long, MarketBar15m>>,
    ): List<ScreenRow> {
        val counts =
            social.mapValues { (_, rows) ->
                rows.groupingBy {
                    completedBucket(it.indexedAtAvailabilityProxyEpochMillis, DAY)
                }.eachCount()
            }
        val output = mutableListOf<ScreenRow>()
        var decision = trainingStart
        while (decision < holdoutEnd) {
            val candidates =
                assets.mapNotNull { asset ->
                    val bars = market.getValue(asset.symbol)
                    val history =
                        (1..30).map { offset ->
                            counts.getValue(asset.symbol)[decision - DAY - offset * DAY]
                                ?.toDouble() ?: 0.0
                        }
                    val abnormal =
                        zScore(
                            counts.getValue(asset.symbol)[decision - DAY]?.toDouble() ?: 0.0,
                            history,
                        ) ?: return@mapNotNull null
                    val momentum = dailyReturn(bars, decision - DAY) ?: return@mapNotNull null
                    val target = dailyReturn(bars, decision) ?: return@mapNotNull null
                    DailyCandidate(asset.symbol, abnormal, momentum, target)
                }
            if (candidates.size >= 8) {
                val ranks = ranks(candidates.associate { it.symbol to it.abnormalAttention })
                candidates.forEach { row ->
                    output +=
                        ScreenRow(
                            decision,
                            row.symbol,
                            mapOf("momentum" to row.momentum, "social" to ranks.getValue(row.symbol)),
                            row.target,
                        )
                }
            }
            decision += DAY
        }
        return output.sortedWith(compareBy(ScreenRow::decisionTime, ScreenRow::symbol))
    }

    private fun evaluate(
        id: String,
        rows: List<ScreenRow>,
        candidateFeatures: List<String>,
        controlFeatures: List<String>,
        socialFeature: String,
        lossKind: LossKind,
        hacLag: Int,
        additionalBaselines: Boolean = false,
        portfolioDiagnostic: Boolean = false,
    ): TheoryScreenResult {
        require(rows.isNotEmpty()) { "$id emitted no complete rows" }
        val developmentImprovements = mutableListOf<Double>()
        repeat(4) { fold ->
            val testStart = developmentStart + fold * 14L * DAY
            val testEnd = testStart + 14L * DAY
            val training = rows.filter { it.decisionTime < testStart }
            val test = rows.filter { it.decisionTime >= testStart && it.decisionTime < testEnd }
            require(training.isNotEmpty() && test.isNotEmpty()) { "$id development fold $fold is empty" }
            val candidate = fit(training, candidateFeatures)
            val control = fit(training, controlFeatures)
            val candidateLoss = losses(test, candidate, lossKind)
            val controlLoss = losses(test, control, lossKind)
            developmentImprovements +=
                controlLoss.zip(candidateLoss) { base, proposed -> base - proposed }.average()
        }
        val fitting = rows.filter { it.decisionTime < holdoutStart }
        val holdout =
            rows.filter { it.decisionTime >= holdoutStart && it.decisionTime < holdoutEnd }
        require(fitting.isNotEmpty() && holdout.isNotEmpty()) { "$id holdout is empty" }
        val candidate = fit(fitting, candidateFeatures)
        val control = fit(fitting, controlFeatures)
        val candidateLoss = losses(holdout, candidate, lossKind)
        val controlLoss = losses(holdout, control, lossKind)
        val differential = controlLoss.zip(candidateLoss) { base, proposed -> base - proposed }
        val bucketDifferential =
            holdout.zip(differential)
                .groupBy({ it.first.decisionTime }, { it.second })
                .toSortedMap()
                .values
                .map(List<Double>::average)
        val standardError =
            ForecastMetrics.hacMeanStandardError(
                bucketDifferential,
                hacLag.coerceAtMost(bucketDifferential.size - 1),
            )
        val pValue =
            if (standardError == 0.0) {
                if (bucketDifferential.average() == 0.0) 1.0 else 0.0
            } else {
                twoSidedPValue(bucketDifferential.average() / standardError)
            }
        val extras =
            if (additionalBaselines) {
                returnBaselineLosses(fitting, holdout, candidateLoss.average())
            } else {
                emptyMap()
            }
        val portfolio =
            if (portfolioDiagnostic) crossSectionPortfolio(holdout) else null
        val coefficientIndex =
            candidate.featureNames.indexOf(socialFeature) + if (candidate.intercept) 1 else 0
        return TheoryScreenResult(
            theoryId = id,
            developmentFoldLossImprovements = developmentImprovements,
            fittingRows = fitting.size,
            holdoutRows = holdout.size,
            holdoutDecisionBuckets = holdout.map(ScreenRow::decisionTime).distinct().size,
            candidateMeanLoss = candidateLoss.average(),
            primaryControlMeanLoss = controlLoss.average(),
            holdoutLossImprovement = differential.average(),
            socialCoefficient = candidate.coefficients[coefficientIndex],
            primaryPValue = pValue,
            holmAdjustedPValue = 1.0,
            passesDiscoveryScreen = false,
            additionalBaselineMeanLosses = extras,
            grossTopMinusBottomMeanReturn = portfolio,
        )
    }

    private fun fit(rows: List<ScreenRow>, features: List<String>): LinearFittedEstimator {
        val dummySymbols = rows.map(ScreenRow::symbol).distinct().sorted().filterNot { it == "BTC" }
        val names = features + dummySymbols.map { "symbol_$it" }
        val observations =
            rows.map { row ->
                val values =
                    buildMap {
                        features.forEach { feature -> put(feature, row.features.getValue(feature)) }
                        dummySymbols.forEach { symbol ->
                            put("symbol_$symbol", if (row.symbol == symbol) 1.0 else 0.0)
                        }
                    }
                val vector = FeatureVector("${row.symbol}:${row.decisionTime}", values)
                LabeledObservation(
                    rowId = vector.rowId,
                    decisionTime = MarketTimestamp(row.decisionTime),
                    labelFrom = MarketTimestamp(row.decisionTime),
                    labelTo = MarketTimestamp(row.decisionTime + 1),
                    features = vector,
                    label = row.label,
                )
            }
        return LinearEstimator(names).fit(observations) as LinearFittedEstimator
    }

    private fun losses(
        rows: List<ScreenRow>,
        model: LinearFittedEstimator,
        kind: LossKind,
    ): List<Double> =
        rows.map { row ->
            val values =
                buildMap {
                    model.featureNames.forEach { feature ->
                        put(
                            feature,
                            if (feature.startsWith("symbol_")) {
                                if (row.symbol == feature.removePrefix("symbol_")) 1.0 else 0.0
                            } else {
                                row.features.getValue(feature)
                            },
                        )
                    }
                }
            val prediction = model.predict(FeatureVector("${row.symbol}:${row.decisionTime}", values))
            when (kind) {
                LossKind.SQUARED_ERROR -> (row.label - prediction) * (row.label - prediction)
                LossKind.QLIKE -> {
                    val forecast = exp(prediction.coerceIn(-30.0, 30.0))
                    checkNotNull(row.realizedVariance) / forecast + ln(forecast)
                }
            }
        }

    private fun returnBaselineLosses(
        fitting: List<ScreenRow>,
        holdout: List<ScreenRow>,
        candidateMeanLoss: Double,
    ): Map<String, Double> {
        val meanByAsset = fitting.groupBy(ScreenRow::symbol).mapValues { it.value.map(ScreenRow::label).average() }
        val baselines =
            linkedMapOf(
                "candidate" to candidateMeanLoss,
                "training_asset_mean" to
                    holdout.map { row ->
                        val error = row.label - meanByAsset.getValue(row.symbol)
                        error * error
                    }.average(),
                "zero_return" to holdout.map { it.label * it.label }.average(),
                "latest_return" to
                    holdout.map {
                        val error = it.label - it.features.getValue("latest_return")
                        error * error
                    }.average(),
                "btc_return" to
                    holdout.map {
                        val error = it.label - it.features.getValue("btc_return")
                        error * error
                    }.average(),
            )
        return baselines
    }

    private fun crossSectionPortfolio(rows: List<ScreenRow>): Double? {
        val daily =
            rows.groupBy(ScreenRow::decisionTime).mapNotNull { (_, dayRows) ->
                if (dayRows.size < 8) return@mapNotNull null
                val ordered = dayRows.sortedBy { it.features.getValue("social") }
                ordered.takeLast(3).map(ScreenRow::label).average() -
                    ordered.take(3).map(ScreenRow::label).average()
            }
        return daily.takeIf(List<Double>::isNotEmpty)?.average()
    }

    private fun variance(
        bars: TreeMap<Long, MarketBar15m>,
        start: Long,
        endExclusive: Long,
        expected: Int,
    ): Double? {
        val selected = bars.subMap(start, true, endExclusive, false).values
        if (selected.size != expected) return null
        return selected.sumOf(MarketBar15m::realizedVariance1m)
    }

    private fun dailyReturn(bars: TreeMap<Long, MarketBar15m>, start: Long): Double? {
        val selected = bars.subMap(start, true, start + DAY, false).values.toList()
        if (selected.size != 96) return null
        return ln(selected.last().close / selected.first().open)
    }

    private fun barReturn(bar: MarketBar15m): Double = ln(bar.close / bar.open)

    private fun zScore(value: Double, history: List<Double>): Double? {
        val standardDeviation = sampleStandardDeviation(history)
        return if (standardDeviation == 0.0) null else (value - history.average()) / standardDeviation
    }

    private fun sampleStandardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun ranks(values: Map<String, Double>): Map<String, Double> {
        val ordered = values.entries.sortedWith(compareBy({ it.value }, { it.key }))
        return ordered
            .groupBy(Map.Entry<String, Double>::value)
            .flatMap { (_, tied) ->
                val averageIndex = tied.map { ordered.indexOf(it) }.average()
                tied.map {
                    it.key to if (ordered.size == 1) 0.5 else averageIndex / (ordered.size - 1)
                }
            }.toMap()
    }

    private fun holm(pValues: List<Double>): List<Double> {
        val ranked = pValues.withIndex().sortedBy { it.value }
        val adjusted = DoubleArray(pValues.size)
        var running = 0.0
        ranked.forEachIndexed { rank, indexed ->
            running = maxOf(running, indexed.value * (pValues.size - rank))
            adjusted[indexed.index] = running.coerceAtMost(1.0)
        }
        return adjusted.toList()
    }

    private fun twoSidedPValue(z: Double): Double =
        (2.0 * (1.0 - 0.5 * (1.0 + Erf.erf(kotlin.math.abs(z) / sqrt(2.0)))))
            .coerceIn(0.0, 1.0)

    private fun completedBucket(availability: Long, duration: Long): Long =
        Math.floorDiv(availability - 1L, duration) * duration

    private data class Acquisition(
        val social: Map<String, List<SocialObservation>>,
        val market: Map<String, TreeMap<Long, MarketBar15m>>,
        val manifestSetSha256: String,
    )

    private data class ScreenRow(
        val decisionTime: Long,
        val symbol: String,
        val features: Map<String, Double>,
        val label: Double,
        val realizedVariance: Double? = null,
    )

    private data class DailyCandidate(
        val symbol: String,
        val abnormalAttention: Double,
        val momentum: Double,
        val target: Double,
    )

    private enum class SocialFeature { ATTENTION, DISAGREEMENT }
    private enum class LossKind { QLIKE, SQUARED_ERROR }

    private companion object {
        const val ATTENTION_ID = "retrospective-single-source-social-attention-hourly-variance"
        const val DISAGREEMENT_ID = "retrospective-single-source-social-disagreement-hourly-variance"
        const val POLARITY_ID = "retrospective-single-source-social-polarity-fifteen-minute-return"
        const val CROSS_SECTION_ID = "retrospective-cross-sectional-social-attention-daily-return"
        const val QUARTER_HOUR = 15L * 60L * 1_000L
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
        const val EPSILON = 1.0e-12
        val JSON =
            Json {
                ignoreUnknownKeys = false
                encodeDefaults = true
                explicitNulls = false
                prettyPrint = true
            }
    }
}

@Serializable
internal data class TheoryScreenResult(
    val theoryId: String,
    val developmentFoldLossImprovements: List<Double>,
    val fittingRows: Int,
    val holdoutRows: Int,
    val holdoutDecisionBuckets: Int,
    val candidateMeanLoss: Double,
    val primaryControlMeanLoss: Double,
    val holdoutLossImprovement: Double,
    val socialCoefficient: Double,
    val primaryPValue: Double,
    val holmAdjustedPValue: Double,
    val passesDiscoveryScreen: Boolean,
    val additionalBaselineMeanLosses: Map<String, Double>,
    val grossTopMinusBottomMeanReturn: Double?,
)

@Serializable
internal data class RetrospectiveAnalysisReport(
    val schemaVersion: String = "marketlab.social-backfill-analysis-report.v1",
    val classification: String = "RETROSPECTIVE_DISCOVERY_ONLY",
    val acquisitionProgramSha256: String,
    val analysisLockSha256: String,
    val inputManifestSetSha256: String,
    val completedAtEpochMillis: Long,
    val socialObservationCounts: Map<String, Int>,
    val marketBarCounts: Map<String, Int>,
    val results: List<TheoryScreenResult>,
    val socialSampleLimitation: String =
        "Bounded Bluesky historical search capture; archive completeness cannot be proven.",
    val executionCostsEvaluated: Boolean = false,
    val hyperliquidConfirmationClaimAllowed: Boolean = false,
    val paperTradingPromotionAllowed: Boolean = false,
    val liveTradingAuthorized: Boolean = false,
)

@Serializable
internal data class AnalysisEnvelope(
    val schemaVersion: String = "marketlab.social-backfill-analysis-envelope.v1",
    val reportUri: String,
    val reportSha256: String,
    val analysisLockSha256: String,
    val inputManifestSetSha256: String,
)
