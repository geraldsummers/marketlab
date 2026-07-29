package dev.marketlab.sentiment

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class SentimentModelTest {
    @Test
    fun `preprocessing identity and substitutions are frozen`() {
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(SentimentPreprocessor.SPEC.toByteArray())
                .joinToString("") { "%02x".format(it) }

        assertEquals(
            "b33201f76835b24083e657080932deb7cb2eaa4b23629efae8d55d302df3ea18",
            hash,
        )
        assertEquals(
            "Bullish @user http",
            SentimentPreprocessor.social("  Bullish   @alice https://example.test  "),
        )
    }

    @Test
    fun `locked ONNX models emit normalized deterministic probabilities`() {
        val models = System.getenv("MARKETLAB_SENTIMENT_MODELS_ROOT")
        val lockPath = System.getenv("MARKETLAB_SENTIMENT_MODEL_LOCK")
        assumeTrue(!models.isNullOrBlank() && !lockPath.isNullOrBlank())
        val verified =
            SentimentModelLock.read(Path.of(lockPath)).verify(Path.of(models))

        OnnxSentimentModel(
            verified.single { it.lock.key == "social-twitter-roberta" },
        ).use { model ->
            val score = model.score("positive-case", "Bitcoin adoption is excellent and I am very bullish")
            assertTrue(score.positiveProbability > score.negativeProbability)
            assertEquals(
                1.0,
                score.positiveProbability + score.neutralProbability + score.negativeProbability,
                1.0e-6,
            )
        }
        OnnxSentimentModel(
            verified.single { it.lock.key == "news-finbert" },
        ).use { model ->
            val score = model.score("negative-case", "The company reported severe losses and a default")
            assertTrue(score.negativeProbability > score.positiveProbability)
        }
    }
}
