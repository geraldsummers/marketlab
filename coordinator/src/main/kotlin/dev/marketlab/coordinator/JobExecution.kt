package dev.marketlab.coordinator

import kotlinx.serialization.json.JsonObject
import java.time.Duration

sealed interface JobExecutionResult {
    data class Succeeded(val result: JsonObject) : JobExecutionResult

    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val retryDelay: Duration = Duration.ZERO,
    ) : JobExecutionResult {
        init {
            require(ERROR_CODE.matches(code)) { "invalid job failure code" }
            require(message.isNotBlank()) { "job failure message cannot be blank" }
            require(message.length <= MAX_ERROR_MESSAGE_CHARS) { "job failure message is too long" }
            require(!retryDelay.isNegative && retryDelay <= Duration.ofDays(7)) {
                "job retry delay is outside the supported range"
            }
        }
    }

    private companion object {
        val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
        const val MAX_ERROR_MESSAGE_CHARS = 1_024
    }
}

class PermanentJobException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

