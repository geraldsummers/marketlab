package dev.marketlab.service

import dev.marketlab.persistence.ArtifactRow
import dev.marketlab.persistence.AsyncSubmission
import dev.marketlab.persistence.DataRepository
import dev.marketlab.persistence.ExperimentRunRow
import dev.marketlab.persistence.JobRepository
import dev.marketlab.persistence.JobRow
import dev.marketlab.persistence.OrderProjection
import dev.marketlab.persistence.PaperEventRow
import dev.marketlab.persistence.PaperRepository
import dev.marketlab.persistence.PaperSessionRow
import dev.marketlab.persistence.PaperSessionStatus
import dev.marketlab.persistence.PositionProjection
import dev.marketlab.persistence.QualityFindingRow
import dev.marketlab.persistence.RunRepository
import dev.marketlab.persistence.SnapshotRow
import dev.marketlab.persistence.SubmissionRepository
import dev.marketlab.persistence.TheoryRepository
import dev.marketlab.persistence.TheoryVersionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface ServiceBackend {
    suspend fun ready(): Boolean
    suspend fun theories(): List<TheoryView>
    suspend fun snapshots(): List<SnapshotView>
    suspend fun snapshot(id: UUID): SnapshotView?
    suspend fun qualityFindings(snapshotId: UUID): List<QualityFindingView>
    suspend fun submitIngestion(key: String, requestHash: String, request: IngestionRequest): AcceptedResponse
    suspend fun submitRun(key: String, requestHash: String, request: RunRequest): AcceptedResponse
    suspend fun run(id: UUID): RunView?
    suspend fun artifacts(runId: UUID): List<ArtifactView>
    suspend fun job(id: UUID): JobView?
    suspend fun cancelJob(id: UUID, key: String, requestHash: String): Pair<JobView, Boolean>
    suspend fun createPaperSession(
        key: String,
        requestHash: String,
        request: PaperSessionRequest,
    ): Pair<PaperSessionView, Boolean>

    suspend fun paperSession(id: UUID): PaperSessionView?
    suspend fun paperSessions(): List<PaperSessionView>
    suspend fun transitionPaperSession(
        id: UUID,
        action: PaperAction,
        key: String,
        requestHash: String,
        request: PaperActionRequest,
    ): PaperActionResponse

    suspend fun paperEvents(id: UUID, afterSequence: Long): List<PaperEventView>
    suspend fun paperPositions(id: UUID): List<PositionView>
    suspend fun paperOrders(id: UUID): List<OrderView>
}

