package dev.marketlab.service

import dev.marketlab.persistence.PersistenceConflictException
import dev.marketlab.persistence.PersistenceNotFoundException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

data class ServiceConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val bearerToken: String,
    val exposeErrorDetails: Boolean = false,
    val allowContainerWildcardBind: Boolean = false,
    val socialRawRoot: Path? = null,
    val socialFeatureRoot: Path? = null,
    val socialUniverseRoot: Path? = null,
    val sentimentModelLock: Path? = null,
) {
    init {
        val loopback = host == "127.0.0.1" || host == "::1" || host == "localhost"
        val explicitContainerBind =
            allowContainerWildcardBind && (host == "0.0.0.0" || host == "::")
        require(loopback || explicitContainerBind) {
            "The API must bind to loopback unless container wildcard binding is explicitly enabled"
        }
        require(port in 1..65_535)
        require(bearerToken.length in 32..4_096) {
            "MARKETLAB_API_TOKEN must contain at least 32 characters"
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServiceConfig =
            ServiceConfig(
                host = environment["MARKETLAB_API_HOST"] ?: "127.0.0.1",
                port = environment["MARKETLAB_API_PORT"]?.toIntOrNull() ?: 8080,
                bearerToken =
                    environment["MARKETLAB_API_TOKEN"]
                        ?: error("MARKETLAB_API_TOKEN is required"),
                exposeErrorDetails =
                    environment["MARKETLAB_EXPOSE_ERROR_DETAILS"]?.toBooleanStrictOrNull() ?: false,
                allowContainerWildcardBind =
                    environment["MARKETLAB_ALLOW_CONTAINER_WILDCARD_BIND"]
                        ?.toBooleanStrictOrNull() ?: false,
                socialRawRoot = environment.optionalPath("MARKETLAB_SOCIAL_RAW_ROOT"),
                socialFeatureRoot = environment.optionalPath("MARKETLAB_SENTIMENT_FEATURE_ROOT"),
                socialUniverseRoot = environment.optionalPath("MARKETLAB_SOCIAL_UNIVERSE_ROOT"),
                sentimentModelLock = environment.optionalPath("MARKETLAB_SENTIMENT_MODEL_LOCK"),
            )

        private fun Map<String, String>.optionalPath(name: String): Path? =
            this[name]?.trim()?.takeIf(String::isNotEmpty)?.let(Path::of)?.normalize()
    }
}

private class ApiException(
    val status: HttpStatusCode,
    val problemType: String,
    val problemTitle: String,
    override val message: String,
) : RuntimeException(message)

internal val ServiceJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

fun Application.configureHttpService(
    config: ServiceConfig,
    backend: ServiceBackend,
    informationStatus: InformationStatusProvider = InformationStatusProvider.unavailable(),
) {
    val applicationLog = environment.log
    install(CallLogging)
    install(ContentNegotiation) {
        json(ServiceJson)
    }
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respondProblem(
                status,
                "https://marketlab.dev/problems/unauthorized",
                "Unauthorized",
                "A valid bearer token is required",
            )
        }
        exception<ApiException> { call, failure ->
            call.respondProblem(
                status = failure.status,
                type = failure.problemType,
                title = failure.problemTitle,
                detail = failure.message,
            )
        }
        exception<PersistenceNotFoundException> { call, failure ->
            call.respondProblem(
                HttpStatusCode.NotFound,
                "https://marketlab.dev/problems/not-found",
                "Resource not found",
                failure.message ?: "The requested resource does not exist",
            )
        }
        exception<PersistenceConflictException> { call, failure ->
            call.respondProblem(
                HttpStatusCode.Conflict,
                "https://marketlab.dev/problems/conflict",
                "Request conflicts with current state",
                failure.message ?: "The request conflicts with current state",
            )
        }
        exception<SerializationException> { call, failure ->
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "https://marketlab.dev/problems/invalid-json",
                "Invalid request body",
                failure.message ?: "The JSON body could not be decoded",
            )
        }
        exception<BadRequestException> { call, failure ->
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "https://marketlab.dev/problems/invalid-json",
                "Invalid request body",
                failure.message ?: "The request body could not be decoded",
            )
        }
        exception<IllegalArgumentException> { call, failure ->
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "https://marketlab.dev/problems/invalid-request",
                "Invalid request",
                failure.message ?: "The request is invalid",
            )
        }
        exception<Throwable> { call, failure ->
            applicationLog.error("Unhandled request failure", failure)
            call.respondProblem(
                HttpStatusCode.InternalServerError,
                "https://marketlab.dev/problems/internal-error",
                "Internal server error",
                if (config.exposeErrorDetails) {
                    failure.message ?: "Unexpected failure"
                } else {
                    "The request could not be completed"
                },
            )
        }
    }
    authentication {
        bearer("api-bearer") {
            authenticate { credential ->
                if (constantTimeEquals(credential.token, config.bearerToken)) {
                    UserIdPrincipal("marketlab-api")
                } else {
                    null
                }
            }
        }
    }

    routing {
        get("/health/live") {
            call.respond(HealthResponse("UP"))
        }
        get("/health/ready") {
            if (backend.ready()) {
                call.respond(HealthResponse("READY"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("NOT_READY"))
            }
        }
        get("/openapi.json") {
            call.respond(openApiDescription())
        }
        get("/metrics") {
            val ready = if (backend.ready()) 1 else 0
            call.respondText(
                """
                # HELP marketlab_ready Whether the control-plane database is reachable.
                # TYPE marketlab_ready gauge
                marketlab_ready $ready
                """.trimIndent() + "\n",
                ContentType.parse("text/plain; version=0.0.4"),
            )
        }

        authenticate("api-bearer") {
            route("/api/v1") {
                get("/theories") {
                    call.respond(Page(backend.theories()))
                }
                get("/theories/{id}") {
                    val id = call.requiredPath("id", THEORY_ID)
                    val versions = backend.theories().filter { it.id == id }
                    if (versions.isEmpty()) throw notFound("Theory $id does not exist")
                    call.respond(Page(versions))
                }
                get("/social/sources") {
                    call.respond(informationStatus.sources())
                }
                get("/social/coverage") {
                    call.respond(informationStatus.coverage())
                }
                get("/social/models") {
                    call.respond(informationStatus.models())
                }

                get("/snapshots") {
                    call.respond(Page(backend.snapshots()))
                }
                get("/snapshots/{id}") {
                    val id = call.requiredUuid("id")
                    call.respond(backend.snapshot(id) ?: throw notFound("Snapshot $id does not exist"))
                }
                get("/snapshots/{id}/quality") {
                    val id = call.requiredUuid("id")
                    if (backend.snapshot(id) == null) throw notFound("Snapshot $id does not exist")
                    call.respond(Page(backend.qualityFindings(id)))
                }

                post("/ingestions") {
                    val key = call.requiredIdempotencyKey()
                    val request = call.receive<IngestionRequest>().validated()
                    val response = backend.submitIngestion(key, request.requestHash(), request)
                    call.response.header(
                        HttpHeaders.Location,
                        "/api/v1/ingestions/${response.resourceId}",
                    )
                    call.respond(HttpStatusCode.Accepted, response)
                }
                get("/ingestions/{id}") {
                    val id = call.requiredUuid("id")
                    val job = backend.job(id)
                    if (job == null || job.resourceType != "ingestion") {
                        throw notFound("Ingestion $id does not exist")
                    }
                    call.respond(job)
                }

                post("/runs") {
                    val key = call.requiredIdempotencyKey()
                    val request = call.receive<RunRequest>().validated()
                    val response = backend.submitRun(key, request.requestHash(), request)
                    call.response.header(HttpHeaders.Location, "/api/v1/runs/${response.resourceId}")
                    call.respond(HttpStatusCode.Accepted, response)
                }
                get("/runs/{id}") {
                    val id = call.requiredUuid("id")
                    call.respond(backend.run(id) ?: throw notFound("Run $id does not exist"))
                }
                get("/runs/{id}/artifacts") {
                    val id = call.requiredUuid("id")
                    if (backend.run(id) == null) throw notFound("Run $id does not exist")
                    call.respond(Page(backend.artifacts(id)))
                }

                get("/jobs/{id}") {
                    val id = call.requiredUuid("id")
                    call.respond(backend.job(id) ?: throw notFound("Job $id does not exist"))
                }
                post("/jobs/{id}/cancel") {
                    val key = call.requiredIdempotencyKey()
                    val id = call.requiredUuid("id")
                    val (job, replayed) =
                        backend.cancelJob(id, key, sha256("cancel:$id"))
                    if (replayed) call.response.header("Idempotency-Replayed", "true")
                    call.respond(HttpStatusCode.Accepted, job)
                }

                get("/paper-sessions") {
                    call.respond(Page(backend.paperSessions()))
                }
                post("/paper-sessions") {
                    val key = call.requiredIdempotencyKey()
                    val request = call.receive<PaperSessionRequest>().validated()
                    val (session, replayed) =
                        backend.createPaperSession(key, request.requestHash(), request)
                    call.response.header(HttpHeaders.Location, "/api/v1/paper-sessions/${session.id}")
                    if (replayed) call.response.header("Idempotency-Replayed", "true")
                    call.respond(HttpStatusCode.Created, session)
                }
                get("/paper-sessions/{id}") {
                    val id = call.requiredUuid("id")
                    call.respond(
                        backend.paperSession(id)
                            ?: throw notFound("Paper session $id does not exist"),
                    )
                }
                post("/paper-sessions/{id}/start") {
                    call.paperTransition(backend, PaperAction.START)
                }
                post("/paper-sessions/{id}/pause") {
                    call.paperTransition(backend, PaperAction.PAUSE)
                }
                post("/paper-sessions/{id}/stop") {
                    call.paperTransition(backend, PaperAction.STOP)
                }
                get("/paper-sessions/{id}/ledger") {
                    val id = call.requiredUuid("id")
                    val after = call.request.queryParameters["afterSequence"]?.toLongOrNull() ?: 0L
                    if (after < 0) throw invalid("afterSequence must be non-negative")
                    if (backend.paperSession(id) == null) {
                        throw notFound("Paper session $id does not exist")
                    }
                    call.respond(Page(backend.paperEvents(id, after)))
                }
                get("/paper-sessions/{id}/positions") {
                    val id = call.requiredUuid("id")
                    if (backend.paperSession(id) == null) {
                        throw notFound("Paper session $id does not exist")
                    }
                    call.respond(Page(backend.paperPositions(id)))
                }
                get("/paper-sessions/{id}/orders") {
                    val id = call.requiredUuid("id")
                    if (backend.paperSession(id) == null) {
                        throw notFound("Paper session $id does not exist")
                    }
                    call.respond(Page(backend.paperOrders(id)))
                }
                get("/paper-sessions/{id}/fills") {
                    val id = call.requiredUuid("id")
                    if (backend.paperSession(id) == null) {
                        throw notFound("Paper session $id does not exist")
                    }
                    val fills = backend.paperEvents(id, 0).filter { "FILL" in it.eventType }
                    call.respond(Page(fills))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.paperTransition(
    backend: ServiceBackend,
    action: PaperAction,
) {
    val key = requiredIdempotencyKey()
    val id = requiredUuid("id")
    val request = receive<PaperActionRequest>().validated()
    val hash = sha256("${action.name}:${request.requestHash()}")
    respond(
        HttpStatusCode.Accepted,
        backend.transitionPaperSession(id, action, key, hash, request),
    )
}

private fun IngestionRequest.validated(): IngestionRequest {
    require(instruments.isNotEmpty()) { "At least one instrument is required" }
    require(instruments.size <= 100) { "At most 100 instruments are accepted" }
    require(instruments.distinct().size == instruments.size) { "Instruments must be unique" }
    require(instruments.all { it.matches(INSTRUMENT) }) { "An instrument identifier is invalid" }
    require(dataKinds.isNotEmpty()) { "At least one data kind is required" }
    require(dataKinds.distinct().size == dataKinds.size) { "Data kinds must be unique" }
    require(source == IngestionSource.HYPERLIQUID_REST) {
        "Only HYPERLIQUID_REST has a deployed ingestion executor"
    }
    require(
        dataKinds.all {
            it in
                setOf(
                    IngestionDataKind.CANDLES,
                    IngestionDataKind.FUNDING,
                    IngestionDataKind.L2_BOOK,
                    IngestionDataKind.ASSET_CONTEXT,
                    IngestionDataKind.OPEN_INTEREST,
                    IngestionDataKind.ORACLE_MARK,
                )
        },
    ) {
        "A requested data kind is not supported by the Hyperliquid REST executor"
    }
    val start = startAt?.parseInstant("startAt")
    val end = endAt?.parseInstant("endAt")
    require(start == null || end == null || start < end) { "startAt must be earlier than endAt" }
    val historical =
        IngestionDataKind.CANDLES in dataKinds || IngestionDataKind.FUNDING in dataKinds
    require(!historical || start != null && end != null) {
        "Historical candles and funding require startAt and endAt"
    }
    require(candleInterval == null || candleInterval in CANDLE_INTERVALS) {
        "candleInterval is unsupported"
    }
    require(l2SignificantFigures == null || l2SignificantFigures in 2..5) {
        "l2SignificantFigures must be between 2 and 5"
    }
    require(l2Mantissa == null || l2SignificantFigures == 5 && l2Mantissa in setOf(1, 2, 5)) {
        "l2Mantissa requires five significant figures and must be 1, 2, or 5"
    }
    return copy(instruments = instruments.map(String::uppercase))
}

private fun RunRequest.validated(): RunRequest {
    require(theoryId.matches(THEORY_ID)) { "theoryId is invalid" }
    require(theoryVersion.matches(VERSION)) { "theoryVersion is invalid" }
    UUID.fromString(snapshotId)
    require(parameters.size <= 64) { "At most 64 theory parameters are accepted" }
    parameters.forEach { (name, value) ->
        require(name.matches(PARAMETER_NAME)) { "Theory parameter name '$name' is invalid" }
        require(value is JsonPrimitive && value !== JsonNull) {
            "Theory parameter '$name' must be a scalar"
        }
        if (value.isString) {
            require(value.content.length <= 256) {
                "Theory parameter '$name' exceeds 256 characters"
            }
        }
    }
    return this
}

private fun PaperSessionRequest.validated(): PaperSessionRequest {
    require(theoryId.matches(THEORY_ID)) { "theoryId is invalid" }
    require(theoryVersion.matches(VERSION)) { "theoryVersion is invalid" }
    UUID.fromString(runId)
    val equity =
        runCatching { BigDecimal(initialEquity) }
            .getOrElse { throw invalid("initialEquity must be an exact decimal") }
    require(equity >= BigDecimal("100") && equity <= BigDecimal("1000000000")) {
        "initialEquity must be between 100 and 1,000,000,000"
    }
    require(equity.scale() <= 18) { "initialEquity may have at most 18 decimal places" }
    require(riskProfile in setOf("DEFAULT_10K", "DEFAULT_100K", "DEFAULT_1M")) {
        "riskProfile must be a registered profile"
    }
    return copy(initialEquity = equity.stripTrailingZeros().toPlainString())
}

private fun PaperActionRequest.validated(): PaperActionRequest {
    reason?.let {
        require(it.isNotBlank() && it.length <= 512) {
            "reason must contain between 1 and 512 characters"
        }
    }
    return this
}

private inline fun <reified T> T.requestHash(): String =
    sha256(canonicalJson(ServiceJson.encodeToJsonElement(this)).toString())

private fun canonicalJson(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(
                element.entries
                    .sortedBy { it.key }
                    .associate { (key, value) -> key to canonicalJson(value) },
            )
        is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.JsonArray(element.map(::canonicalJson))
        else -> element
    }

private fun String.parseInstant(name: String): Instant =
    try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        throw invalid("$name must be an ISO-8601 UTC timestamp")
    }

private fun ApplicationCall.requiredIdempotencyKey(): String {
    val key =
        request.headers["Idempotency-Key"]
            ?: throw ApiException(
                HttpStatusCode.BadRequest,
                "https://marketlab.dev/problems/idempotency-key-required",
                "Idempotency key required",
                "Mutating requests require an Idempotency-Key header",
            )
    if (!key.matches(IDEMPOTENCY_KEY)) {
        throw invalid("Idempotency-Key must contain 8-128 safe ASCII characters")
    }
    return key
}

private fun ApplicationCall.requiredUuid(name: String): UUID {
    val raw = parameters[name] ?: throw invalid("Missing path parameter '$name'")
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw invalid("Path parameter '$name' must be a UUID") }
}

private fun ApplicationCall.requiredPath(name: String, regex: Regex): String {
    val raw = parameters[name] ?: throw invalid("Missing path parameter '$name'")
    if (!raw.matches(regex)) throw invalid("Path parameter '$name' is invalid")
    return raw
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    type: String,
    title: String,
    detail: String,
) {
    val problem =
        Problem(
            type = type,
            title = title,
            status = status.value,
            detail = detail,
            instance = request.path(),
        )
    respondText(
        text = ServiceJson.encodeToString(problem),
        contentType = PROBLEM_JSON,
        status = status,
    )
}

private fun invalid(detail: String): ApiException =
    ApiException(
        HttpStatusCode.BadRequest,
        "https://marketlab.dev/problems/invalid-request",
        "Invalid request",
        detail,
    )

private fun notFound(detail: String): ApiException =
    ApiException(
        HttpStatusCode.NotFound,
        "https://marketlab.dev/problems/not-found",
        "Resource not found",
        detail,
    )

private fun constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8),
    )

private fun openApiDescription(): ApiDescription {
    fun operation(summary: String, responseCode: String) =
        buildJsonObject {
            put("summary", summary)
            put(
                "responses",
                buildJsonObject {
                    put(
                        responseCode,
                        buildJsonObject {
                            put("description", "Successful response")
                        },
                    )
                },
            )
        }
    val get = operation("Read resource", "200")
    val post = operation("Submit idempotent operation", "202")
    return ApiDescription(
        paths =
            mapOf(
                "/health/live" to buildJsonObject { put("get", get) },
                "/health/ready" to buildJsonObject { put("get", get) },
                "/metrics" to buildJsonObject { put("get", get) },
                "/api/v1/theories" to buildJsonObject { put("get", get) },
                "/api/v1/social/sources" to buildJsonObject { put("get", get) },
                "/api/v1/social/coverage" to buildJsonObject { put("get", get) },
                "/api/v1/social/models" to buildJsonObject { put("get", get) },
                "/api/v1/snapshots" to buildJsonObject { put("get", get) },
                "/api/v1/ingestions" to buildJsonObject { put("post", post) },
                "/api/v1/runs" to buildJsonObject { put("post", post) },
                "/api/v1/jobs/{id}" to buildJsonObject { put("get", get) },
                "/api/v1/paper-sessions" to
                    buildJsonObject {
                        put("get", get)
                        put("post", post)
                    },
            ),
    )
}

private val PROBLEM_JSON = ContentType.parse("application/problem+json")
private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
private val INSTRUMENT = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}")
private val THEORY_ID = Regex("[a-z][a-z0-9.-]{1,127}")
private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")
private val PARAMETER_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
private val CANDLE_INTERVALS =
    setOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "3d", "1w", "1M")
