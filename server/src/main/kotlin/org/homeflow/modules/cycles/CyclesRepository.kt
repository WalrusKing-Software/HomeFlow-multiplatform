package org.homeflow.modules.cycles

import kotlinx.datetime.LocalDate
import org.homeflow.db.Cycles
import org.homeflow.db.DailyLogs
import org.homeflow.modules.sync.ChangeLogRepository
import org.homeflow.modules.sync.ChangeLogRepository.Companion.TYPE_CYCLE
import org.homeflow.modules.sync.ChangeLogRepository.Companion.TYPE_DAY
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
    val deletedAt: OffsetDateTime? = null,
)

/**
 * DB access for the user's cycles. Every query is scoped to the authenticated
 * `userId` (taken from the JWT principal, never the request body) — a cross-user
 * read simply returns nothing, surfacing as `RESOURCE_NOT_FOUND` upstream. No
 * business logic lives here (see `__docs/ARCHITECTURE-server.md`).
 *
 * All write methods call [changeLogRepository.record] inside the same Exposed
 * transaction so the change entry and the data row are atomic (Phase 16a D-16a.3).
 */
class CyclesRepository(
    private val db: Database,
    private val changeLogRepository: ChangeLogRepository,
) {
    /** All of the user's live (non-deleted) cycles, newest first (by start date, then creation). */
    fun findAllByUser(userId: UUID): List<CycleRow> =
        transaction(db) {
            Cycles
                .selectAll()
                .where { (Cycles.userId eq userId) and Cycles.deletedAt.isNull() }
                .orderBy(Cycles.startDate to SortOrder.DESC, Cycles.createdAt to SortOrder.DESC)
                .map(::toRow)
        }

    /** A single live cycle owned by [userId], or null if it does not exist / belongs to someone else. */
    fun findById(
        userId: UUID,
        cycleId: UUID,
    ): CycleRow? =
        transaction(db) {
            Cycles
                .selectAll()
                .where {
                    (Cycles.id eq cycleId) and (Cycles.userId eq userId) and Cycles.deletedAt.isNull()
                }
                .map(::toRow)
                .singleOrNull()
        }

    /**
     * A single cycle owned by [userId] whether live or tombstoned.
     * Used by the sync pull to include tombstones in the wire response.
     */
    fun findByIdIncludingDeleted(
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

    /** The user's currently open (non-deleted) cycle (no `end_date`), or null if none is open. */
    fun findOpen(userId: UUID): CycleRow? =
        transaction(db) {
            Cycles
                .selectAll()
                .where {
                    (Cycles.userId eq userId) and Cycles.endDate.isNull() and Cycles.deletedAt.isNull()
                }
                .orderBy(Cycles.startDate to SortOrder.DESC)
                .map(::toRow)
                .firstOrNull()
        }

    /** All live cycles for a user, in ascending start_date order. Used by the sync service. */
    fun findAllByUserAscending(userId: UUID): List<CycleRow> =
        transaction(db) {
            Cycles
                .selectAll()
                .where { (Cycles.userId eq userId) and Cycles.deletedAt.isNull() }
                .orderBy(Cycles.startDate to SortOrder.ASC)
                .map(::toRow)
        }

    /**
     * Inserts a new cycle, first closing any open cycle by setting its `end_date` to
     * [previousEndDate] — both in one transaction so a new cycle never coexists with
     * a lingering open one. [previousEndDate] (the business rule `startDate - 1 day`)
     * is computed by the service.
     *
     * Records a `cycle` change for the newly created cycle (and the closed one, if any).
     */
    fun insertClosingOpen(
        userId: UUID,
        startDate: LocalDate,
        previousEndDate: LocalDate,
        id: UUID = UUID.randomUUID(),
    ): CycleRow =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)

            // Close any open cycle and record its change.
            val openCycles =
                Cycles
                    .selectAll()
                    .where { (Cycles.userId eq userId) and Cycles.endDate.isNull() and Cycles.deletedAt.isNull() }
                    .map(::toRow)

            if (openCycles.isNotEmpty()) {
                Cycles.update({ (Cycles.userId eq userId) and Cycles.endDate.isNull() and Cycles.deletedAt.isNull() }) {
                    it[endDate] = previousEndDate
                    it[updatedAt] = now
                }
                openCycles.forEach { closed ->
                    changeLogRepository.record(userId, TYPE_CYCLE, closed.id, now, deleted = false)
                }
            }

            Cycles.insert {
                it[Cycles.id] = id
                it[Cycles.userId] = userId
                it[Cycles.startDate] = startDate
                it[endDate] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[deletedAt] = null
            }
            changeLogRepository.record(userId, TYPE_CYCLE, id, now, deleted = false)

            CycleRow(id, userId, startDate, endDate = null, createdAt = now, updatedAt = now)
        }

    /**
     * Inserts a cycle with both dates exactly as given, without touching any other
     * cycle. Used only by import (Phase 12) and sync apply (Phase 16a): an imported
     * cycle is additive, never auto-closes an open one.
     */
    fun insertExplicit(
        userId: UUID,
        startDate: LocalDate,
        endDate: LocalDate?,
        id: UUID = UUID.randomUUID(),
    ): CycleRow =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            Cycles.insert {
                it[Cycles.id] = id
                it[Cycles.userId] = userId
                it[Cycles.startDate] = startDate
                it[Cycles.endDate] = endDate
                it[createdAt] = now
                it[updatedAt] = now
                it[deletedAt] = null
            }
            changeLogRepository.record(userId, TYPE_CYCLE, id, now, deleted = false)
            CycleRow(id, userId, startDate, endDate, createdAt = now, updatedAt = now)
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
                Cycles.update({
                    (Cycles.id eq cycleId) and (Cycles.userId eq userId) and Cycles.deletedAt.isNull()
                }) {
                    it[Cycles.endDate] = endDate
                    it[updatedAt] = now
                }
            if (updated == 0) {
                null
            } else {
                changeLogRepository.record(userId, TYPE_CYCLE, cycleId, now, deleted = false)
                Cycles
                    .selectAll()
                    .where { Cycles.id eq cycleId }
                    .map(::toRow)
                    .single()
            }
        }

    /**
     * Updates both `start_date` and `end_date` on an existing cycle by [id]. Used by
     * the sync push to apply incoming cycle data. Does NOT auto-close any other cycle.
     */
    fun updateDates(
        userId: UUID,
        cycleId: UUID,
        startDate: LocalDate,
        endDate: LocalDate?,
        updatedAt: OffsetDateTime,
    ): CycleRow? =
        transaction(db) {
            val updated =
                Cycles.update({
                    (Cycles.id eq cycleId) and (Cycles.userId eq userId)
                }) {
                    it[Cycles.startDate] = startDate
                    it[Cycles.endDate] = endDate
                    it[Cycles.updatedAt] = updatedAt
                    it[deletedAt] = null
                }
            if (updated == 0) null
            else {
                changeLogRepository.record(userId, TYPE_CYCLE, cycleId, updatedAt, deleted = false)
                findByIdIncludingDeleted(userId, cycleId)
            }
        }

    /**
     * Soft-deletes the cycle and cascades soft-deletes to every daily_log row in that
     * cycle. Records tombstones for all affected aggregates in one transaction (D-16a.4).
     *
     * Returns false if the cycle doesn't exist or belongs to another user.
     */
    fun softDeleteCascade(
        userId: UUID,
        cycleId: UUID,
    ): Boolean =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)

            // Verify ownership (live cycle only — can't re-delete).
            val cycle =
                Cycles
                    .selectAll()
                    .where {
                        (Cycles.id eq cycleId) and (Cycles.userId eq userId) and Cycles.deletedAt.isNull()
                    }
                    .map(::toRow)
                    .singleOrNull()
                    ?: return@transaction false

            // Cascade: soft-delete all daily_logs for this cycle and record day tombstones.
            val dayIds =
                DailyLogs
                    .selectAll()
                    .where { (DailyLogs.cycleId eq cycleId) and (DailyLogs.userId eq userId) }
                    .map { it[DailyLogs.id] }

            if (dayIds.isNotEmpty()) {
                DailyLogs.update({
                    (DailyLogs.cycleId eq cycleId) and (DailyLogs.userId eq userId)
                }) {
                    it[deletedAt] = now
                    it[updatedAt] = now
                }
                dayIds.forEach { dayId ->
                    changeLogRepository.record(userId, TYPE_DAY, dayId, now, deleted = true)
                }
            }

            // Soft-delete the cycle itself.
            Cycles.update({ Cycles.id eq cycle.id }) {
                it[deletedAt] = now
                it[updatedAt] = now
            }
            changeLogRepository.record(userId, TYPE_CYCLE, cycleId, now, deleted = true)

            true
        }

    private fun toRow(row: ResultRow): CycleRow =
        CycleRow(
            id = row[Cycles.id],
            userId = row[Cycles.userId],
            startDate = row[Cycles.startDate],
            endDate = row[Cycles.endDate],
            createdAt = row[Cycles.createdAt],
            updatedAt = row[Cycles.updatedAt],
            deletedAt = row[Cycles.deletedAt],
        )
}
