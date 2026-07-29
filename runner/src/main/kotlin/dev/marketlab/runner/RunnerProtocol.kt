package dev.marketlab.runner

import kotlinx.serialization.Serializable

@Serializable
data class LaunchWorkerRequest(
    val jobId: String,
    val attemptId: String,
    val capabilityId: String,
)

@Serializable
data class RunnerJobResponse(
    val jobId: String,
    val attemptId: String,
    val status: String,
    val exitCode: Int? = null,
    val diagnostic: String? = null,
)

@Serializable
data class RunnerProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
)
