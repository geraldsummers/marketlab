package dev.marketlab.runner

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.security.MessageDigest

fun main() {
    val config = RunnerConfig.fromEnvironment()
    embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
        module = { runnerModule(config) },
    ).start(wait = true)
}

fun Application.runnerModule(config: RunnerConfig) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false })
    }
    val launcher = WorkerLauncher(config, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    routing {
        get("/health/live") {
            call.respond(mapOf("status" to "UP"))
        }
        route("/internal/v1") {
            post("/jobs") {
                if (!authorized(call.request.headers["Authorization"], config.bearerToken)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        RunnerProblem("about:blank", "Unauthorized", 401, "A valid runner bearer token is required"),
                    )
                    return@post
                }
                val request = call.receive<LaunchWorkerRequest>()
                runCatching { launcher.launch(request) }
                    .onSuccess { call.respond(HttpStatusCode.Accepted, it) }
                    .onFailure {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            RunnerProblem("about:blank", "Invalid worker request", 400, it.message ?: "Invalid request"),
                        )
                    }
            }
            get("/jobs/{jobId}/{attemptId}") {
                if (!authorized(call.request.headers["Authorization"], config.bearerToken)) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }
                val jobId = call.parameters["jobId"].orEmpty()
                val attemptId = call.parameters["attemptId"].orEmpty()
                launcher.status(jobId, attemptId)
                    ?.let { call.respond(it) }
                    ?: call.respond(HttpStatusCode.NotFound)
            }
            delete("/jobs/{jobId}/{attemptId}") {
                if (!authorized(call.request.headers["Authorization"], config.bearerToken)) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@delete
                }
                val jobId = call.parameters["jobId"].orEmpty()
                val attemptId = call.parameters["attemptId"].orEmpty()
                launcher.cancel(jobId, attemptId)
                    ?.let { call.respond(it) }
                    ?: call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private fun authorized(header: String?, expectedToken: String): Boolean {
    val supplied = header?.removePrefix("Bearer ") ?: return false
    return MessageDigest.isEqual(
        supplied.toByteArray(Charsets.UTF_8),
        expectedToken.toByteArray(Charsets.UTF_8),
    )
}

