package org.homeflow.app.shared.data.sync

import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.app.shared.db.Sync_outbox
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Pending outbox operations not yet acknowledged by the server. */
const val OP_UPSERT = "upsert"

/** Outbox operation for a soft-deleted entity. */
const val OP_DELETE = "delete"

/**
 * Thin wrapper over the `sync_outbox` SQLDelight queries. Callers record an entry on
 * every local write; [SyncEngine] drains the outbox as part of [SyncEngine.syncNow].
 *
 * The outbox is append-only from the writer's perspective; duplicates (same entity, same
 * op, different timestamps) are fine — on push the SyncEngine groups by entity and
 * sends only the LATEST entry per entity (via LWW on the server).
 */
@OptIn(ExperimentalUuidApi::class)
class LocalOutbox(
    private val db: HomeFlowDb,
) {
    /** Records a pending sync operation for [entityType]/[entityId]. */
    fun record(
        entityType: String,
        entityId: String,
        op: String,
        updatedAt: String,
    ) {
        db.syncOutboxQueries.insert(
            Uuid.random().toString(),
            entityType,
            entityId,
            op,
            updatedAt,
        )
    }

    /** All pending (un-synced) outbox entries, ordered by [updated_at] ascending. */
    fun pending(): List<Sync_outbox> = db.syncOutboxQueries.selectPending().executeAsList()

    /** Marks the entry with [id] as synced (will no longer appear in [pending]). */
    fun markSynced(id: String) = db.syncOutboxQueries.markSynced(id)

    /** Clears all outbox entries — used on account wipe. */
    fun clearAll() = db.syncOutboxQueries.deleteAll()
}
