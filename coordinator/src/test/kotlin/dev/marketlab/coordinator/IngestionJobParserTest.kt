package dev.marketlab.coordinator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import java.time.Duration
import java.time.Instant
import java.util.UUID

class IngestionJobParserTest {
    @Test
    fun `parses the service REST payload and expands only requested operations`() {
        val payload =
            buildJsonObject {
                put("source", "HYPERLIQUID_REST")
                put("instruments", JsonArray(listOf(JsonPrimitive("BTC"), JsonPrimitive("ETH"))))
                put(
                    "dataKinds",
                    JsonArray(listOf(JsonPrimitive("L2_BOOK"), JsonPrimitive("ASSET_CONTEXT"))),
                )
            }

        val parsed = parse(payload)
        val operations =
            parsed.operations(UUID.fromString("f1144419-e1fe-4d26-bd55-7ea129d03ce2"))

        assertEquals(
            listOf(
                RestDataKind.L2_BOOK,
                RestDataKind.L2_BOOK,
                RestDataKind.MARK_ORACLE,
                RestDataKind.OPEN_INTEREST,
            ),
            operations.map(IngestionOperation::kind),
        )
        assertEquals(listOf("BTC", "ETH"), operations[2].instruments)
        assertEquals(operations.map(IngestionOperation::operationKey).distinct().size, operations.size)
    }

    @Test
    fun `historical payload has a strict bounded range and a stable operation key`() {
        val payload =
            historicalPayload(
                startAt = "2026-07-01T00:00:00Z",
                endAt = "2026-07-02T00:00:00Z",
            )
        val parsed = parse(payload)
        val jobId = UUID.fromString("60814f15-3a97-498f-a9c0-a38860629130")

        val first = parsed.operations(jobId).single()
        val replay = parse(payload).operations(jobId).single()

        assertEquals("1h", first.candleInterval)
        assertEquals(first.operationKey, replay.operationKey)
        assertNotEquals(first.operationKey, parsed.operations(UUID.randomUUID()).single().operationKey)
    }

    @Test
    fun `unsupported sources kinds and unknown fields fail before side effects`() {
        val wrongSource =
            buildJsonObject {
                put("source", "HYPERLIQUID_TESTNET")
                put("instruments", JsonArray(listOf(JsonPrimitive("BTC"))))
                put("dataKinds", JsonArray(listOf(JsonPrimitive("L2_BOOK"))))
            }
        assertEquals(
            "UNSUPPORTED_INGESTION_SOURCE",
            assertFailsWith<PermanentJobException> { parse(wrongSource) }.code,
        )

        val unsupportedKind =
            buildJsonObject {
                put("source", "HYPERLIQUID_REST")
                put("instruments", JsonArray(listOf(JsonPrimitive("BTC"))))
                put("dataKinds", JsonArray(listOf(JsonPrimitive("TRADES"))))
            }
        assertEquals(
            "UNSUPPORTED_INGESTION_KIND",
            assertFailsWith<PermanentJobException> { parse(unsupportedKind) }.code,
        )

        val unknownField =
            buildJsonObject {
                put("source", "HYPERLIQUID_REST")
                put("instruments", JsonArray(listOf(JsonPrimitive("BTC"))))
                put("dataKinds", JsonArray(listOf(JsonPrimitive("L2_BOOK"))))
                put("endpoint", "https://example.invalid")
            }
        assertEquals(
            "INVALID_INGESTION_PAYLOAD",
            assertFailsWith<PermanentJobException> { parse(unknownField) }.code,
        )
    }

    @Test
    fun `historical ranges are required ordered and capped`() {
        val missingRange =
            buildJsonObject {
                put("source", "HYPERLIQUID_REST")
                put("instruments", JsonArray(listOf(JsonPrimitive("BTC"))))
                put("dataKinds", JsonArray(listOf(JsonPrimitive("FUNDING"))))
            }
        assertEquals(
            "INVALID_INGESTION_RANGE",
            assertFailsWith<PermanentJobException> { parse(missingRange) }.code,
        )
        assertEquals(
            "INVALID_INGESTION_RANGE",
            assertFailsWith<PermanentJobException> {
                parse(
                    historicalPayload(
                        startAt = "2026-07-02T00:00:00Z",
                        endAt = "2026-07-01T00:00:00Z",
                    ),
                )
            }.code,
        )
        assertEquals(
            "INVALID_INGESTION_RANGE",
            assertFailsWith<PermanentJobException> {
                IngestionJobParser.parse(
                    payload =
                        historicalPayload(
                            startAt = "2026-07-01T00:00:00Z",
                            endAt = "2026-07-03T00:00:00Z",
                        ),
                    defaultCandleInterval = "1h",
                    maximumRange = Duration.ofDays(1),
                )
            }.code,
        )
    }

    @Test
    fun `instrument and L2 parameters are constrained`() {
        val invalidSymbol =
            buildJsonObject {
                put("source", "HYPERLIQUID_REST")
                put("instruments", JsonArray(listOf(JsonPrimitive("../BTC"))))
                put("dataKinds", JsonArray(listOf(JsonPrimitive("L2_BOOK"))))
            }
        assertEquals(
            "INVALID_INGESTION_PAYLOAD",
            assertFailsWith<PermanentJobException> { parse(invalidSymbol) }.code,
        )

        val invalidL2 =
            buildJsonObject {
                put("source", "HYPERLIQUID_REST")
                put("instruments", JsonArray(listOf(JsonPrimitive("BTC"))))
                put("dataKinds", JsonArray(listOf(JsonPrimitive("L2_BOOK"))))
                put("l2SignificantFigures", 4)
                put("l2Mantissa", 2)
            }
        assertEquals(
            "INVALID_INGESTION_PAYLOAD",
            assertFailsWith<PermanentJobException> { parse(invalidL2) }.code,
        )
    }

    private fun parse(payload: kotlinx.serialization.json.JsonObject): HyperliquidRestIngestionJob =
        IngestionJobParser.parse(
            payload = payload,
            defaultCandleInterval = "1h",
            maximumRange = Duration.ofDays(365),
        )

    private fun historicalPayload(
        startAt: String,
        endAt: String,
    ) = buildJsonObject {
        put("source", "HYPERLIQUID_REST")
        put("instruments", JsonArray(listOf(JsonPrimitive("BTC"))))
        put("dataKinds", JsonArray(listOf(JsonPrimitive("CANDLES"))))
        put("startAt", startAt)
        put("endAt", endAt)
    }
}

