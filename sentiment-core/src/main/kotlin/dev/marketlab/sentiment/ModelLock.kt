package dev.marketlab.sentiment

import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.data.SentimentModelIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
/** Immutable identities and checksums for locally executed sentiment models. */
data class SentimentModelLock(
    val schemaVersion: String,
    val preprocessingSha256: String,
    val languageDetector: LockedLanguageDetector,
    val models: List<LockedSentimentModel>,
) {
    init {
        require(schemaVersion == "marketlab.sentiment-model-lock.v1") {
            "unsupported sentiment model lock schema"
        }
        require(DIGEST.matches(preprocessingSha256)) {
            "preprocessing hash must be a SHA-256"
        }
        require(models.map(LockedSentimentModel::key).distinct().size == models.size) {
            "sentiment model keys must be unique"
        }
    }

    fun verify(modelsRoot: Path): List<VerifiedSentimentModel> =
        models.map { model ->
            val modelPath = modelsRoot.resolve(model.modelFile).normalize()
            val tokenizerPath = modelsRoot.resolve(model.tokenizerFile).normalize()
            require(modelPath.startsWith(modelsRoot.normalize()) && tokenizerPath.startsWith(modelsRoot.normalize())) {
                "model lock path escapes models root"
            }
            require(Files.isRegularFile(modelPath) && Files.isRegularFile(tokenizerPath)) {
                "locked model artifacts are missing for ${model.key}"
            }
            require(sha256(modelPath) == model.modelSha256) {
                "ONNX hash mismatch for ${model.key}"
            }
            require(sha256(tokenizerPath) == model.tokenizerSha256) {
                "tokenizer hash mismatch for ${model.key}"
            }
            VerifiedSentimentModel(
                lock = model,
                modelPath = modelPath,
                tokenizerPath = tokenizerPath,
                identity =
                    SentimentModelIdentity(
                        key = model.key,
                        upstreamRevision = model.upstreamRevision,
                        modelSha256 = Sha256Digest(model.modelSha256),
                        tokenizerSha256 = Sha256Digest(model.tokenizerSha256),
                        preprocessingSha256 = Sha256Digest(preprocessingSha256),
                    ),
            )
        }

    companion object {
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val JSON = Json { ignoreUnknownKeys = false }

        fun read(path: Path): SentimentModelLock =
            JSON.decodeFromString(Files.readString(path))

        private fun sha256(path: Path): String {
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
    }
}

@Serializable
data class LockedLanguageDetector(
    val artifact: String,
    val languages: List<String>,
    val minimumRelativeDistance: Double,
) {
    init {
        require(artifact == "com.github.pemistahl:lingua:1.2.2") {
            "unsupported language detector artifact"
        }
        require(languages.isNotEmpty() && languages == languages.distinct().sorted()) {
            "language detector languages must be unique and sorted"
        }
        require("ENGLISH" in languages)
        require(minimumRelativeDistance in 0.0..0.99)
    }
}

@Serializable
data class LockedSentimentModel(
    val key: String,
    val upstream: String,
    val upstreamRevision: String,
    val license: String,
    val modelFile: String,
    val modelSha256: String,
    val tokenizerFile: String,
    val tokenizerSha256: String,
    val negativeIndex: Int,
    val neutralIndex: Int,
    val positiveIndex: Int,
    val maximumTokens: Int,
) {
    init {
        require(key.isNotBlank() && upstream.isNotBlank() && license.isNotBlank())
        require(Regex("[0-9a-f]{40,64}").matches(upstreamRevision))
        require(Regex("[0-9a-f]{64}").matches(modelSha256))
        require(Regex("[0-9a-f]{64}").matches(tokenizerSha256))
        require(setOf(negativeIndex, neutralIndex, positiveIndex).size == 3)
        require(listOf(negativeIndex, neutralIndex, positiveIndex).all { it in 0..2 })
        require(maximumTokens in 8..512)
    }
}

data class VerifiedSentimentModel(
    val lock: LockedSentimentModel,
    val modelPath: Path,
    val tokenizerPath: Path,
    val identity: SentimentModelIdentity,
)
