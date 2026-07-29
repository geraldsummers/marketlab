package dev.marketlab.theory

import dev.marketlab.contracts.Sha256Digest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

object TheoryPlanHasher {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
    }

    fun canonicalJson(plan: TheoryPlan): String {
        TheoryPlanValidator.requireValid(plan)
        val element = json.encodeToJsonElement(TheoryPlan.serializer(), plan)
        return json.encodeToString(JsonElement.serializer(), canonicalize(element))
    }

    fun hash(plan: TheoryPlan): Sha256Digest {
        val bytes = canonicalJson(plan).encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest(digest.joinToString(separator = "") { byte -> "%02x".format(byte) })
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy(Map.Entry<String, JsonElement>::key)
                .associate { (key, value) -> key to canonicalize(value) },
        )

        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
