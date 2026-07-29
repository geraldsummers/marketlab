package dev.marketlab.coordinator

import dev.marketlab.persistence.JobLease
import dev.marketlab.persistence.JobRepository
import dev.marketlab.persistence.LeaseLostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException
import java.sql.SQLTransientException
import java.time.Duration
import java.time.Instant

class JobCoordinator(
    private val config: CoordinatorConfig,
    private val jobs: JobRepository,
    private val ingestion: HyperliquidIngestionJobHandler,
    private val experiments: ExperimentJobHandler,
) {
    private val logger = LoggerFactory.getLogger(JobCoordinator::class.java)

    suspend fun run() {
        logger.info("Coordinator {} started", config.workerId)
        var nextReapAt = Instant.EPOCH
        while (currentCoroutineContext().isActive) {
            currentCoroutineContext().ensureActive()
            val now = Instant.now()
            if (!now.isBefore(nextReapAt)) {
                val reaped = io { jobs.reapExpiredLeases() }
                if (reaped > 0) logger.warn("Reaped {} expired job lease(s)", reaped)
                nextReapAt = now.plus(config.reapInterval)
            }
            val lease =
                io {
                    jobs.leaseNext(
                        workerId = config.workerId,
                        leaseDuration = config.leaseDuration,
                        acceptedKinds = ACCEPTED_JOB_KINDS,
                    )
                }
            if (lease == null) {
                delay(config.idlePollInterval.toMillis())
            } else {
                process(lease)
            }
        }
    }

    private suspend fun process(lease: JobLease) {
        val job = lease.job
        logger.info(
            "Leased job {} kind={} attempt={}/{}",
            job.id,
            job.kind,
            job.attemptCount,
            job.maxAttempts,
        )
        try {
            val outcome =
                withLeaseHeartbeat(lease) {
                    when (job.kind) {
                        INGESTION_JOB_KIND -> ingestion.execute(job)
                        EXPERIMENT_JOB_KIND -> experiments.execute(lease)
                        else ->
                            JobExecutionResult.Failed(
                                code = "UNSUPPORTED_JOB_KIND",
                                message = "Coordinator cannot execute job kind ${job.kind}",
                                retryable = false,
                            )
                    }
                }
            finish(lease, outcome)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LeaseLostException) {
            logger.warn("Lease for job {} was lost: {}", job.id, exception.message)
            finishLostOrCancelledLease(lease, exception)
        } catch (exception: PermanentJobException) {
            finish(
                lease,
                JobExecutionResult.Failed(
                    code = exception.code,
                    message = sanitize(exception.message),
                    retryable = false,
                ),
            )
        } catch (exception: Exception) {
            val retryable = exception.isRetryable()
            logger.error(
                "Job {} failed (retryable={}): {}",
                job.id,
                retryable,
                exception.javaClass.simpleName,
                exception,
            )
            finish(
                lease,
                JobExecutionResult.Failed(
                    code = if (retryable) "TRANSIENT_EXECUTION_FAILURE" else "EXECUTION_FAILURE",
                    message = sanitize(exception.message ?: exception.javaClass.simpleName),
                    retryable = retryable,
                    retryDelay = if (retryable) config.retryDelay else Duration.ZERO,
                ),
            )
        }
    }

    private suspend fun <T> withLeaseHeartbeat(
        lease: JobLease,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val heartbeat =
                launch(Dispatchers.IO) {
                    while (true) {
                        delay(config.heartbeatInterval.toMillis())
                        jobs.extendLease(
                            jobId = lease.job.id,
                            workerId = config.workerId,
                            leaseToken = lease.token,
                            leaseDuration = config.leaseDuration,
                        )
                    }
                }
            try {
                block()
            } finally {
                heartbeat.cancelAndJoin()
            }
        }

    private suspend fun finish(
        lease: JobLease,
        outcome: JobExecutionResult,
    ) {
        when (outcome) {
            is JobExecutionResult.Succeeded -> {
                val finished =
                    io {
                        jobs.complete(
                            jobId = lease.job.id,
                            workerId = config.workerId,
                            leaseToken = lease.token,
                            result = outcome.result,
                        )
                    }
                logger.info("Job {} finished with {}", lease.job.id, finished.status)
            }
            is JobExecutionResult.Failed -> {
                val error =
                    buildJsonObject {
                        put("code", outcome.code)
                        put("message", sanitize(outcome.message))
                        put("retryable", outcome.retryable)
                        put("attempt", lease.job.attemptCount)
                    }
                val finished =
                    io {
                        jobs.fail(
                            jobId = lease.job.id,
                            workerId = config.workerId,
                            leaseToken = lease.token,
                            error = error,
                            retryable = outcome.retryable,
                            retryDelay = outcome.retryDelay,
                        )
                    }
                logger.info("Job {} finished with {}", lease.job.id, finished.status)
            }
        }
    }

    private suspend fun finishLostOrCancelledLease(
        lease: JobLease,
        exception: LeaseLostException,
    ) {
        val error =
            buildJsonObject {
                put("code", "LEASE_LOST_OR_CANCELLED")
                put("message", sanitize(exception.message ?: "Lease was lost or cancelled"))
                put("retryable", true)
                put("attempt", lease.job.attemptCount)
            }
        runCatching {
            io {
                jobs.fail(
                    jobId = lease.job.id,
                    workerId = config.workerId,
                    leaseToken = lease.token,
                    error = error,
                    retryable = true,
                    retryDelay = config.retryDelay,
                )
            }
        }.onFailure {
            logger.warn("Could not finalize lost lease for job {}", lease.job.id)
        }
    }

    private fun Exception.isRetryable(): Boolean =
        when (this) {
            is IOException -> true
            is SQLTransientException -> true
            is SQLException ->
                sqlState?.startsWith("08") == true ||
                    sqlState in setOf("40001", "40P01", "55P03", "57P01", "57P02", "57P03")
            is IllegalStateException ->
                message?.startsWith("Data failed closed:") == true ||
                    TRANSIENT_HYPERLIQUID_HTTP.containsMatchIn(message.orEmpty())
            else -> cause is Exception && (cause as Exception).isRetryable()
        }

    private fun sanitize(message: String): String =
        message
            .replace(CONTROL_CHARACTERS, " ")
            .trim()
            .ifBlank { "Unspecified coordinator failure" }
            .take(MAX_ERROR_MESSAGE_CHARS)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val INGESTION_JOB_KIND = "INGESTION"
        const val EXPERIMENT_JOB_KIND = "EXPERIMENT_RUN"
        val ACCEPTED_JOB_KINDS = setOf(INGESTION_JOB_KIND, EXPERIMENT_JOB_KIND)
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        val TRANSIENT_HYPERLIQUID_HTTP =
            Regex("""^Hyperliquid returned HTTP (408|425|429|5\d\d):""")
        const val MAX_ERROR_MESSAGE_CHARS = 1_024
    }
}
