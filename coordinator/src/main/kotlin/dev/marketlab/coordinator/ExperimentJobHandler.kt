package dev.marketlab.coordinator

import dev.marketlab.persistence.JobLease

interface ExperimentJobHandler {
    suspend fun execute(lease: JobLease): JobExecutionResult
}
