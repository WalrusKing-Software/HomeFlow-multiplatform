package org.homeflow.modules.sync

import org.homeflow.db.SyncChanges
import org.homeflow.db.userScopedTransaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
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
 * data write. [record] requires an active transaction and throws otherwise — it never
 * opens its own, so the change row and the data row always commit or roll back together.
 */
class ChangeLogRepository(
    private val db: Database,
) {
    /**
     * Upserts a change row for [entityId] of [entityType] for [userId].
     *
     * MUST be called inside the same Exposed `transaction {}` as the originating data
     * write — the change row and the data row then commit or roll back together.
     * Throws [IllegalStateException] when no transaction is active, instead of silently
     * opening its own (which would hide a missing-atomicity bug at the call site).
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
        val tx =
            TransactionManager.currentOrNull()
                ?: error("ChangeLogRepository.record() requires an active transaction")

        val seq =
            tx.exec("SELECT nextval('sync_seq') AS next_seq") { rs ->
                if (rs.next()) rs.getLong("next_seq") else error("sync_seq returned no value")
            } ?: error("SELECT nextval('sync_seq') returned no result")

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

    /**
     * Change rows for [userId] with `server_seq > [cursor]`, ordered ascending by seq,
     * capped at [limit] rows (SEC-02: the pull is paginated — never read an unbounded
     * change set into memory). Used by [GET /api/v1/sync/changes].
     */
    fun findChangesSince(
        userId: UUID,
        cursor: Long,
        limit: Int,
    ): List<SyncChangeRow> =
        userScopedTransaction(db, userId) {
            SyncChanges
                .selectAll()
                .where { (SyncChanges.userId eq userId) and (SyncChanges.serverSeq greater cursor) }
                .orderBy(SyncChanges.serverSeq to SortOrder.ASC)
                .limit(limit)
                .map(::toRow)
        }

    /** The highest `server_seq` recorded for [userId], or 0 if none. */
    fun maxSeq(userId: UUID): Long =
        userScopedTransaction(db, userId) {
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
