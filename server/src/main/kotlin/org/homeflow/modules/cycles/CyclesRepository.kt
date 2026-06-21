package org.homeflow.modules.cycles

import kotlinx.datetime.LocalDate
import org.homeflow.db.Cycles
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A `cycles` row as read back by the repository. */
data class CycleRow(
    val id: UUID,
    val userId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/**
 * DB access for the user's cycles. Every query is scoped to the authenticated
 * `userId` (taken from the JWT principal, never the request body) — a cross-user
 * read simply returns nothing, surfacing as `RESOURCE_NOT_FOUND` upstream. No
 * business logic lives here (see `__docs/ARCHITECTURE-server.md`).
 */
class CyclesRepository(
    private val db: Database,
) {
    /** All of the user's cycles, newest first (by start date, then creation). */
    fun findAllByUser(userId: UUID): List<CycleRow> =
        transaction(db) {
            Cycles
                .selectAll()
                .where { Cycles.userId eq userId }
                .orderBy(Cycles.startDate to SortOrder.DESC, Cycles.createdAt to SortOrder.DESC)
                .map(::toRow)
        }

    /** A single cycle owned by [userId], or null if it does not exist / belongs to someone else. */
    fun findById(
        userId: UUID,
        cycleId: UUID,
    ): CycleRow? =
        transaction(db) {
            Cycles
                .selectAll()
                .where { (Cycles.id eq cycleId) and (Cycles.userId eq userId) }
                .map(::toRow)
                .singleOrNull()
        }

    /** The user's currently open cycle (no `end_date`), or null if none is open. */
    fun findOpen(userId: UUID): CycleRow? =
        transaction(db) {
            Cycles
                .selectAll()
                .where { (Cycles.userId eq userId) and Cycles.endDate.isNull() }
                .orderBy(Cycles.startDate to SortOrder.DESC)
                .map(::toRow)
                .firstOrNull()
        }

    /**
     * Inserts a new cycle, first closing any open cycle by setting its `end_date` to
     * [previousEndDate] — both in one transaction so a new cycle never coexists with
     * a lingering open one. [previousEndDate] (the business rule `startDate - 1 day`)
     * is computed by the service.
     */
    fun insertClosingOpen(
        userId: UUID,
        startDate: LocalDate,
        previousEndDate: LocalDate,
    ): CycleRow =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            Cycles.update({ (Cycles.userId eq userId) and Cycles.endDate.isNull() }) {
                it[endDate] = previousEndDate
                it[updatedAt] = now
            }
            val id = UUID.randomUUID()
            Cycles.insert {
                it[Cycles.id] = id
                it[Cycles.userId] = userId
                it[Cycles.startDate] = startDate
                it[endDate] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            CycleRow(id, userId, startDate, endDate = null, createdAt = now, updatedAt = now)
        }

    /**
     * Sets [endDate] on the user's cycle [cycleId], returning the updated row (or null
     * if no such cycle is owned by the user). Scoped to [userId] so it can never close
     * another user's cycle.
     */
    fun updateEndDate(
        userId: UUID,
        cycleId: UUID,
        endDate: LocalDate,
    ): CycleRow? =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val updated =
                Cycles.update({ (Cycles.id eq cycleId) and (Cycles.userId eq userId) }) {
                    it[Cycles.endDate] = endDate
                    it[updatedAt] = now
                }
            if (updated == 0) {
                null
            } else {
                Cycles
                    .selectAll()
                    .where { Cycles.id eq cycleId }
                    .map(::toRow)
                    .single()
            }
        }

    private fun toRow(row: ResultRow): CycleRow =
        CycleRow(
            id = row[Cycles.id],
            userId = row[Cycles.userId],
            startDate = row[Cycles.startDate],
            endDate = row[Cycles.endDate],
            createdAt = row[Cycles.createdAt],
            updatedAt = row[Cycles.updatedAt],
        )
}
