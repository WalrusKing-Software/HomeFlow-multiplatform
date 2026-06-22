package org.homeflow.modules.dailylogs

import kotlinx.datetime.LocalDate
import org.homeflow.db.DailyLogCollection
import org.homeflow.db.DailyLogDigestion
import org.homeflow.db.DailyLogDischarge
import org.homeflow.db.DailyLogEmotions
import org.homeflow.db.DailyLogEnergy
import org.homeflow.db.DailyLogFlow
import org.homeflow.db.DailyLogMind
import org.homeflow.db.DailyLogSex
import org.homeflow.db.DailyLogSkin
import org.homeflow.db.DailyLogSleep
import org.homeflow.db.DailyLogs
import org.homeflow.db.MultiSelectLog
import org.homeflow.db.PainLogLocations
import org.homeflow.db.PainLogs
import org.homeflow.db.SingleSelectLog
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A daily log's anchor plus all of its symptom sub-logs, read in one pass. */
data class AssembledDay(
    val anchor: DailyLogRow,
    val emotions: List<UUID>,
    val sleep: List<UUID>,
    val discharge: List<UUID>,
    val skin: List<UUID>,
    val digestion: List<UUID>,
    val mind: List<UUID>,
    val energy: UUID?,
    val flow: UUID?,
    val collection: UUID?,
    /** Raw ciphertext as stored; the service decrypts it (never the repository). */
    val sexEncryptedPayload: String?,
    val pain: AssembledPain?,
)

/** The pain log anchor and its per-location severities for a day. */
data class AssembledPain(
    val painLogId: UUID,
    val locations: List<AssembledPainLocation>,
)

/** One selected pain location with its severity (null = selected but unrated). */
data class AssembledPainLocation(
    val locationId: UUID,
    val severity: Int?,
)

/** The result of a pain write: the day's new `updated_at` and the pain log id (null if cleared). */
data class PainWriteResult(
    val updatedAt: OffsetDateTime,
    val painLogId: UUID?,
)

/**
 * DB access for the symptom sub-logs that hang off a `daily_logs` anchor. Every write
 * follows the `PUT` replace semantics from `__docs/API.md`: delete the existing rows
 * for the category, insert the new set, and bump the anchor's `updated_at`. Each
 * operation is row-scoped to the authenticated `userId` and returns `null` when no
 * anchor exists for the date (the service surfaces that as `404`). No crypto or
 * validation here — the service encrypts sex and validates option IDs first.
 */
