package dev.marketlab.service

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HttpServiceTest {
    @Test
    fun `wildcard bind requires an explicit container acknowledgement`() {
        assertFailsWith<IllegalArgumentException> {
            ServiceConfig(
                host = "0.0.0.0",
                bearerToken = "0123456789abcdef0123456789abcdef",
            )
        }
        ServiceConfig(
            host = "0.0.0.0",
            bearerToken = "0123456789abcdef0123456789abcdef",
            allowContainerWildcardBind = true,
        )
    }

    @Test
    fun `health and OpenAPI discovery do not require credentials`() =
        testApplication {
            application { configureHttpService(CONFIG, EmptyBackend()) }

            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
            assertContains(client.get("/metrics").bodyAsText(), "marketlab_ready 1")
            val openApi = client.get("/openapi.json")
            assertEquals(HttpStatusCode.OK, openApi.status)
            assertContains(openApi.bodyAsText(), "\"openapi\":\"3.1.0\"")
        }

    @Test
    fun `protected endpoints return problem JSON without bearer token`() =
        testApplication {
            application { configureHttpService(CONFIG, EmptyBackend()) }

            val response = client.get("/api/v1/theories")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                "application/problem+json",
                response.headers[HttpHeaders.ContentType]?.substringBefore(';'),
            )
            assertContains(response.bodyAsText(), "\"status\":401")
        }

    @Test
    fun `social status endpoints are authenticated and delegated`() =
        testApplication {
            val provider =
                object : InformationStatusProvider {
                    override fun sources() = buildJsonObject { put("kind", "sources") }
                    override fun coverage() = buildJsonObject { put("kind", "coverage") }
                    override fun models() = buildJsonObject { put("kind", "models") }
                }
            application { configureHttpService(CONFIG, EmptyBackend(), provider) }

            val response =
                client.get("/api/v1/social/coverage") {
                    authorize()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "\"kind\":\"coverage\"")
        }

    @Test
    fun `mutations require an idempotency key`() =
        testApplication {
            application { configureHttpService(CONFIG, EmptyBackend()) }

            val response =
                client.post("/api/v1/ingestions") {
                    authorize()
                    contentType(ContentType.Application.Json)
                    setBody(VALID_INGESTION)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "Idempotency key required")
        }

    @Test
    fun `valid ingestion is accepted and constrained request reaches backend`() =
        testApplication {
            val backend = RecordingBackend()
            application { configureHttpService(CONFIG, backend) }

            val response =
                client.post("/api/v1/ingestions") {
                    authorize()
                    header("Idempotency-Key", "ingestion-test-key")
                    contentType(ContentType.Application.Json)
                    setBody(VALID_INGESTION)
                }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(
                "/api/v1/ingestions/${RecordingBackend.JOB_ID}",
                response.headers[HttpHeaders.Location],
            )
            val captured = assertNotNull(backend.ingestion)
            assertEquals(listOf("BTC"), captured.instruments)
            assertEquals(IngestionSource.HYPERLIQUID_REST, captured.source)
        }

    @Test
    fun `idempotency replay is stable and conflicting reuse is problem JSON`() =
        testApplication {
            val backend = RecordingBackend()
            application { configureHttpService(CONFIG, backend) }

            suspend fun submit(body: String) =
                client.post("/api/v1/ingestions") {
                    authorize()
                    header("Idempotency-Key", "stable-ingestion-key")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            val first = submit(VALID_INGESTION)
            val replay = submit(VALID_INGESTION)
            val conflict =
                submit(
                    VALID_INGESTION.replace(
                        "\"instruments\":[\"btc\"]",
                        "\"instruments\":[\"eth\"]",
                    ),
                )

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Accepted, replay.status)
            assertContains(replay.bodyAsText(), "\"replayed\":true")
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals(
                "application/problem+json",
                conflict.headers[HttpHeaders.ContentType]?.substringBefore(';'),
            )
        }

    @Test
    fun `unsafe run parameter shapes are rejected before submission`() =
        testApplication {
            val backend = RecordingBackend()
            application { configureHttpService(CONFIG, backend) }

            val response =
                client.post("/api/v1/runs") {
                    authorize()
                    header("Idempotency-Key", "run-test-key-001")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "theoryId":"har-volatility",
                          "theoryVersion":"1.0.0",
                          "snapshotId":"00000000-0000-0000-0000-000000000011",
                          "parameters":{"lookbacks":[1,5,22]}
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(backend.runRequest == null)
            assertContains(response.bodyAsText(), "must be a scalar")
        }

    @Test
    fun `paper session cannot omit its promotion-qualified run`() =
        testApplication {
            application { configureHttpService(CONFIG, EmptyBackend()) }

            val response =
                client.post("/api/v1/paper-sessions") {
                    authorize()
                    header("Idempotency-Key", "paper-without-run")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "theoryId":"time-series-momentum",
                          "theoryVersion":"1.0.0",
                          "initialEquity":"100000",
                          "riskProfile":"DEFAULT_100K"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        header(HttpHeaders.Authorization, "Bearer ${CONFIG.bearerToken}")
    }

    private companion object {
        val CONFIG =
            ServiceConfig(
                bearerToken = "0123456789abcdef0123456789abcdef",
            )
        val VALID_INGESTION =
            """
            {
              "source":"HYPERLIQUID_REST",
              "instruments":["btc"],
              "dataKinds":["CANDLES"],
              "startAt":"2025-01-01T00:00:00Z",
              "endAt":"2025-01-02T00:00:00Z"
            }
            """.trimIndent()
    }
}

private open class EmptyBackend : ServiceBackend {
    override suspend fun ready() = true
    override suspend fun theories() = emptyList<TheoryView>()
    override suspend fun snapshots() = emptyList<SnapshotView>()
    override suspend fun snapshot(id: UUID): SnapshotView? = null
    override suspend fun qualityFindings(snapshotId: UUID) = emptyList<QualityFindingView>()

    override suspend fun submitIngestion(
        key: String,
        requestHash: String,
        request: IngestionRequest,
    ): AcceptedResponse = error("Unexpected ingestion submission")

    override suspend fun submitRun(
        key: String,
        requestHash: String,
        request: RunRequest,
    ): AcceptedResponse = error("Unexpected run submission")

    override suspend fun run(id: UUID): RunView? = null
    override suspend fun artifacts(runId: UUID) = emptyList<ArtifactView>()
    override suspend fun job(id: UUID): JobView? = null
    override suspend fun cancelJob(
        id: UUID,
        key: String,
        requestHash: String,
    ): Pair<JobView, Boolean> = error("Unexpected cancellation")

    override suspend fun createPaperSession(
        key: String,
        requestHash: String,
        request: PaperSessionRequest,
    ): Pair<PaperSessionView, Boolean> = error("Unexpected paper-session creation")

    override suspend fun paperSession(id: UUID): PaperSessionView? = null
    override suspend fun paperSessions() = emptyList<PaperSessionView>()

    override suspend fun transitionPaperSession(
        id: UUID,
        action: PaperAction,
        key: String,
        requestHash: String,
        request: PaperActionRequest,
    ): PaperActionResponse = error("Unexpected paper-session transition")

    override suspend fun paperEvents(id: UUID, afterSequence: Long) = emptyList<PaperEventView>()
    override suspend fun paperPositions(id: UUID) = emptyList<PositionView>()
    override suspend fun paperOrders(id: UUID) = emptyList<OrderView>()
}

private class RecordingBackend : EmptyBackend() {
    var ingestion: IngestionRequest? = null
    var runRequest: RunRequest? = null
    private val ingestionHashes = mutableMapOf<String, String>()

    override suspend fun submitIngestion(
        key: String,
        requestHash: String,
        request: IngestionRequest,
    ): AcceptedResponse {
        val prior = ingestionHashes.putIfAbsent(key, requestHash)
        if (prior != null && prior != requestHash) {
            throw dev.marketlab.persistence.PersistenceConflictException(
                "Idempotency key was reused with a different request",
            )
        }
        ingestion = request
        return AcceptedResponse(
            resourceType = "ingestion",
            resourceId = JOB_ID.toString(),
            jobId = JOB_ID.toString(),
            acceptedAt = "2026-01-01T00:00:00Z",
            replayed = prior != null,
        )
    }

    override suspend fun submitRun(
        key: String,
        requestHash: String,
        request: RunRequest,
    ): AcceptedResponse {
        runRequest = request
        return AcceptedResponse(
            resourceType = "run",
            resourceId = RUN_ID.toString(),
            jobId = JOB_ID.toString(),
            acceptedAt = "2026-01-01T00:00:00Z",
            replayed = false,
        )
    }

    companion object {
        val JOB_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val RUN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
    }
}
