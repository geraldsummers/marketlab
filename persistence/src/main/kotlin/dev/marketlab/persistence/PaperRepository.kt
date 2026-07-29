package dev.marketlab.persistence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class PaperSessionStatus {
    CREATED,
    RUNNING,
    PAUSED,
    STOPPED,
    HALTED,
}

data class PaperSessionRow(
    val id: UUID,
    val runId: UUID?,
    val theoryId: String,
    val theoryVersion: String,
    val status: PaperSessionStatus,
    val initialEquity: BigDecimal,
    val riskProfile: String,
    val lastSequence: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val stoppedAt: Instant?,
)

data class PaperEventRow(
    val id: UUID,
    val sessionId: UUID,
    val sequence: Long,
    val eventType: String,
    val eventTime: Instant,
    val observedAt: Instant,
    val marketEventRef: JsonElement?,
    val payload: JsonObject,
    val idempotencyKey: String,
    val createdAt: Instant,
)

data class BalanceProjection(
    val equity: BigDecimal,
    val cash: BigDecimal,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val fees: BigDecimal,
    val funding: BigDecimal,
)

data class PositionProjection(
    val instrument: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal?,
    val markPrice: BigDecimal?,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
)

data class OrderProjection(
    val orderId: UUID,
    val instrument: String,
    val side: String,
    val orderType: String,
    val status: String,
    val quantity: BigDecimal,
    val filledQuantity: BigDecimal,
    val limitPrice: BigDecimal?,
)

data class PaperProjectionUpdate(
    val balance: BalanceProjection? = null,
    val positions: List<PositionProjection> = emptyList(),
    val orders: List<OrderProjection> = emptyList(),
)