@Suppress("TooManyFunctions") // one write per category family plus the day-assembly readers
class DailyLogSubsRepository(
    private val db: Database,
) {
    /** Reads the anchor and every sub-log for [userId]/[date], or null if no anchor exists. */
    fun assembleDay(
        userId: UUID,
        date: LocalDate,
    ): AssembledDay? =
        transaction(db) {
            val anchorRow =
                DailyLogs
                    .selectAll()
                    .where { (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }
                    .singleOrNull()
                    ?: return@transaction null
            val anchor = toDailyLogRow(anchorRow)
            val id = anchor.id
            AssembledDay(
                anchor = anchor,
                emotions = readMulti(DailyLogEmotions, id),
                sleep = readMulti(DailyLogSleep, id),
                discharge = readMulti(DailyLogDischarge, id),
                skin = readMulti(DailyLogSkin, id),
                digestion = readMulti(DailyLogDigestion, id),
                mind = readMulti(DailyLogMind, id),
                energy = readSingle(DailyLogEnergy, id),
                flow = readSingle(DailyLogFlow, id),
                collection = readSingle(DailyLogCollection, id),
                sexEncryptedPayload =
                    DailyLogSex
                        .selectAll()
                        .where { DailyLogSex.dailyLogId eq id }
                        .singleOrNull()
                        ?.get(DailyLogSex.encryptedPayload),
                pain = readPain(id),
            )
        }

    /** Replaces every row in a multi-select [table] for the day with [optionIds]. */
    fun replaceMultiSelect(
        table: MultiSelectLog,
        userId: UUID,
        date: LocalDate,
        optionIds: List<UUID>,
    ): OffsetDateTime? =
        transaction(db) {
            val id = findAnchorId(userId, date) ?: return@transaction null
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            table.deleteWhere { (table.dailyLogId eq id) and (table.userId eq userId) }
            optionIds.forEach { option ->
                table.insert {
                    it[table.dailyLogId] = id
                    it[table.userId] = userId
                    it[table.optionId] = option
                    it[table.createdAt] = now
                }
            }
            touch(userId, date, now)
            now
        }

    /** Replaces the single-select [table] row for the day with [optionId] (null clears it). */
    fun replaceSingleSelect(
        table: SingleSelectLog,
        userId: UUID,
        date: LocalDate,
        optionId: UUID?,
    ): OffsetDateTime? =
        transaction(db) {
            val id = findAnchorId(userId, date) ?: return@transaction null
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            table.deleteWhere { (table.dailyLogId eq id) and (table.userId eq userId) }
            if (optionId != null) {
                table.insert {
                    it[table.dailyLogId] = id
                    it[table.userId] = userId
                    it[table.optionId] = optionId
                    it[table.createdAt] = now
                }
            }
            touch(userId, date, now)
            now
        }

    /**
     * Replaces the day's encrypted sex payload. [encryptedPayload] is the already-encrypted
     * string to store, or null to clear the entry (the row is deleted, per the addendum).
     */
    fun replaceSex(
        userId: UUID,
        date: LocalDate,
        encryptedPayload: String?,
    ): OffsetDateTime? =
        transaction(db) {
            val id = findAnchorId(userId, date) ?: return@transaction null
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            DailyLogSex.deleteWhere { (DailyLogSex.dailyLogId eq id) and (DailyLogSex.userId eq userId) }
            if (encryptedPayload != null) {
                DailyLogSex.insert {
                    it[dailyLogId] = id
                    it[DailyLogSex.userId] = userId
                    it[DailyLogSex.encryptedPayload] = encryptedPayload
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            touch(userId, date, now)
            now
        }

    /**
     * Replaces the day's pain log with [locations]. An empty list clears it (the
     * `pain_logs` row is deleted, cascading to its locations). Returns the new
     * `updated_at` and the pain log id (null when cleared).
     */
    fun replacePain(
        userId: UUID,
        date: LocalDate,
        locations: List<AssembledPainLocation>,
    ): PainWriteResult? =
        transaction(db) {
            val id = findAnchorId(userId, date) ?: return@transaction null
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            PainLogs.deleteWhere { (PainLogs.dailyLogId eq id) and (PainLogs.userId eq userId) }
            val painLogId =
                if (locations.isEmpty()) {
                    null
                } else {
                    val newId = UUID.randomUUID()
                    PainLogs.insert {
                        it[PainLogs.id] = newId
                        it[dailyLogId] = id
                        it[PainLogs.userId] = userId
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                    locations.forEach { location ->
                        PainLogLocations.insert {
                            it[painLogId] = newId
                            it[PainLogLocations.userId] = userId
                            it[locationId] = location.locationId
                            it[severity] = location.severity?.toShort()
                            it[createdAt] = now
                        }
                    }
                    newId
                }
            touch(userId, date, now)
            PainWriteResult(updatedAt = now, painLogId = painLogId)
        }

    /** The anchor id for [userId]/[date], or null. Must be called inside a transaction. */
    private fun findAnchorId(
        userId: UUID,
        date: LocalDate,
    ): UUID? =
        DailyLogs
            .selectAll()
            .where { (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }
            .map { it[DailyLogs.id] }
            .singleOrNull()

    /** Bumps the anchor's `updated_at`. Must be called inside a transaction. */
    private fun touch(
        userId: UUID,
        date: LocalDate,
        now: OffsetDateTime,
    ) {
        DailyLogs.update({ (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }) {
            it[updatedAt] = now
        }
    }

    /** Reads a multi-select category's option ids. Must be called inside a transaction. */
    private fun readMulti(
        table: MultiSelectLog,
        dailyLogId: UUID,
    ): List<UUID> =
        table
            .selectAll()
            .where { table.dailyLogId eq dailyLogId }
            .map { it[table.optionId] }

    /** Reads a single-select category's option id (or null). Must be called inside a transaction. */
    private fun readSingle(
        table: SingleSelectLog,
        dailyLogId: UUID,
    ): UUID? =
        table
            .selectAll()
            .where { table.dailyLogId eq dailyLogId }
            .map { it[table.optionId] }
            .singleOrNull()

    /** Reads the pain log and its locations (or null). Must be called inside a transaction. */
    private fun readPain(dailyLogId: UUID): AssembledPain? {
        val painLogId =
            PainLogs
                .selectAll()
                .where { PainLogs.dailyLogId eq dailyLogId }
                .map { it[PainLogs.id] }
                .singleOrNull()
                ?: return null
        val locations =
            PainLogLocations
                .selectAll()
                .where { PainLogLocations.painLogId eq painLogId }
                .map {
                    AssembledPainLocation(
                        locationId = it[PainLogLocations.locationId],
                        severity = it[PainLogLocations.severity]?.toInt(),
                    )
                }
        return AssembledPain(painLogId, locations)
    }

    private fun toDailyLogRow(row: ResultRow): DailyLogRow =
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
