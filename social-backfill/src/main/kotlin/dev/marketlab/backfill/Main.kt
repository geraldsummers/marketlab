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
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking

fun main(arguments: Array<String>) {
    if (arguments.firstOrNull() == "functional-features") {
        runFunctionalFeatures(arguments.drop(1).toTypedArray())
        return
    }
    val config = BackfillConfig.parse(arguments)
    Files.createDirectories(config.outputRoot)
    val store = ImmutableEvidenceStore(config.outputRoot)
    verifyRegistration(config, store)
    if ("analysis" in config.sources) {
        RetrospectiveAnalysis(config, store).run()
    }
    if (config.sources.none { it == "market" || it == "social" }) return
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
                val modelLock = SentimentModelLock.read(config.modelLock)
                val verified = modelLock.verify(config.modelsRoot)
                val socialIdentity =
                    verified.single { it.lock.key == "social-twitter-roberta" }
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

private fun runFunctionalFeatures(arguments: Array<String>) {
    require(arguments.size % 2 == 0) { "functional feature arguments must be --key value pairs" }
    val expected = setOf("output-root", "program-lock", "feature-lock", "start", "end")
    val values = linkedMapOf<String, String>()
    arguments.toList().chunked(2).forEach { pair ->
        require(pair[0].startsWith("--")) { "functional feature arguments must be --key value pairs" }
        val key = pair[0].removePrefix("--")
        require(key in expected) { "unknown functional feature argument: $key" }
        require(values.put(key, pair[1]) == null) { "duplicate functional feature argument: $key" }
    }
    fun required(name: String) = requireNotNull(values[name]) { "--$name is required" }
    val outputRoot = Path.of(required("output-root")).toAbsolutePath().normalize()
    val config =
        FunctionalFeatureConfig(
            outputRoot = outputRoot,
            programLock = Path.of(required("program-lock")).toAbsolutePath().normalize(),
            featureLock = Path.of(required("feature-lock")).toAbsolutePath().normalize(),
            start = Instant.parse(required("start")),
            endExclusive = Instant.parse(required("end")),
        )
    Files.createDirectories(outputRoot)
    FunctionalFeatureMaterializer(config, ImmutableEvidenceStore(outputRoot)).run()
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
