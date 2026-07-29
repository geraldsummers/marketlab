package dev.marketlab.service

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface InformationStatusProvider {
    fun sources(): JsonElement

    fun coverage(): JsonElement

    fun models(): JsonElement

    companion object {
        fun unavailable(): InformationStatusProvider =
            object : InformationStatusProvider {
                override fun sources(): JsonElement = unavailableObject()
                override fun coverage(): JsonElement = unavailableObject()
                override fun models(): JsonElement = unavailableObject()
            }

        private fun unavailableObject(): JsonObject =
            buildJsonObject {
                put("schemaVersion", "marketlab.social-status.v1")
                put("available", false)
            }
    }
}

class FileInformationStatusProvider(
    private val rawRoot: Path?,
    private val featureRoot: Path?,
    private val universeRoot: Path?,
    private val modelLock: Path?,
) : InformationStatusProvider {
    override fun sources(): JsonElement {
        val root = rawRoot?.takeIf(Files::isDirectory) ?: return unavailable("raw-root")
        val latestBySource = sortedMapOf<String, Long>()
        var informationRecords = 0L
        var qualitySignals = 0L
        val manifests = root.resolve("manifests")
        if (Files.isDirectory(manifests)) {
            Files.walk(manifests).use { paths ->
                paths.iterator().asSequence()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .forEach { path ->
                        val manifest = parse(path)?.jsonObject ?: return@forEach
                        informationRecords +=
                            manifest["informationCount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                ?: 0L
                        qualitySignals +=
                            manifest["qualityCount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                ?: 0L
                        val at =
                            manifest["finalizedAtEpochMillis"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toLongOrNull() ?: 0L
                        manifest["sources"]?.jsonArray?.forEach { source ->
                            latestBySource.merge(source.jsonPrimitive.content, at, ::maxOf)
                        }
                    }
            }
        }
        return buildJsonObject {
            put("schemaVersion", "marketlab.social-source-status.v1")
            put("available", true)
            put("ready", Files.exists(root.resolve(".social-collector-ready.json")))
            put("informationRecords", informationRecords)
            put("qualitySignals", qualitySignals)
            put(
                "sources",
                JsonArray(
                    latestBySource.map { (source, at) ->
                        buildJsonObject {
                            put("source", source)
                            put("lastFinalizedAtEpochMillis", at)
                        }
                    },
                ),
            )
        }
    }

    override fun coverage(): JsonElement {
        val feature = featureRoot?.takeIf(Files::isDirectory) ?: return unavailable("feature-root")
        val state = parse(feature.resolve("program/state.json"))
        val latestFeature = parse(feature.resolve("features/latest.json"))
        val universe = universeRoot?.let { parse(it.resolve(".social-universe-ready.json")) }
        return buildJsonObject {
            put("schemaVersion", "marketlab.social-coverage-status.v1")
            put("available", true)
            put("program", state ?: JsonNull)
            put("latestFeature", latestFeature ?: JsonNull)
            put("universe", universe ?: JsonNull)
        }
    }

    override fun models(): JsonElement {
        val lock = modelLock?.let(::parse)?.jsonObject ?: return unavailable("model-lock")
        return buildJsonObject {
            put("schemaVersion", "marketlab.social-model-status.v1")
            put("available", true)
            put("preprocessingSha256", lock.getValue("preprocessingSha256"))
            put("languageDetector", lock.getValue("languageDetector"))
            put(
                "models",
                JsonArray(
                    lock.getValue("models").jsonArray.map { element ->
                        val model = element.jsonObject
                        buildJsonObject {
                            put("key", model.getValue("key"))
                            put("upstream", model.getValue("upstream"))
                            put("upstreamRevision", model.getValue("upstreamRevision"))
                            put("modelSha256", model.getValue("modelSha256"))
                            put("tokenizerSha256", model.getValue("tokenizerSha256"))
                        }
                    },
                ),
            )
        }
    }

    private fun unavailable(component: String): JsonObject =
        buildJsonObject {
            put("schemaVersion", "marketlab.social-status.v1")
            put("available", false)
            put("missingComponent", component)
        }

    private fun parse(path: Path): JsonElement? =
        if (Files.isRegularFile(path)) {
            runCatching { JSON.parseToJsonElement(Files.readString(path)) }.getOrNull()
        } else {
            null
        }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
