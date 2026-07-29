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
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    val lock = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
    require(
        lock.getValue("schemaVersion").jsonPrimitive.content ==
            "marketlab.social-backfill-program-lock.v1",
    )
    val period = lock.getValue("period").jsonObject
    require(period.getValue("startInclusive").jsonPrimitive.content == config.start.toString())
    require(period.getValue("endExclusive").jsonPrimitive.content == config.endExclusive.toString())
    val symbols =
        lock.getValue("universe").jsonObject
            .getValue("symbols").jsonArray
            .map { it.jsonPrimitive.content }
    require(symbols == ASSETS.map(AssetSpec::symbol).sorted()) {
        "compiled asset universe differs from registration"
    }
    val queries =
        lock.getValue("sources").jsonObject
            .getValue("social").jsonObject
            .getValue("queries").jsonObject
            .mapValues { it.value.jsonPrimitive.content }
    require(queries == ASSETS.associate { it.symbol to it.query }) {
        "compiled Bluesky queries differ from registration"
    }
    val months =
        lock.getValue("sources").jsonObject
            .getValue("market").jsonObject
            .getValue("months").jsonArray
            .map { it.jsonPrimitive.content }
    require(months == MONTHS.map { it.toString() }) {
        "compiled Binance months differ from registration"
    }
    val frozen = config.outputRoot.resolve("program-lock.json")
    if (Files.isRegularFile(frozen)) {
        require(store.sha256(frozen) == store.sha256(bytes)) {
            "backfill program lock changed after output creation"
        }
    } else {
        store.publish(bytes, frozen)
    }
}

private val JSON = Json { ignoreUnknownKeys = false }
