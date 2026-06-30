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
import org.homeflow.modules.sync.ChangeLogRepository
import org.homeflow.modules.sync.ChangeLogRepository.Companion.TYPE_DAY
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
 *
 * Every write calls [changeLogRepository.record] (TYPE_DAY, the anchor id) inside
 * the same Exposed transaction — so the sub-log change and the anchor bump are atomic
 * with the change-log entry (Phase 16a D-16a.3).
 */
@Suppress("TooManyFunctions") // one write per category family plus the day-assembly readers
class DailyLogSubsRepository(
    private val db: Database,
    private val changeLogRepository: ChangeLogRepository,
) {
    /** Reads the anchor and every sub-log for [userId]/[date], or null if no live anchor exists. */
    fun assembleDay(
        userId: UUID,
        date: LocalDate,
    ): AssembledDay? =
        transaction(db) {
            val anchorRow =
                DailyLogs
                    .selectAll()
                    .where {
                        (DailyLogs.userId eq userId) and
                            (DailyLogs.logDate eq date) and
                            DailyLogs.deletedAt.isNull()
                    }.singleOrNull()
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

    /**
     * Reads the anchor and every sub-log by anchor [id] regardless of deleted_at.
     * Used by sync pull to assemble live tombstone-inclusive day snapshots.
     */
    fun assembleDayById(
        userId: UUID,
        anchorId: UUID,
    ): AssembledDay? =
        transaction(db) {
            val anchorRow =
                DailyLogs
                    .selectAll()
                    .where { (DailyLogs.id eq anchorId) and (DailyLogs.userId eq userId) }
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
            val (id, now) = findAnchorAndTime(userId, date) ?: return@transaction null
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
            changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
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
            val (id, now) = findAnchorAndTime(userId, date) ?: return@transaction null
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
            changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
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
            val (id, now) = findAnchorAndTime(userId, date) ?: return@transaction null
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
            changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
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
            val (id, now) = findAnchorAndTime(userId, date) ?: return@transaction null
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
            changeLogRepository.record(userId, TYPE_DAY, id, now, deleted = false)
            PainWriteResult(updatedAt = now, painLogId = painLogId)
        }

    /**
     * Replaces every sub-log category for the anchor [anchorId] in a SINGLE transaction and
     * stamps [updatedAt] on the anchor row. Used exclusively by the sync push handler so that
     * the client-originating timestamp is preserved (not overwritten by wall-clock `now()`),
     * and so that all category replacements + the change-log record are atomic.
     *
     * [sexEncryptedPayload] is already encrypted (null = clear). [notesEncrypted] is already
     * encrypted (null = clear). Pain location UUIDs are already resolved from slugs.
     *
     * Returns false if [anchorId] does not exist or does not belong to [userId].
     */
    @Suppress("LongParameterList")
    fun applyAllFromSync(
        userId: UUID,
        anchorId: UUID,
        emotions: List<UUID>,
        sleep: List<UUID>,
        discharge: List<UUID>,
        skin: List<UUID>,
        digestion: List<UUID>,
        mind: List<UUID>,
        energy: UUID?,
        flow: UUID?,
        collection: UUID?,
        sexEncryptedPayload: String?,
        painLocations: List<AssembledPainLocation>,
        notesEncrypted: String?,
        updatedAt: OffsetDateTime,
    ): Boolean =
        transaction(db) {
            val anchor =
                DailyLogs
                    .selectAll()
                    .where { (DailyLogs.id eq anchorId) and (DailyLogs.userId eq userId) }
                    .singleOrNull()
                    ?: return@transaction false

            inlineReplaceMulti(DailyLogEmotions, userId, anchorId, emotions)
            inlineReplaceMulti(DailyLogSleep, userId, anchorId, sleep)
            inlineReplaceMulti(DailyLogDischarge, userId, anchorId, discharge)
            inlineReplaceMulti(DailyLogSkin, userId, anchorId, skin)
            inlineReplaceMulti(DailyLogDigestion, userId, anchorId, digestion)
            inlineReplaceMulti(DailyLogMind, userId, anchorId, mind)

            inlineReplaceSingle(DailyLogEnergy, userId, anchorId, energy)
            inlineReplaceSingle(DailyLogFlow, userId, anchorId, flow)
            inlineReplaceSingle(DailyLogCollection, userId, anchorId, collection)

            DailyLogSex.deleteWhere { (DailyLogSex.dailyLogId eq anchorId) and (DailyLogSex.userId eq userId) }
            if (sexEncryptedPayload != null) {
                DailyLogSex.insert {
                    it[dailyLogId] = anchorId
                    it[DailyLogSex.userId] = userId
                    it[encryptedPayload] = sexEncryptedPayload
                    it[createdAt] = updatedAt
                    it[DailyLogSex.updatedAt] = updatedAt
                }
            }

            PainLogs.deleteWhere { (PainLogs.dailyLogId eq anchorId) and (PainLogs.userId eq userId) }
            if (painLocations.isNotEmpty()) {
                val newPainLogId = UUID.randomUUID()
                PainLogs.insert {
                    it[PainLogs.id] = newPainLogId
                    it[dailyLogId] = anchorId
                    it[PainLogs.userId] = userId
                    it[createdAt] = updatedAt
                    it[PainLogs.updatedAt] = updatedAt
                }
                painLocations.forEach { loc ->
                    PainLogLocations.insert {
                        it[painLogId] = newPainLogId
                        it[PainLogLocations.userId] = userId
                        it[locationId] = loc.locationId
                        it[severity] = loc.severity?.toShort()
                        it[createdAt] = updatedAt
                    }
                }
            }

            DailyLogs.update({ (DailyLogs.id eq anchorId) and (DailyLogs.userId eq userId) }) {
                it[notes] = notesEncrypted
                it[DailyLogs.updatedAt] = updatedAt
                it[deletedAt] = null
            }

            changeLogRepository.record(userId, TYPE_DAY, anchorId, updatedAt, deleted = false)

            // suppress "unused" — loaded only for ownership check above
            @Suppress("UNUSED_EXPRESSION")
            anchor
            true
        }

    /** Replaces all rows of a multi-select [table] for [anchorId]. Must be inside a transaction. */
    private fun inlineReplaceMulti(
        table: MultiSelectLog,
        userId: UUID,
        anchorId: UUID,
        ids: List<UUID>,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        table.deleteWhere { (table.dailyLogId eq anchorId) and (table.userId eq userId) }
        ids.forEach { optionId ->
            table.insert {
                it[table.dailyLogId] = anchorId
                it[table.userId] = userId
                it[table.optionId] = optionId
                it[table.createdAt] = now
            }
        }
    }

    /** Replaces the single-select [table] row for [anchorId]. Must be inside a transaction. */
    private fun inlineReplaceSingle(
        table: SingleSelectLog,
        userId: UUID,
        anchorId: UUID,
        id: UUID?,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        table.deleteWhere { (table.dailyLogId eq anchorId) and (table.userId eq userId) }
        if (id != null) {
            table.insert {
                it[table.dailyLogId] = anchorId
                it[table.userId] = userId
                it[table.optionId] = id
                it[table.createdAt] = now
            }
        }
    }

    /** The live anchor id + now for [userId]/[date], or null. Must be called inside a transaction. */
    private fun findAnchorAndTime(
        userId: UUID,
        date: LocalDate,
    ): Pair<UUID, OffsetDateTime>? {
        val id =
            DailyLogs
                .selectAll()
                .where {
                    (DailyLogs.userId eq userId) and
                        (DailyLogs.logDate eq date) and
                        DailyLogs.deletedAt.isNull()
                }.map { it[DailyLogs.id] }
                .singleOrNull()
                ?: return null
        return id to OffsetDateTime.now(ZoneOffset.UTC)
    }

    /** Bumps the anchor's `updated_at`. Must be called inside a transaction. */
    private fun touch(
        userId: UUID,
        date: LocalDate,
        now: OffsetDateTime,
    ) {
        DailyLogs.update({
            (DailyLogs.userId eq userId) and
                (DailyLogs.logDate eq date) and
                DailyLogs.deletedAt.isNull()
        }) {
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
            deletedAt = row[DailyLogs.deletedAt],
        )
}
