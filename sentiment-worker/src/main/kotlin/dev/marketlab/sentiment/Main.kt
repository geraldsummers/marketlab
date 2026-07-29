package dev.marketlab.sentiment

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main() {
    val config = SentimentWorkerConfig.fromEnvironment()
    val lock = SentimentModelLock.read(config.modelLock)
    val preprocessingHash =
        MessageDigest.getInstance("SHA-256")
            .digest(SentimentPreprocessor.SPEC.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    require(preprocessingHash == lock.preprocessingSha256) {
        "sentiment preprocessing does not match its locked identity"
    }
    val verified = lock.verify(config.modelsRoot)
    Files.createDirectories(config.featureRoot)
    val ready = config.featureRoot.resolve(".sentiment-worker-ready")
    Files.deleteIfExists(ready)
    SentimentBatchProcessor(config, verified, lock.languageDetector).use { processor ->
        val materializer = FeatureMaterializer(config)
        val programClock = SocialProgramClock(config)
        Files.writeString(
            ready,
            Json.encodeToString(
                SentimentWorkerReady(
                    schemaVersion = "marketlab.sentiment-worker-ready.v1",
                    sourceRevision = config.sourceRevision,
                    readyAtEpochMillis = System.currentTimeMillis(),
                ),
            ) + "\n",
        )
        runBlocking {
            try {
                while (true) {
                    val count = processor.processAvailable()
                    if (count > 0) LOGGER.info("Scored {} finalized information segments", count)
                    if (materializer.materializeLatestCompleteInterval()) {
                        LOGGER.info("Materialized the latest complete social-information interval")
                    }
                    programClock.tick()
                    delay(config.pollInterval.toMillis())
                }
            } catch (exception: CancellationException) {
                LOGGER.info("Sentiment worker stopping")
                throw exception
            }
        }
    }
}

@Serializable
private data class SentimentWorkerReady(
    val schemaVersion: String,
    val sourceRevision: String,
    val readyAtEpochMillis: Long,
)

private val LOGGER = LoggerFactory.getLogger("dev.marketlab.sentiment.Main")
