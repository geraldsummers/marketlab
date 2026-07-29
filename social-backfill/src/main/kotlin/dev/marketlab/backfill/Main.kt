package dev.marketlab.backfill

import dev.marketlab.sentiment.FrozenLanguageDetector
import dev.marketlab.sentiment.OnnxSentimentModel
import dev.marketlab.sentiment.SentimentModelLock
import dev.marketlab.evidence.ImmutableEvidenceStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

fun main(arguments: Array<String>) {
    val config = BackfillConfig.parse(arguments)
    Files.createDirectories(config.outputRoot)
    val store = ImmutableEvidenceStore(config.outputRoot)
    verifyRegistration(config, store)
    if ("analysis" in config.sources) {
        RetrospectiveAnalysis(config, store).run()
    }
    if (config.sources.none { it == "market" || it == "social" }) return
    val modelLock = SentimentModelLock.read(config.modelLock)
    val verified = modelLock.verify(config.modelsRoot)
    val socialIdentity = verified.single { it.lock.key == "social-twitter-roberta" }
    val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000L
                requestTimeoutMillis = 180_000L
                socketTimeoutMillis = 180_000L
            }
            install(UserAgent) {
                agent = "MarketlabRetrospectiveResearch/1.0"
            }
            expectSuccess = true
        }
    try {
        runBlocking {
            if ("market" in config.sources) {
                BinanceMarketBackfill(config, client, store).run()
            }
            if ("social" in config.sources) {
                OnnxSentimentModel(socialIdentity).use { model ->
                    BlueskyBackfill(
                        config = config,
                        client = client,
                        store = store,
                        model = model,
                        languageDetector = FrozenLanguageDetector(modelLock.languageDetector),
                    ).run()
                }
            }
        }
    } finally {
        client.close()
    }
}

private fun verifyRegistration(config: BackfillConfig, store: ImmutableEvidenceStore) {
    val bytes = Files.readAllBytes(config.programLock)
    val frozen = config.outputRoot.resolve("program-lock.json")
    if (Files.isRegularFile(frozen)) {
        require(store.sha256(frozen) == store.sha256(bytes)) {
            "backfill program lock changed after output creation"
        }
    } else {
        store.publish(bytes, frozen)
    }
}
