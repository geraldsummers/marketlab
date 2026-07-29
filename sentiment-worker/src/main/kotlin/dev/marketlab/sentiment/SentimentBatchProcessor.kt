package dev.marketlab.sentiment

import dev.marketlab.contracts.data.InformationChannel
import dev.marketlab.contracts.data.InformationMutation
import dev.marketlab.contracts.data.PublicInformationEvent
import dev.marketlab.contracts.data.SentimentScore
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class ScoredInformationRecord(
    val schemaVersion: String = "marketlab.scored-public-information.v1",
    val source: String,
    val sourceEventId: String,
    val channel: InformationChannel,
    val mutation: InformationMutation,
    val eventTimeEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val availableAtEpochMillis: Long,
    val authorIdHash: String?,
    val language: String?,
    val textSha256: String?,
    val matchedInstruments: List<String>,
    val sourceMetrics: Map<String, Double>,
    val score: SentimentScore?,
)

@Serializable
internal data class ScoreObjectManifest(
    val schemaVersion: String = "marketlab.sentiment-score-object.v1",
    val inputSegmentSha256: String,
    val outputSha256: String,
    val outputUri: String,
    val rowCount: Long,
    val socialModelSha256: String,
    val newsModelSha256: String,
    val completedAtEpochMillis: Long,
)

internal class SentimentBatchProcessor(
    private val config: SentimentWorkerConfig,
    verifiedModels: List<VerifiedSentimentModel>,
    languageDetectorLock: LockedLanguageDetector,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val languageDetector = FrozenLanguageDetector(languageDetectorLock)
    private val social =
        OnnxSentimentModel(
            verifiedModels.single { it.lock.key == "social-twitter-roberta" },
            clock,
        )
    private val news =
        OnnxSentimentModel(
            verifiedModels.single { it.lock.key == "news-finbert" },
            clock,
        )
    private val socialIdentity = verifiedModels.single { it.lock.key == "social-twitter-roberta" }.identity
    private val newsIdentity = verifiedModels.single { it.lock.key == "news-finbert" }.identity
    private val outputObjects = config.featureRoot.resolve("scores/objects")
    private val outputManifests = config.featureRoot.resolve("scores/manifests")

    init {
        Files.createDirectories(outputObjects)
        Files.createDirectories(outputManifests)
    }

    fun processAvailable(): Int {
        val inputManifests = config.rawRoot.resolve("manifests")
        if (!Files.isDirectory(inputManifests)) return 0
        var processed = 0
        Files.walk(inputManifests).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .forEach { manifestPath ->
                    val manifest = JSON.parseToJsonElement(Files.readString(manifestPath)).jsonObject
                    val inputHash =
                        manifest["segmentSha256"]?.jsonPrimitive?.contentOrNull
                            ?: error("input manifest has no segment hash")
                    val outputManifest = outputManifests.resolve("$inputHash.json")
                    if (Files.exists(outputManifest)) return@forEach
                    val segmentUri =
                        manifest["segmentUri"]?.jsonPrimitive?.contentOrNull
                            ?: error("input manifest has no segment URI")
                    val input = config.rawRoot.resolve(segmentUri).normalize()
                    require(input.startsWith(config.rawRoot.normalize())) {
                        "input segment escapes raw root"
                    }
                    require(sha256(input) == inputHash) { "input segment hash mismatch" }
                    processOne(input, inputHash, outputManifest)
                    processed++
                }
        }
        return processed
    }

    private fun processOne(input: Path, inputHash: String, outputManifest: Path) {
        val temporary = Files.createTempFile(config.featureRoot, ".scores-", ".partial")
        var rows = 0L
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                Files.newBufferedReader(input).useLines { lines ->
                    lines.forEach { line ->
                        scoreLine(line)?.let { record ->
                            val bytes = (JSON.encodeToString(record) + "\n").toByteArray(Charsets.UTF_8)
                            channel.writeFully(bytes)
                            rows++
                        }
                    }
                }
                channel.force(true)
            }
            val outputHash = sha256(temporary)
            val directory = outputObjects.resolve(outputHash.take(2))
            Files.createDirectories(directory)
            val output = directory.resolve("$outputHash.jsonl")
            if (!Files.exists(output)) {
                try {
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    Files.deleteIfExists(temporary)
                }
            } else {
                Files.deleteIfExists(temporary)
            }
            val manifest =
                ScoreObjectManifest(
                    inputSegmentSha256 = inputHash,
                    outputSha256 = outputHash,
                    outputUri = config.featureRoot.relativize(output).toString(),
                    rowCount = rows,
                    socialModelSha256 = socialIdentity.modelSha256.hex,
                    newsModelSha256 = newsIdentity.modelSha256.hex,
                    completedAtEpochMillis = clock.instant().toEpochMilli(),
                )
            publish(
                JSON.encodeToString(manifest).toByteArray(Charsets.UTF_8),
                outputManifest,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun scoreLine(line: String): ScoredInformationRecord? {
        val root = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if (root["recordType"]?.jsonPrimitive?.contentOrNull != "information") return null
        val eventElement = root["event"] ?: return null
        val event = JSON.decodeFromJsonElement(PublicInformationEvent.serializer(), eventElement)
        val text = event.text
        val language = event.language
        if (event.mutation == InformationMutation.DELETE) {
            return ScoredInformationRecord(
                source = event.source.value,
                sourceEventId = event.sourceEventId,
                channel = event.channel,
                mutation = event.mutation,
                eventTimeEpochMillis = event.eventTime.epochMillis,
                receivedAtEpochMillis = event.receivedAt.epochMillis,
                availableAtEpochMillis = event.availableAt.epochMillis,
                authorIdHash = event.authorIdHash?.hex,
                language = event.language,
                textSha256 = null,
                matchedInstruments = emptyList(),
                sourceMetrics = event.sourceMetrics,
                score = null,
            )
        }
        if (text.isNullOrBlank()) return null
        if (event.channel == InformationChannel.SOCIAL &&
            event.matchedInstruments.isEmpty()
        ) {
            return null
        }
        if (!languageDetector.acceptsEnglish(language, text)) return null
        val model = if (event.channel == InformationChannel.SOCIAL) social else news
        val score = model.score(event.sourceEventId, text)
        return ScoredInformationRecord(
            source = event.source.value,
            sourceEventId = event.sourceEventId,
            channel = event.channel,
            mutation = event.mutation,
            eventTimeEpochMillis = event.eventTime.epochMillis,
            receivedAtEpochMillis = event.receivedAt.epochMillis,
            availableAtEpochMillis = event.availableAt.epochMillis,
            authorIdHash = event.authorIdHash?.hex,
            language = language,
            textSha256 = sha256(text.toByteArray(Charsets.UTF_8)),
            matchedInstruments = event.matchedInstruments.map { it.value },
            sourceMetrics = event.sourceMetrics,
            score = score,
        )
    }

    override fun close() {
        social.close()
        news.close()
    }

    private fun publish(bytes: ByteArray, destination: Path) {
        val temporary = destination.resolveSibling(".${UUID.randomUUID()}.partial")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.writeFully(bytes)
            channel.force(true)
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun FileChannel.writeFully(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) write(buffer)
        }
    }
}
