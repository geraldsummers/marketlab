package dev.marketlab.research

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ResearchSuiteReport(
    val schemaVersion: String,
    val suiteVersion: String,
    val generatedAtEpochMillis: Long,
    val producer: ProducerReport,
    val configuration: ReportConfiguration,
    val methodology: MethodologyReport,
    val snapshots: List<SnapshotEvidence>,
    val experiments: List<CoinExperimentReport>,
    val capacityObservations: List<CapacityObservationReport>,
    val limitations: List<String>,
)

@Serializable
data class ProducerReport(
    val sourceRevision: String,
    val sourceRevisionOrigin: String,
    val productionMode: Boolean,
    val entryPoint: String,
    val javaRuntimeVersion: String,
    val javaVmName: String,
    val javaVendor: String,
    val kotlinRuntimeVersion: String,
    val operatingSystem: String,
    val architecture: String,
)

@Serializable
data class ReportConfiguration(
    val dataRoot: String,
    val artifactRoot: String,
    val coins: List<String>,
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val deterministicSeed: Long,
    val sourceRevision: String,
    val sourceRevisionOrigin: String,
    val productionMode: Boolean,
)

@Serializable
data class MethodologyReport(
    val experimentId: String,
    val relatedRegisteredTheoryId: String,
    val relatedRegisteredTheoryPlanSha256: String,
    val adaptation: String,
    val decisionRule: String,
    val target: String,
    val candidateEstimator: String,
    val controls: List<String>,
    val minimumTrainingRows: Int,
    val testRows: Int,
    val stepRows: Int,
    val purgeMillis: Long,
    val embargoMillis: Long,
    val hacLag: Int,
    val researchAdequacyRows: Int,
    val predictiveAlpha: Double,
    val historicalExecutionEvaluated: Boolean,
    val fabricatedFills: Boolean,
)

@Serializable
data class SnapshotEvidence(
    val coin: String,
    val observation: String,
    val snapshotId: String,
    val snapshotManifestSha256: String,
    val createdAtEpochMillis: Long,
    val qualityCheckedAtEpochMillis: Long,
    val qualityUsable: Boolean,
    val qualityIssues: List<QualityIssueEvidence>,
    val rawObjects: List<RawObjectEvidence>,
)

@Serializable
data class QualityIssueEvidence(
    val kind: String,
    val severity: String,
    val message: String,
)

@Serializable
data class RawObjectEvidence(
    val contentSha256: String,
    val uri: String,
    val byteCount: Long,
    val rowCount: Long,
    val eventFromInclusiveEpochMillis: Long,
    val eventToExclusiveEpochMillis: Long,
    val availableFromInclusiveEpochMillis: Long,
    val availableToExclusiveEpochMillis: Long,
    val source: String,
    val production: Boolean,
    val retrievedAtEpochMillis: Long,
    val requestMethod: String,
    val requestUri: String,
    val requestParameters: Map<String, String>,
    val schemaVersion: String,
    val adapterVersion: String,
)

@Serializable
data class CoinExperimentReport(
    val coin: String,
    val sample: SampleAdequacyReport,
    val candidate: CandidateReport?,
    val evidenceStatus: EvidenceStatus,
    val promotionStatus: PromotionStatus,
    val promotionExplanation: String,
)

@Serializable
data class SampleAdequacyReport(
    val requestedHourlyRows: Int,
    val completeAlignedRows: Int,
    val minimumRowsForOneFold: Int,
    val researchAdequacyRows: Int,
    val emittedFolds: Int,
    val outOfSampleRows: Int,
    val adequateForResearchScreen: Boolean,
    val explanation: String,
)

@Serializable
data class CandidateReport(
    val feature: String,
    val candidateMetrics: ForecastMetricReport,
    val comparisons: List<ControlComparisonReport>,
    val forecasts: List<ForecastRecord>,
)

@Serializable
data class ForecastMetricReport(
    val count: Int,
    val meanAbsoluteError: Double,
    val rootMeanSquaredError: Double,
    val directionalAccuracy: Double,
)