class DatabaseServiceBackend(
    private val dataSource: DataSource,
    private val data: DataRepository = DataRepository(dataSource),
    private val theoryRepository: TheoryRepository = TheoryRepository(dataSource),
    private val runRepository: RunRepository = RunRepository(dataSource),
    private val jobRepository: JobRepository = JobRepository(dataSource),
    private val submissions: SubmissionRepository = SubmissionRepository(dataSource),
    private val paper: PaperRepository = PaperRepository(dataSource),
) : ServiceBackend {
    override suspend fun ready(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT 1").use { statement ->
                        statement.executeQuery().use { result -> result.next() && result.getInt(1) == 1 }
                    }
                }
            }.getOrDefault(false)
        }

    override suspend fun theories(): List<TheoryView> =
        io { theoryRepository.list().map(TheoryVersionRow::toView) }

    override suspend fun snapshots(): List<SnapshotView> =
        io { data.listSnapshots().map(SnapshotRow::toView) }

    override suspend fun snapshot(id: UUID): SnapshotView? =
        io { data.getSnapshot(id)?.toView() }

    override suspend fun qualityFindings(snapshotId: UUID): List<QualityFindingView> =
        io { data.listQualityFindings(snapshotId).map(QualityFindingRow::toView) }

    override suspend fun submitIngestion(
        key: String,
        requestHash: String,
        request: IngestionRequest,
    ): AcceptedResponse =
        io {
            val payload =
                buildJsonObject {
                    put("source", request.source.name)
                    put(
                        "instruments",
                        JsonArray(request.instruments.map(::JsonPrimitive)),
                    )
                    put(
                        "dataKinds",
                        JsonArray(request.dataKinds.map { JsonPrimitive(it.name) }),
                    )
                    request.startAt?.let { put("startAt", it) }
                    request.endAt?.let { put("endAt", it) }
                    request.candleInterval?.let { put("candleInterval", it) }
                    request.l2SignificantFigures?.let { put("l2SignificantFigures", it) }
                    request.l2Mantissa?.let { put("l2Mantissa", it) }
                }
            submissions.submitIngestion(
                scope = "POST:/api/v1/ingestions",
                idempotencyKey = key,
                requestHash = requestHash,
                payload = payload,
            ).toAccepted()
        }

    override suspend fun submitRun(
        key: String,
        requestHash: String,
        request: RunRequest,
    ): AcceptedResponse =
        io {
            validateRegisteredParameters(request)
            submissions.submitRun(
                scope = "POST:/api/v1/runs",
                idempotencyKey = key,
                requestHash = requestHash,
                theoryId = request.theoryId,
                theoryVersion = request.theoryVersion,
                snapshotId = UUID.fromString(request.snapshotId),
                parameters = request.parameters,
            ).toAccepted()
        }

    private fun validateRegisteredParameters(request: RunRequest) {
        val theory = theoryRepository.get(request.theoryId, request.theoryVersion)
            ?: throw dev.marketlab.persistence.PersistenceNotFoundException(
                "Theory ${request.theoryId}@${request.theoryVersion} is not registered",
            )
        val specifications =
            theory.plan["parameterSpace"]
                ?.jsonObject
                ?.get("parameters")
                ?.jsonArray
                .orEmpty()
                .associateBy { specification ->
                    specification.jsonObject.getValue("name").jsonPrimitive.content
                }
        request.parameters.forEach { (name, requested) ->
            val specification =
                specifications[name]
                    ?: throw IllegalArgumentException(
                        "Theory parameter '$name' is not declared by ${request.theoryId}@${request.theoryVersion}",
                    )
            val allowed = specification.jsonObject.getValue("values").jsonArray
            if (allowed.none { it.scalarEquivalent(requested) }) {
                throw IllegalArgumentException(
                    "Theory parameter '$name' is outside its registered finite search space",
                )
            }
        }
    }

    override suspend fun run(id: UUID): RunView? =
        io { runRepository.get(id)?.toView() }

    override suspend fun artifacts(runId: UUID): List<ArtifactView> =
        io { runRepository.listArtifacts(runId).map(ArtifactRow::toView) }

    override suspend fun job(id: UUID): JobView? =
        io { jobRepository.get(id)?.toView() }

    override suspend fun cancelJob(
        id: UUID,
        key: String,
        requestHash: String,
    ): Pair<JobView, Boolean> =
        io {
            val (job, replayed) =
                submissions.cancelJob(
                    scope = "POST:/api/v1/jobs/$id/cancel",
                    idempotencyKey = key,
                    requestHash = requestHash,
                    jobId = id,
                )
            job.toView() to replayed
        }

    override suspend fun createPaperSession(
        key: String,
        requestHash: String,
        request: PaperSessionRequest,
    ): Pair<PaperSessionView, Boolean> =
        io {
            val (row, replayed) =
                paper.createSession(
                    scope = "POST:/api/v1/paper-sessions",
                    idempotencyKey = key,
                    requestHash = requestHash,
                    theoryId = request.theoryId,
                    theoryVersion = request.theoryVersion,
                    runId = UUID.fromString(request.runId),
                    initialEquity = BigDecimal(request.initialEquity),
                    riskProfile = request.riskProfile,
                )
            row.toView() to replayed
        }

    override suspend fun paperSession(id: UUID): PaperSessionView? =
        io { paper.get(id)?.toView() }

    override suspend fun paperSessions(): List<PaperSessionView> =
        io { paper.list().map(PaperSessionRow::toView) }

    override suspend fun transitionPaperSession(
        id: UUID,
        action: PaperAction,
        key: String,
        requestHash: String,
        request: PaperActionRequest,
    ): PaperActionResponse =
        io {
            val target =
                when (action) {
                    PaperAction.START -> PaperSessionStatus.RUNNING
                    PaperAction.PAUSE -> PaperSessionStatus.PAUSED
                    PaperAction.STOP -> PaperSessionStatus.STOPPED
                }
            val payload =
                buildJsonObject {
                    request.reason?.let { put("reason", it) }
                }
            val event = paper.transition(id, target, key, requestHash, payload = payload)
            PaperActionResponse(
                sessionId = id.toString(),
                eventId = event.id.toString(),
                sequence = event.sequence,
                status = target.name,
            )
        }

    override suspend fun paperEvents(id: UUID, afterSequence: Long): List<PaperEventView> =
        io { paper.listEvents(id, afterSequence).map(PaperEventRow::toView) }

    override suspend fun paperPositions(id: UUID): List<PositionView> =
        io { paper.positions(id).map(PositionProjection::toView) }

    override suspend fun paperOrders(id: UUID): List<OrderView> =
        io { paper.orders(id).map(OrderProjection::toView) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

private fun JsonElement.scalarEquivalent(other: JsonElement): Boolean {
    val left = this as? JsonPrimitive ?: return false
    val right = other as? JsonPrimitive ?: return false
    if (left.isString || right.isString) return left.isString == right.isString && left.content == right.content
    val leftBoolean = left.booleanOrNull
    val rightBoolean = right.booleanOrNull
    if (leftBoolean != null || rightBoolean != null) return leftBoolean == rightBoolean
    val leftNumber = left.content.toBigDecimalOrNull()
    val rightNumber = right.content.toBigDecimalOrNull()
    return leftNumber != null && rightNumber != null && leftNumber.compareTo(rightNumber) == 0
}

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun AsyncSubmission.toAccepted(): AcceptedResponse =
    AcceptedResponse(
        resourceType = resourceType,
        resourceId = resourceId,
        jobId = job.id.toString(),
        status = job.status.name,
        acceptedAt = job.createdAt.toString(),
        replayed = replayed,
    )

private fun TheoryVersionRow.toView() =
    TheoryView(theoryId, version, planHash, descriptor, registeredAt.toString())

private fun SnapshotRow.toView() =
    SnapshotView(
        id.toString(),
        manifestHash,
        status.name,
        createdAt.toString(),
        objectCount,
        fatalFindingCount,
        metadata,
    )

private fun QualityFindingRow.toView() =
    QualityFindingView(
        id,
        snapshotId?.toString(),
        dataObjectId?.toString(),
        severity.name,
        code,
        eventTimeStart?.toString(),
        eventTimeEnd?.toString(),
        details,
        detectedAt.toString(),
    )

private fun ExperimentRunRow.toView() =
    RunView(
        id.toString(),
        theoryId,
        theoryVersion,
        theoryPlanHash,
        snapshotId.toString(),
        status.name,
        promotionStatus.name,
        parameters,
        manifest,
        metrics,
        failure,
        createdAt.toString(),
        startedAt?.toString(),
        completedAt?.toString(),
    )

private fun JobRow.toView() =
    JobView(
        id.toString(),
        kind,
        resourceType,
        resourceId,
        status.name,
        attemptCount,
        maxAttempts,
        cancellationRequested,
        availableAt.toString(),
        createdAt.toString(),
        updatedAt.toString(),
        completedAt?.toString(),
        lastError,
    )

private fun ArtifactRow.toView() =
    ArtifactView(
        id.toString(),
        runId?.toString(),
        kind,
        contentHash,
        objectUri,
        byteCount,
        mediaType,
        manifest,
        createdAt.toString(),
    )

private fun PaperSessionRow.toView() =
    PaperSessionView(
        id.toString(),
        runId?.toString(),
        theoryId,
        theoryVersion,
        status.name,
        initialEquity.toPlainString(),
        riskProfile,
        lastSequence,
        createdAt.toString(),
        updatedAt.toString(),
        stoppedAt?.toString(),
    )

private fun PaperEventRow.toView() =
    PaperEventView(
        id.toString(),
        sessionId.toString(),
        sequence,
        eventType,
        eventTime.toString(),
        observedAt.toString(),
        marketEventRef,
        payload,
        createdAt.toString(),
    )

private fun PositionProjection.toView() =
    PositionView(
        instrument,
        quantity.toPlainString(),
        averagePrice?.toPlainString(),
        markPrice?.toPlainString(),
        realizedPnl.toPlainString(),
        unrealizedPnl.toPlainString(),
    )

private fun OrderProjection.toView() =
    OrderView(
        orderId.toString(),
        instrument,
        side,
        orderType,
        status,
        quantity.toPlainString(),
        filledQuantity.toPlainString(),
        limitPrice?.toPlainString(),
    )
