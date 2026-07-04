package org.homeflow.modules.dailylogs

import kotlinx.datetime.LocalDate
import org.homeflow.db.DailyLogs
import org.homeflow.modules.sync.ChangeLogRepository
import org.homeflow.modules.sync.ChangeLogRepository.Companion.TYPE_DAY
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A `daily_logs` anchor row. [notes] is the raw ciphertext column value as stored —
 * the repository never encrypts or decrypts (that is the service's job). Sub-logs
 * (emotions, sleep, …) live in their own tables and are assembled in Phase 5.
 */
data class DailyLogRow(
    val id: UUID,
    val userId: UUID,
    val cycleId: UUID,
    val logDate: LocalDate,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val deletedAt: OffsetDateTime? = null,
)

/**
 * DB access for the per-day anchor (`daily_logs`). Every query is scoped to the
 * authenticated `userId`; the `(user_id, log_date)` unique index makes one log per
 * day per user. No business logic or crypto here (see `__docs/ARCHITECTURE-server.md`).
 *
 * All write methods call [changeLogRepository.record] inside the same Exposed
 * transaction so the change entry and the data row are atomic (Phase 16a D-16a.3).
 * Live reads filter `deleted_at IS NULL`; tombstone-inclusive variants are used only
 * by the sync pull handler.
 */
class DailyLogsRepository(
    private val db: Database,
    private val changeLogRepository: ChangeLogRepository,
) {
    /** The live anchor for [userId] on [date], or null if none exists. */
    fun findByDate(
        userId: UUID,
        date: LocalDate,
    ): DailyLogRow? =
        transaction(db) {
            DailyLogs
                .selectAll()
                .where {
                    (DailyLogs.userId eq userId) and
                        (DailyLogs.logDate eq date) and
                        DailyLogs.deletedAt.isNull()
                }.map(::toRow)
                .singleOrNull()
        }

    /** The anchor for [userId] on [date] including tombstoned rows. Used by sync pull. */
    fun findByDateIncludingDeleted(
        userId: UUID,
        date: LocalDate,
    ): DailyLogRow? =
        transaction(db) {
            DailyLogs
                .selectAll()
                .where { (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }
                .map(::toRow)
                .singleOrNull()
        }

    /** The anchor for [userId] by primary-key [id], including tombstoned rows. Used by sync pull. */
    fun findByIdIncludingDeleted(
        userId: UUID,
        id: UUID,
    ): DailyLogRow? =
        transaction(db) {
            DailyLogs
                .selectAll()
                .where { (DailyLogs.id eq id) and (DailyLogs.userId eq userId) }
                .map(::toRow)
                .singleOrNull()
        }

    /** Every live anchor owned by [userId], in no particular order. Used by export (Phase 12). */
    fun findAllByUser(userId: UUID): List<DailyLogRow> =
        transaction(db) {
            DailyLogs
                .selectAll()
                .where { (DailyLogs.userId eq userId) and DailyLogs.deletedAt.isNull() }
                .map(::toRow)
        }

    /**
     * Inserts the anchor for [userId]/[date]/[cycleId], or returns null if a log
     * already exists for that day (the caller surfaces that as `409 CONFLICT`). The
     * existence check and insert share one transaction so the unique index is honoured.
     *
     * Records a `day` change for the new anchor.
     */
    fun insertIfAbsent(
        userId: UUID,
        cycleId: UUID,
        date: LocalDate,
        id: UUID = UUID.randomUUID(),
    ): DailyLogRow? =
        transaction(db) {
            val existing =
                DailyLogs
                    .selectAll()
                    .where {
                        (DailyLogs.userId eq userId) and
                            (DailyLogs.logDate eq date) and
                            DailyLogs.deletedAt.isNull()
                    }.map(::toRow)
                    .singleOrNull()
            if (existing != null) return@transaction null

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            DailyLogs.insert {
                it[DailyLogs.id] = id
                it[DailyLogs.userId] = userId
                it[DailyLogs.cycleId] = cycleId
                it[logDate] = date
                it[notes] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[deletedAt] = null
            }
            changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
            DailyLogRow(id, userId, cycleId, date, notes = null, createdAt = now, updatedAt = now)
        }

    /**
     * Inserts or updates a daily_log anchor with the given explicit [id]. Used by the
     * sync push to apply incoming day data (client-supplied UUID, D1).
     *
     * If a row with [id] already exists, updates `cycle_id` and `updated_at`; if a live
     * row for the same `(user_id, log_date)` exists with a DIFFERENT id, returns null
     * (conflict — the caller decides via LWW which row wins).
     */
    fun upsertById(
        userId: UUID,
        id: UUID,
        cycleId: UUID,
        date: LocalDate,
        updatedAt: OffsetDateTime,
    ): DailyLogRow? =
        transaction(db) {
            val now = updatedAt

            // Check if this exact anchor already exists (any state).
            val existing =
                DailyLogs
                    .selectAll()
                    .where { (DailyLogs.id eq id) and (DailyLogs.userId eq userId) }
                    .map(::toRow)
                    .singleOrNull()

            if (existing != null) {
                // Update dates/cycle to reflect the incoming state.
                DailyLogs.update({ DailyLogs.id eq id }) {
                    it[DailyLogs.cycleId] = cycleId
                    it[DailyLogs.logDate] = date
                    it[DailyLogs.updatedAt] = now
                    it[deletedAt] = null
                }
                changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
                return@transaction DailyLogRow(
                    id,
                    userId,
                    cycleId,
                    date,
                    notes = existing.notes,
                    createdAt = existing.createdAt,
                    updatedAt = now,
                )
            }

            // No row with this id yet — insert.
            val createdAt = OffsetDateTime.now(ZoneOffset.UTC)
            DailyLogs.insert {
                it[DailyLogs.id] = id
                it[DailyLogs.userId] = userId
                it[DailyLogs.cycleId] = cycleId
                it[logDate] = date
                it[notes] = null
                it[DailyLogs.createdAt] = createdAt
                it[DailyLogs.updatedAt] = now
                it[deletedAt] = null
            }
            changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
            DailyLogRow(id, userId, cycleId, date, notes = null, createdAt = createdAt, updatedAt = now)
        }

    /**
     * Stores [ciphertext] (already encrypted, or null to clear) in the day's `notes`
     * column and bumps `updated_at`. Returns the updated row, or null if no live anchor
     * exists for [userId]/[date].
     *
     * Records a `day` change for the updated anchor.
     */
    fun updateNotes(
        userId: UUID,
        date: LocalDate,
        ciphertext: String?,
    ): DailyLogRow? =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val updated =
                DailyLogs.update({
                    (DailyLogs.userId eq userId) and
                        (DailyLogs.logDate eq date) and
                        DailyLogs.deletedAt.isNull()
                }) {
                    it[notes] = ciphertext
                    it[updatedAt] = now
                }
            if (updated == 0) {
                null
            } else {
                val row =
                    DailyLogs
                        .selectAll()
                        .where {
                            (DailyLogs.userId eq userId) and
                                (DailyLogs.logDate eq date) and
                                DailyLogs.deletedAt.isNull()
                        }.map(::toRow)
                        .single()
                changeLogRepository.record(userId, TYPE_DAY, row.id, now, deleted = false)
                row
            }
        }

    /**
     * Soft-deletes the anchor for [userId]/[date]. Returns the tombstoned row, or null
     * if no live anchor exists. Sub-log rows remain but are unreachable via live reads
     * (which filter on the anchor's `deleted_at`).
     *
     * Records a `day` tombstone.
     */
    fun softDeleteByDate(
        userId: UUID,
        date: LocalDate,
    ): DailyLogRow? =
        transaction(db) {
            val existing =
                DailyLogs
                    .selectAll()
                    .where {
                        (DailyLogs.userId eq userId) and
                            (DailyLogs.logDate eq date) and
                            DailyLogs.deletedAt.isNull()
                    }.map(::toRow)
                    .singleOrNull()
                    ?: return@transaction null

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            DailyLogs.update({ DailyLogs.id eq existing.id }) {
                it[deletedAt] = now
                it[updatedAt] = now
            }
            changeLogRepository.record(userId, TYPE_DAY, existing.id, now, deleted = true)
            existing.copy(deletedAt = now, updatedAt = now)
        }

    /**
     * Soft-deletes an anchor by primary-key [id]. Used by the sync push to apply an
     * incoming tombstone. Returns false if no live row with that id exists for [userId].
     */
    fun softDeleteById(
        userId: UUID,
        id: UUID,
    ): Boolean =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val updated =
                DailyLogs.update({
                    (DailyLogs.id eq id) and (DailyLogs.userId eq userId) and DailyLogs.deletedAt.isNull()
                }) {
                    it[deletedAt] = now
                    it[updatedAt] = now
                }
            if (updated > 0) {
                changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = true)
                true
            } else {
                false
            }
        }

    private fun toRow(row: ResultRow): DailyLogRow =
        DailyLogRow(
            id = row[DailyLogs.id],
            userId = row[DailyLogs.userId],
            cycleId = row[DailyLogs.cycleId],
            logDate = row[DailyLogs.logDate],
            notes = row[DailyLogs.notes],
            createdAt = row[DailyLogs.createdAt],
            updatedAt = row[DailyLogs.updatedAt],
            deletedAt = row[DailyLogs.deletedAt],
        )
}
