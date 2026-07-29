package dev.marketlab.data.json

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CanonicalJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun encode(element: JsonElement): ByteArray =
        json.encodeToString(JsonElement.serializer(), canonicalize(element)).toByteArray(Charsets.UTF_8)

    fun sha256(element: JsonElement): String = sha256(encode(element))

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
            is JsonPrimitive -> element
        }
}