class PaperRepository(
    private val dataSource: DataSource,
    private val idempotency: IdempotencyRepository = IdempotencyRepository(dataSource),
) {
    fun createSession(
        scope: String,
        idempotencyKey: String,
        requestHash: String,
        theoryId: String,
        theoryVersion: String,
        runId: UUID,
        initialEquity: BigDecimal,
        riskProfile: String,
    ): Pair<PaperSessionRow, Boolean> {
        require(initialEquity > BigDecimal.ZERO)
        require(riskProfile.matches(Regex("[A-Z][A-Z0-9_]{1,63}")))
        return dataSource.transaction { connection ->
            idempotency.lock(connection, scope, idempotencyKey)
            idempotency.find(connection, scope, idempotencyKey)?.let { existing ->
                idempotency.requireMatching(existing, requestHash)
                val existingId = UUID.fromString(existing.resourceId)
                val session = get(connection, existingId)
                    ?: error("Idempotency record points to missing paper session $existingId")
                return@transaction session to true
            }
            requireTheory(connection, theoryId, theoryVersion)
            requireRunMatches(connection, runId, theoryId, theoryVersion)
            val id = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO paper_sessions (
                    id, run_id, theory_id, theory_version, status,
                    initial_equity, risk_profile
                )
                VALUES (?, ?, ?, ?, 'CREATED', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, runId)
                statement.setString(3, theoryId)
                statement.setString(4, theoryVersion)
                statement.setBigDecimal(5, initialEquity)
                statement.setString(6, riskProfile)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO paper_balances_projection (
                    session_id, equity, cash, realized_pnl, unrealized_pnl,
                    fees, funding, as_of_sequence
                )
                VALUES (?, ?, ?, 0, 0, 0, 0, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setBigDecimal(2, initialEquity)
                statement.setBigDecimal(3, initialEquity)
                statement.executeUpdate()
            }
            idempotency.insert(
                connection = connection,
                scope = scope,
                key = idempotencyKey,
                requestHash = requestHash,
                resourceType = "paper-session",
                resourceId = id.toString(),
                responseStatus = 201,
                responseBody =
                    buildJsonObject {
                        put("sessionId", id.toString())
                        put("status", PaperSessionStatus.CREATED.name)
                    },
            )
            (get(connection, id) ?: error("Inserted paper session disappeared")) to false
        }
    }

    fun get(id: UUID): PaperSessionRow? =
        dataSource.read { connection -> get(connection, id) }

    fun list(limit: Int = 100): List<PaperSessionRow> {
        require(limit in 1..500)
        return dataSource.read { connection ->
            connection.prepareStatement(
                "SELECT * FROM paper_sessions ORDER BY created_at DESC, id DESC LIMIT ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapSession(result))
                    }
                }
            }
        }
    }

    fun transition(
        sessionId: UUID,
        target: PaperSessionStatus,
        idempotencyKey: String,
        requestHash: String,
        eventTime: Instant = Instant.now(),
        payload: JsonObject = buildJsonObject {},
    ): PaperEventRow {
        require(target in setOf(PaperSessionStatus.RUNNING, PaperSessionStatus.PAUSED, PaperSessionStatus.STOPPED))
        return append(
            sessionId = sessionId,
            eventType = "SESSION_${target.name}",
            eventTime = eventTime,
            observedAt = Instant.now(),
            marketEventRef = null,
            payload = payload,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            projection = PaperProjectionUpdate(),
            targetStatus = target,
        )
    }

    fun halt(
        sessionId: UUID,
        reason: JsonObject,
        idempotencyKey: String,
        requestHash: String,
        eventTime: Instant = Instant.now(),
    ): PaperEventRow =
        append(
            sessionId = sessionId,
            eventType = "SESSION_HALTED",
            eventTime = eventTime,
            observedAt = Instant.now(),
            marketEventRef = null,
            payload = reason,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            projection = PaperProjectionUpdate(),
            targetStatus = PaperSessionStatus.HALTED,
        )

    fun appendEvent(
        sessionId: UUID,
        eventType: String,
        eventTime: Instant,
        observedAt: Instant,
        marketEventRef: JsonElement?,
        payload: JsonObject,
        idempotencyKey: String,
        requestHash: String,
        projection: PaperProjectionUpdate = PaperProjectionUpdate(),
    ): PaperEventRow {
        require(eventType.matches(Regex("[A-Z][A-Z0-9_]{1,127}")))
        require(marketEventRef != null || eventType.startsWith("SESSION_")) {
            "Trading/accounting events must reference the immutable market event that caused them"
        }
        return append(
            sessionId = sessionId,
            eventType = eventType,
            eventTime = eventTime,
            observedAt = observedAt,
            marketEventRef = marketEventRef,
            payload = payload,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            projection = projection,
            targetStatus = null,
        )
    }

    fun listEvents(sessionId: UUID, afterSequence: Long = 0, limit: Int = 500): List<PaperEventRow> {
        require(afterSequence >= 0)
        require(limit in 1..2_000)
        return dataSource.read { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM paper_events
                WHERE session_id = ? AND sequence > ?
                ORDER BY sequence
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setLong(2, afterSequence)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(mapEvent(result))
                    }
                }
            }
        }
    }

    fun positions(sessionId: UUID): List<PositionProjection> =
        dataSource.read { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM paper_positions_projection
                WHERE session_id = ?
                ORDER BY instrument
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                PositionProjection(
                                    instrument = result.getString("instrument"),
                                    quantity = result.getBigDecimal("quantity"),
                                    averagePrice = result.getBigDecimal("average_price"),
                                    markPrice = result.getBigDecimal("mark_price"),
                                    realizedPnl = result.getBigDecimal("realized_pnl"),
                                    unrealizedPnl = result.getBigDecimal("unrealized_pnl"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun orders(sessionId: UUID): List<OrderProjection> =
        dataSource.read { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM paper_orders_projection
                WHERE session_id = ?
                ORDER BY instrument, order_id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                OrderProjection(
                                    orderId = result.uuid("order_id"),
                                    instrument = result.getString("instrument"),
                                    side = result.getString("side"),
                                    orderType = result.getString("order_type"),
                                    status = result.getString("status"),
                                    quantity = result.getBigDecimal("quantity"),
                                    filledQuantity = result.getBigDecimal("filled_quantity"),
                                    limitPrice = result.getBigDecimal("limit_price"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun append(
        sessionId: UUID,
        eventType: String,
        eventTime: Instant,
        observedAt: Instant,
        marketEventRef: JsonElement?,
        payload: JsonObject,
        idempotencyKey: String,
        requestHash: String,
        projection: PaperProjectionUpdate,
        targetStatus: PaperSessionStatus?,
    ): PaperEventRow = dataSource.transaction { connection ->
        val scope = "paper-event:$sessionId"
        idempotency.lock(connection, scope, idempotencyKey)
        idempotency.find(connection, scope, idempotencyKey)?.let { existing ->
            idempotency.requireMatching(existing, requestHash)
            val existingId = UUID.fromString(existing.resourceId)
            return@transaction findEvent(connection, existingId)
                ?: error("Idempotency record points to missing paper event $existingId")
        }
        val session =
            connection.prepareStatement(
                "SELECT * FROM paper_sessions WHERE id = ? FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        throw PersistenceNotFoundException("Paper session $sessionId does not exist")
                    }
                    mapSession(result)
                }
            }
        if (targetStatus == null && session.status != PaperSessionStatus.RUNNING) {
            throw PersistenceConflictException("Paper session $sessionId is not active")
        }
        if (targetStatus != null) requireTransition(session.status, targetStatus)
        if (targetStatus == PaperSessionStatus.RUNNING) {
            val runId =
                session.runId
                    ?: throw PersistenceConflictException(
                        "Legacy paper session $sessionId has no promotion-qualified run",
                    )
            requireRunMatches(connection, runId, session.theoryId, session.theoryVersion)
        }

        val sequence = session.lastSequence + 1
        val eventId = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO paper_events (
                id, session_id, sequence, event_type, event_time, observed_at,
                market_event_ref, payload, idempotency_key
            )
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, sessionId)
            statement.setLong(3, sequence)
            statement.setString(4, eventType)
            statement.setInstant(5, eventTime)
            statement.setInstant(6, observedAt)
            statement.setString(7, marketEventRef?.toString())
            statement.setString(8, payload.toString())
            statement.setString(9, idempotencyKey)
            statement.executeUpdate()
        }
        applyProjection(connection, sessionId, sequence, projection)
        connection.prepareStatement(
            """
            UPDATE paper_sessions
            SET last_sequence = ?,
                status = COALESCE(?, status),
                stopped_at = CASE WHEN ? = 'STOPPED' THEN clock_timestamp() ELSE stopped_at END,
                updated_at = clock_timestamp()
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sequence)
            statement.setString(2, targetStatus?.name)
            statement.setString(3, targetStatus?.name)
            statement.setObject(4, sessionId)
            check(statement.executeUpdate() == 1)
        }
        idempotency.insert(
            connection = connection,
            scope = scope,
            key = idempotencyKey,
            requestHash = requestHash,
            resourceType = "paper-event",
            resourceId = eventId.toString(),
            responseStatus = 200,
            responseBody =
                buildJsonObject {
                    put("eventId", eventId.toString())
                    put("sessionId", sessionId.toString())
                    put("sequence", sequence)
                },
        )
        findEvent(connection, eventId) ?: error("Inserted paper event disappeared")
    }

    private fun applyProjection(
        connection: Connection,
        sessionId: UUID,
        sequence: Long,
        update: PaperProjectionUpdate,
    ) {
        update.balance?.let { balance ->
            connection.prepareStatement(
                """
                INSERT INTO paper_balances_projection (
                    session_id, equity, cash, realized_pnl, unrealized_pnl,
                    fees, funding, as_of_sequence
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE
                SET equity = EXCLUDED.equity,
                    cash = EXCLUDED.cash,
                    realized_pnl = EXCLUDED.realized_pnl,
                    unrealized_pnl = EXCLUDED.unrealized_pnl,
                    fees = EXCLUDED.fees,
                    funding = EXCLUDED.funding,
                    as_of_sequence = EXCLUDED.as_of_sequence
                WHERE paper_balances_projection.as_of_sequence < EXCLUDED.as_of_sequence
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setBigDecimal(2, balance.equity)
                statement.setBigDecimal(3, balance.cash)
                statement.setBigDecimal(4, balance.realizedPnl)
                statement.setBigDecimal(5, balance.unrealizedPnl)
                statement.setBigDecimal(6, balance.fees)
                statement.setBigDecimal(7, balance.funding)
                statement.setLong(8, sequence)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO paper_positions_projection (
                session_id, instrument, quantity, average_price, mark_price,
                realized_pnl, unrealized_pnl, as_of_sequence
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id, instrument) DO UPDATE
            SET quantity = EXCLUDED.quantity,
                average_price = EXCLUDED.average_price,
                mark_price = EXCLUDED.mark_price,
                realized_pnl = EXCLUDED.realized_pnl,
                unrealized_pnl = EXCLUDED.unrealized_pnl,
                as_of_sequence = EXCLUDED.as_of_sequence
            WHERE paper_positions_projection.as_of_sequence < EXCLUDED.as_of_sequence
            """.trimIndent(),
        ).use { statement ->
            update.positions.forEach { position ->
                statement.setObject(1, sessionId)
                statement.setString(2, position.instrument)
                statement.setBigDecimal(3, position.quantity)
                statement.setBigDecimal(4, position.averagePrice)
                statement.setBigDecimal(5, position.markPrice)
                statement.setBigDecimal(6, position.realizedPnl)
                statement.setBigDecimal(7, position.unrealizedPnl)
                statement.setLong(8, sequence)
                statement.addBatch()
            }
            if (update.positions.isNotEmpty()) statement.executeBatch()
        }
        connection.prepareStatement(
            """
            INSERT INTO paper_orders_projection (
                session_id, order_id, instrument, side, order_type, status,
                quantity, filled_quantity, limit_price, as_of_sequence
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id, order_id) DO UPDATE
            SET status = EXCLUDED.status,
                filled_quantity = EXCLUDED.filled_quantity,
                as_of_sequence = EXCLUDED.as_of_sequence
            WHERE paper_orders_projection.as_of_sequence < EXCLUDED.as_of_sequence
            """.trimIndent(),
        ).use { statement ->
            update.orders.forEach { order ->
                statement.setObject(1, sessionId)
                statement.setObject(2, order.orderId)
                statement.setString(3, order.instrument)
                statement.setString(4, order.side)
                statement.setString(5, order.orderType)
                statement.setString(6, order.status)
                statement.setBigDecimal(7, order.quantity)
                statement.setBigDecimal(8, order.filledQuantity)
                statement.setBigDecimal(9, order.limitPrice)
                statement.setLong(10, sequence)
                statement.addBatch()
            }
            if (update.orders.isNotEmpty()) statement.executeBatch()
        }
    }

    private fun requireTransition(from: PaperSessionStatus, to: PaperSessionStatus) {
        val allowed =
            when (from) {
                PaperSessionStatus.CREATED -> setOf(PaperSessionStatus.RUNNING, PaperSessionStatus.STOPPED)
                PaperSessionStatus.RUNNING ->
                    setOf(PaperSessionStatus.PAUSED, PaperSessionStatus.STOPPED, PaperSessionStatus.HALTED)
                PaperSessionStatus.PAUSED ->
                    setOf(PaperSessionStatus.RUNNING, PaperSessionStatus.STOPPED, PaperSessionStatus.HALTED)
                PaperSessionStatus.HALTED -> setOf(PaperSessionStatus.STOPPED)
                PaperSessionStatus.STOPPED -> emptySet()
            }
        if (to !in allowed) throw PersistenceConflictException("Paper session cannot transition from $from to $to")
    }

    private fun requireTheory(connection: Connection, theoryId: String, theoryVersion: String) {
        connection.prepareStatement(
            "SELECT enabled FROM theory_versions WHERE theory_id = ? AND version = ?",
        ).use { statement ->
            statement.setString(1, theoryId)
            statement.setString(2, theoryVersion)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw PersistenceNotFoundException("Theory $theoryId@$theoryVersion is not registered")
                }
                if (!result.getBoolean("enabled")) {
                    throw PersistenceConflictException("Theory $theoryId@$theoryVersion is disabled")
                }
            }
        }
    }

    private fun requireRunMatches(
        connection: Connection,
        runId: UUID,
        theoryId: String,
        theoryVersion: String,
    ) {
        connection.prepareStatement(
            """
            SELECT status, promotion_status, theory_id, theory_version
            FROM experiment_runs
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, runId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw PersistenceNotFoundException("Run $runId does not exist")
                if (
                    result.getString("theory_id") != theoryId ||
                    result.getString("theory_version") != theoryVersion
                ) {
                    throw PersistenceConflictException("Run $runId belongs to a different theory version")
                }
                if (result.getString("status") != RunStatus.SUCCEEDED.name) {
                    throw PersistenceConflictException("Run $runId has not succeeded")
                }
                if (result.getString("promotion_status") != PromotionStatus.PASSED.name) {
                    throw PersistenceConflictException("Run $runId has not passed its registered promotion gate")
                }
            }
        }
    }

    private fun get(connection: Connection, id: UUID): PaperSessionRow? =
        connection.prepareStatement("SELECT * FROM paper_sessions WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) mapSession(result) else null
            }
        }

    private fun findEvent(connection: Connection, id: UUID): PaperEventRow? =
        connection.prepareStatement("SELECT * FROM paper_events WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) mapEvent(result) else null
            }
        }

    private fun mapSession(result: java.sql.ResultSet): PaperSessionRow =
        PaperSessionRow(
            id = result.uuid("id"),
            runId = result.getObject("run_id", UUID::class.java),
            theoryId = result.getString("theory_id"),
            theoryVersion = result.getString("theory_version"),
            status = PaperSessionStatus.valueOf(result.getString("status")),
            initialEquity = result.getBigDecimal("initial_equity"),
            riskProfile = result.getString("risk_profile"),
            lastSequence = result.getLong("last_sequence"),
            createdAt = result.instant("created_at"),
            updatedAt = result.instant("updated_at"),
            stoppedAt = result.instantOrNull("stopped_at"),
        )

    private fun mapEvent(result: java.sql.ResultSet): PaperEventRow =
        PaperEventRow(
            id = result.uuid("id"),
            sessionId = result.uuid("session_id"),
            sequence = result.getLong("sequence"),
            eventType = result.getString("event_type"),
            eventTime = result.instant("event_time"),
            observedAt = result.instant("observed_at"),
            marketEventRef = result.jsonOrNull("market_event_ref"),
            payload = result.jsonObject("payload"),
            idempotencyKey = result.getString("idempotency_key"),
            createdAt = result.instant("created_at"),
        )
}
