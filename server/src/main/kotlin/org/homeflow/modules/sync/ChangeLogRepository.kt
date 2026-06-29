package org.homeflow.modules.sync

import org.homeflow.db.SyncChanges
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.util.UUID

/** A `sync_changes` row as read by the repository. */
data class SyncChangeRow(
    val entityType: String,
    val entityId: UUID,
    val serverSeq: Long,
    val updatedAt: OffsetDateTime,
    val deleted: Boolean,
)

/**
 * Maintains the `sync_changes` change-log — one row per (user, entity_type, entity_id),
 * upserted on every write so a pull returns each changed aggregate exactly once.
 *
 * `server_seq` is a monotonically increasing value drawn from the PostgreSQL sequence
 * `sync_seq` (created by V3__sync.sql). Using the DB sequence ensures ordering even
 * across concurrent writes without application-level locking.
 *
 * All writes call [record] inside the SAME Exposed `transaction {}` as the originating
 * data write (via the nested-transaction / SAVEPOINT behavior in Exposed) — so the
 * change row and the data row either both commit or both roll back.
 */
class ChangeLogRepository(
    private val db: Database,
) {
    /**
     * Upserts a change row for [entityId] of [entityType] for [userId]. Must be called
     * inside (or will create a nested savepoint within) the same transaction as the
     * data write to guarantee atomicity.
     *
     * @param deleted true when the aggregate was soft-deleted (tombstone); false for live writes.
     */
    fun record(
        userId: UUID,
        entityType: String,
        entityId: UUID,
        updatedAt: OffsetDateTime,
        deleted: Boolean,
    ) {
        transaction(db) {
            val seq =
                exec("SELECT nextval('sync_seq') AS next_seq") { rs ->
                    if (rs.next()) rs.getLong("next_seq") else error("sync_seq returned no value")
                }!!

            SyncChanges.upsert(
                SyncChanges.userId,
                SyncChanges.entityType,
                SyncChanges.entityId,
            ) {
                it[SyncChanges.userId] = userId
                it[SyncChanges.entityType] = entityType
                it[SyncChanges.entityId] = entityId
                it[SyncChanges.serverSeq] = seq
                it[SyncChanges.updatedAt] = updatedAt
                it[SyncChanges.deleted] = deleted
            }
        }
    }

    /**
     * All change rows for [userId] with `server_seq > [cursor]`, ordered ascending by seq.
     * Used by [GET /api/v1/sync/changes].
     */
    fun findChangesSince(
        userId: UUID,
        cursor: Long,
    ): List<SyncChangeRow> =
        transaction(db) {
            SyncChanges
                .selectAll()
                .where { (SyncChanges.userId eq userId) and (SyncChanges.serverSeq greater cursor) }
                .orderBy(SyncChanges.serverSeq to SortOrder.ASC)
                .map(::toRow)
        }

    /** The highest `server_seq` recorded for [userId], or 0 if none. */
    fun maxSeq(userId: UUID): Long =
        transaction(db) {
            SyncChanges
                .select(SyncChanges.serverSeq)
                .where { SyncChanges.userId eq userId }
                .orderBy(SyncChanges.serverSeq to SortOrder.DESC)
                .limit(1)
                .map { it[SyncChanges.serverSeq] }
                .firstOrNull() ?: 0L
        }

    private fun toRow(row: org.jetbrains.exposed.sql.ResultRow): SyncChangeRow =
        SyncChangeRow(
            entityType = row[SyncChanges.entityType],
            entityId = row[SyncChanges.entityId],
            serverSeq = row[SyncChanges.serverSeq],
            updatedAt = row[SyncChanges.updatedAt],
            deleted = row[SyncChanges.deleted],
        )

    companion object {
        const val TYPE_CYCLE = "cycle"
        const val TYPE_DAY = "day"
        const val TYPE_PREFERENCES = "preferences"
    }
}
