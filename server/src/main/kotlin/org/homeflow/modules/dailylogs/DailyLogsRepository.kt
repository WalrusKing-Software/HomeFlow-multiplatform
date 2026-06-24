package org.homeflow.modules.dailylogs

import kotlinx.datetime.LocalDate
import org.homeflow.db.DailyLogs
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
)

/**
 * DB access for the per-day anchor (`daily_logs`). Every query is scoped to the
 * authenticated `userId`; the `(user_id, log_date)` unique index makes one log per
 * day per user. No business logic or crypto here (see `__docs/ARCHITECTURE-server.md`).
 */
class DailyLogsRepository(
    private val db: Database,
) {
    /** The anchor for [userId] on [date], or null if none exists. */
    fun findByDate(
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

    /** Every anchor owned by [userId], in no particular order. Used by export (Phase 12). */
    fun findAllByUser(userId: UUID): List<DailyLogRow> =
        transaction(db) {
            DailyLogs
                .selectAll()
                .where { DailyLogs.userId eq userId }
                .map(::toRow)
        }

    /**
     * Inserts the anchor for [userId]/[date]/[cycleId], or returns null if a log
     * already exists for that day (the caller surfaces that as `409 CONFLICT`). The
     * existence check and insert share one transaction so the unique index is honoured.
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
                    .where { (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }
                    .map(::toRow)
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
            }
            DailyLogRow(id, userId, cycleId, date, notes = null, createdAt = now, updatedAt = now)
        }

    /**
     * Stores [ciphertext] (already encrypted, or null to clear) in the day's `notes`
     * column and bumps `updated_at`. Returns the updated row, or null if no anchor
     * exists for [userId]/[date].
     */
    fun updateNotes(
        userId: UUID,
        date: LocalDate,
        ciphertext: String?,
    ): DailyLogRow? =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val updated =
                DailyLogs.update({ (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }) {
                    it[notes] = ciphertext
                    it[updatedAt] = now
                }
            if (updated == 0) {
                null
            } else {
                DailyLogs
                    .selectAll()
                    .where { (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }
                    .map(::toRow)
                    .single()
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
        )
}