@Serializable
data class ControlComparisonReport(
    val control: String,
    val controlMetrics: ForecastMetricReport,
    val meanSquaredErrorImprovement: Double,
    val hacStandardError: Double,
    val zStatistic: String,
    val twoSidedPValue: Double,
    val hacLag: Int,
)

@Serializable
data class ForecastRecord(
    val fold: Int,
    val rowId: String,
    val decisionTimeEpochMillis: Long,
    val actualLogReturn: Double,
    val candidateForecast: Double,
    val zeroReturnForecast: Double,
    val historicalMeanForecast: Double,
)

@Serializable
enum class EvidenceStatus {
    NOT_RUN_INSUFFICIENT_ROWS,
    INCONCLUSIVE,
    PREDICTIVE_SCREEN_PASSED,
}

@Serializable
enum class PromotionStatus {
    INCONCLUSIVE_INSUFFICIENT_SAMPLE,
    INCONCLUSIVE_PREDICTIVE_TEST_FAILED,
    INCONCLUSIVE_HISTORICAL_EXECUTION_NOT_TESTED,
}

@Serializable
data class CapacityObservationReport(
    val coin: String,
    val classification: String,
    val historical: Boolean,
    val representsExecutionOrFill: Boolean,
    val exchangeTimeEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val availableAtEpochMillis: Long,
    val rawBookSha256: String,
    val maximumDisplayedDepthFraction: String,
    val sweeps: List<CapacitySweepReport>,
)

@Serializable
data class CapacitySweepReport(
    val side: String,
    val requestedNotional: String,
    val filledNotional: String,
    val visibleQuantity: String,
    val vwap: String?,
    val referencePrice: String,
    val impactBasisPoints: Double?,
    val completeWithinObservedDepth: Boolean,
)

data class WrittenReportArtifact(
    val reportPath: Path,
    val checksumPath: Path,
    val sha256: String,
    val byteCount: Long,
)

internal object CanonicalReportJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun encode(report: ResearchSuiteReport): ByteArray =
        encode(ResearchSuiteReport.serializer(), report)

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val element = json.encodeToJsonElement(serializer, value)
        return json.encodeToString(JsonElement.serializer(), canonicalize(element))
            .toByteArray(Charsets.UTF_8)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .sortedBy { it.key }
                    .associate { (key, value) -> key to canonicalize(value) },
            )
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }
}

internal class ReportArtifactWriter(root: Path) {
    private val root = root.toAbsolutePath().normalize()
    private val reportsRoot = this.root.resolve("research-reports")

    init {
        Files.createDirectories(reportsRoot)
    }

    fun write(report: ResearchSuiteReport): WrittenReportArtifact {
        val bytes = CanonicalReportJson.encode(report)
        val hash = CanonicalReportJson.sha256(bytes)
        val reportPath = reportsRoot.resolve("$hash.json")
        val checksumPath = reportsRoot.resolve("$hash.json.sha256")
        writeImmutable(reportPath, bytes)
        writeImmutable(
            checksumPath,
            "$hash  ${reportPath.fileName}\n".toByteArray(Charsets.UTF_8),
        )
        return WrittenReportArtifact(
            reportPath = reportPath,
            checksumPath = checksumPath,
            sha256 = hash,
            byteCount = bytes.size.toLong(),
        )
    }

    private fun writeImmutable(target: Path, bytes: ByteArray) {
        require(target.toAbsolutePath().normalize().startsWith(root)) {
            "report target escapes artifact root"
        }
        if (Files.exists(target)) {
            check(Files.readAllBytes(target).contentEquals(bytes)) {
                "immutable report artifact already exists with different content"
            }
            return
        }
        Files.createDirectories(target.parent)
        val partial = target.parent.resolve(".${target.fileName}.${UUID.randomUUID()}.partial")
        try {
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                check(Files.readAllBytes(target).contentEquals(bytes)) {
                    "immutable report artifact concurrently created with different content"
                }
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IOException("artifact filesystem does not support atomic rename", exception)
            }
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
