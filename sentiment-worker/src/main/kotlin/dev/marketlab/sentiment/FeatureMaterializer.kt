package dev.marketlab.sentiment

import dev.marketlab.contracts.data.HyperliquidUniverseSnapshot
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class FeatureObjectManifest(
    val schemaVersion: String = "marketlab.social-information-feature-manifest.v1",
    val decisionTimeEpochMillis: Long,
    val universeSelectedAtEpochMillis: Long,
    val universeSourceRevision: String,
    val outputUri: String,
    val outputSha256: String,
    val rowCount: Int,
    val inputScoreObjects: List<String>,
    val materializedAtEpochMillis: Long,
)

internal class FeatureMaterializer(
    private val config: SentimentWorkerConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val aggregator: CausalFeatureAggregator = CausalFeatureAggregator(),
) {
    private val featureObjects = config.featureRoot.resolve("features/objects")
    private val featureManifests = config.featureRoot.resolve("features/manifests")

    init {
        Files.createDirectories(featureObjects)
        Files.createDirectories(featureManifests)
    }

    fun materializeLatestCompleteInterval(): Boolean {
        val now = clock.instant().toEpochMilli()
        val decision = now - Math.floorMod(now, INTERVAL_MILLIS)
        val day = DATE.format(Instant.ofEpochMilli(decision))
        val manifestPath = featureManifests.resolve(day).resolve("$decision.json")
        if (Files.exists(manifestPath)) return false
        val universe = activeUniverse(decision) ?: return false
        val (records, inputHashes) = readScores()
        val instruments = universe.members.map { it.instrument.value }.sorted()
        val rows = aggregator.compile(records, instruments, decision)
        val bytes =
            rows.joinToString(separator = "\n", postfix = "\n") { JSON.encodeToString(it) }
                .toByteArray(Charsets.UTF_8)
        val outputHash = sha256(bytes)
        val objectDirectory = featureObjects.resolve(outputHash.take(2))
        Files.createDirectories(objectDirectory)
        val output = objectDirectory.resolve("$outputHash.jsonl")
        publish(bytes, output, replace = false)
        val manifest =
            FeatureObjectManifest(
                decisionTimeEpochMillis = decision,
                universeSelectedAtEpochMillis = universe.selectedAt.epochMillis,
                universeSourceRevision = universe.sourceRevision.hex,
                outputUri = config.featureRoot.relativize(output).toString(),
                outputSha256 = outputHash,
                rowCount = rows.size,
                inputScoreObjects = inputHashes,
                materializedAtEpochMillis = clock.instant().toEpochMilli(),
            )
        Files.createDirectories(manifestPath.parent)
        publish(JSON.encodeToString(manifest).toByteArray(Charsets.UTF_8), manifestPath, replace = false)
        publish(
            JSON.encodeToString(manifest).toByteArray(Charsets.UTF_8),
            config.featureRoot.resolve("features/latest.json"),
            replace = true,
        )
        return true
    }

    private fun activeUniverse(decision: Long): HyperliquidUniverseSnapshot? {
        if (!Files.isDirectory(config.universeRoot)) return null
        return Files.walk(config.universeRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .mapNotNull { path ->
                    runCatching {
                        JSON.decodeFromString(
                            HyperliquidUniverseSnapshot.serializer(),
                            Files.readString(path),
                        )
                    }.getOrNull()
                }
                .filter {
                    it.effectiveFrom.epochMillis <= decision &&
                        decision < it.effectiveToExclusive.epochMillis
                }
                .maxByOrNull { it.selectedAt.epochMillis }
        }
    }

    private fun readScores(): Pair<List<ScoredInformationRecord>, List<String>> {
        val records = mutableListOf<ScoredInformationRecord>()
        val hashes = mutableListOf<String>()
        val manifests = config.featureRoot.resolve("scores/manifests")
        if (!Files.isDirectory(manifests)) return records to hashes
        Files.walk(manifests).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .forEach { path ->
                    val root = JSON.parseToJsonElement(Files.readString(path)).jsonObject
                    val hash = root["outputSha256"]?.jsonPrimitive?.contentOrNull
                        ?: error("score manifest has no output hash")
                    val uri = root["outputUri"]?.jsonPrimitive?.contentOrNull
                        ?: error("score manifest has no output URI")
                    val scoreObject = config.featureRoot.resolve(uri).normalize()
                    require(scoreObject.startsWith(config.featureRoot.normalize()))
                    require(sha256(Files.readAllBytes(scoreObject)) == hash) {
                        "score object hash mismatch"
                    }
                    Files.newBufferedReader(scoreObject).useLines { lines ->
                        lines.filter(String::isNotBlank).forEach { line ->
                            records += JSON.decodeFromString<ScoredInformationRecord>(line)
                        }
                    }
                    hashes += hash
                }
        }
        return records to hashes.sorted()
    }

    private fun publish(bytes: ByteArray, destination: Path, replace: Boolean) {
        Files.createDirectories(destination.parent)
        val temporary = destination.resolveSibling(".${UUID.randomUUID()}.partial")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        val options =
            if (replace) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
        try {
            Files.move(temporary, destination, *options)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val INTERVAL_MILLIS = 15L * 60L * 1_000L
        val DATE: DateTimeFormatter =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
        val JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = false
            }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
