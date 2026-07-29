package dev.marketlab.sentiment

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.marketlab.contracts.MarketTimestamp
import dev.marketlab.contracts.data.SentimentScore
import java.io.Closeable
import java.nio.LongBuffer
import java.time.Clock
import kotlin.math.exp

class OnnxSentimentModel(
    private val verified: VerifiedSentimentModel,
    private val clock: Clock = Clock.systemUTC(),
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {
    private val tokenizer =
        HuggingFaceTokenizer.newInstance(
            verified.tokenizerPath,
            mapOf(
                "padding" to "false",
                "truncation" to "true",
                "maxLength" to verified.lock.maximumTokens.toString(),
            ),
        )
    private val session =
        environment.createSession(
            verified.modelPath.toString(),
            OrtSession.SessionOptions(),
        )

    fun score(sourceEventId: String, text: String): SentimentScore {
        val prepared =
            if (verified.lock.key.startsWith("social-")) {
                SentimentPreprocessor.social(text)
            } else {
                SentimentPreprocessor.news(text)
            }
        require(prepared.isNotBlank()) { "sentiment input cannot be blank after preprocessing" }
        val encoding = tokenizer.encode(prepared)
        val ids = encoding.ids.take(verified.lock.maximumTokens).toLongArray()
        require(ids.isNotEmpty()) { "tokenizer emitted no tokens" }
        val mask = LongArray(ids.size) { 1L }
        val shape = longArrayOf(1L, ids.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { inputIds ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape).use { attentionMask ->
                val inputs = linkedMapOf<String, OnnxTensor>()
                inputs["input_ids"] = inputIds
                inputs["attention_mask"] = attentionMask
                var tokenTypes: OnnxTensor? = null
                if ("token_type_ids" in session.inputNames) {
                    tokenTypes =
                        OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(LongArray(ids.size)),
                            shape,
                        )
                    inputs["token_type_ids"] = tokenTypes
                }
                try {
                    session.run(inputs).use { result ->
                        val logits = (result[0].value as Array<*>)[0] as FloatArray
                        require(logits.size == 3) { "sentiment model must emit exactly three logits" }
                        val probabilities = softmax(logits)
                        return SentimentScore(
                            sourceEventId = sourceEventId,
                            model = verified.identity,
                            scoredAt = MarketTimestamp(clock.instant().toEpochMilli()),
                            negativeProbability = probabilities[verified.lock.negativeIndex],
                            neutralProbability = probabilities[verified.lock.neutralIndex],
                            positiveProbability = probabilities[verified.lock.positiveIndex],
                        )
                    }
                } finally {
                    tokenTypes?.close()
                }
            }
        }
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    private fun softmax(logits: FloatArray): DoubleArray {
        val maximum = logits.max()
        val exponentials = logits.map { exp((it - maximum).toDouble()) }
        val total = exponentials.sum()
        return exponentials.map { it / total }.toDoubleArray()
    }
}
