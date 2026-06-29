package org.homeflow.app.shared.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.RemoteDataSource
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.dto.SyncCycle
import org.homeflow.core.dto.SyncDay
import org.homeflow.core.dto.SyncPreferences
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.core.service.CycleBoundary
import org.homeflow.core.service.MergeWinner
import org.homeflow.core.service.mergeDecision
import org.homeflow.core.service.reconcileOpenCycles
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Orchestrates the offline sync cycle: **push** local outbox → **pull** server changes
 * → **merge** via LWW → **advance** cursor (Phase 16b D-16b.4).
 *
 * [syncNow] is safe to call concurrently; a guard flag prevents nested runs. It is
 * called from [SyncTrigger] (foreground debounce, connectivity, periodic timer).
 *
 * The `applyRemote*` paths in [SyncApplier] write directly to the local DB WITHOUT
 * recording outbox entries — this prevents the classic echo loop where a server change
 * re-enters the outbox on the next push.
 */
@OptIn(ExperimentalUuidApi::class)
class SyncEngine(
    private val db: HomeFlowDb,
    private val remote: RemoteDataSource,
) {
    private val userId = LocalBootstrap.LOCAL_USER_ID
    private val outbox = LocalOutbox(db)
    private val assembler = LocalSyncAssembler(db)
    private val applier = SyncApplier(db, userId)

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var running = false

    /**
     * Runs one full sync cycle (push then pull). Returns immediately if a cycle is already
     * running. The [status] flow reflects progress.
     */
    suspend fun syncNow() {
        if (running) return
        running = true
        _status.value = SyncStatus.Syncing
        try {
            push()
            pull()
            _status.value = SyncStatus.Success(Clock.System.now().toString())
        } catch (e: Exception) {
            _status.value = SyncStatus.Error(e.message ?: "Sync failed.")
        } finally {
            running = false
        }
    }

    // ─── Push ─────────────────────────────────────────────────────────────────

    private suspend fun push() {
        val pending = outbox.pending()
        if (pending.isEmpty()) return

        val cyclesById = mutableMapOf<String, SyncCycle>()
        val daysById = mutableMapOf<String, SyncDay>()
        var preferences: SyncPreferences? = null

        for (entry in pending) {
            when (entry.entity_type) {
                ENTITY_CYCLE -> {
                    val row =
                        db.cyclesQueries.selectByIdIncludingDeleted(entry.entity_id, userId)
                            .executeAsOneOrNull()
                    if (row != null) {
                        val cycle = assembler.assembleCycle(row)
                        val existing = cyclesById[entry.entity_id]
                        if (existing == null || cycle.updatedAt > existing.updatedAt) {
                            cyclesById[entry.entity_id] = cycle
                        }
                    }
                }
                ENTITY_DAY -> {
                    val row =
                        db.dailyLogsQueries.selectByIdIncludingDeleted(entry.entity_id, userId)
                            .executeAsOneOrNull()
                    if (row != null) {
                        val day = assembler.assembleDay(row)
                        val existing = daysById[entry.entity_id]
                        if (existing == null || day.updatedAt > existing.updatedAt) {
                            daysById[entry.entity_id] = day
                        }
                    }
                }
                ENTITY_PREFERENCES -> {
                    val candidate = assembler.assemblePreferences(userId)
                    if (candidate != null) {
                        if (preferences == null || candidate.updatedAt > preferences.updatedAt) {
                            preferences = candidate
                        }
                    }
                }
            }
        }

        if (cyclesById.isEmpty() && daysById.isEmpty() && preferences == null) {
            pending.forEach { outbox.markSynced(it.id) }
            return
        }

        val request =
            SyncPushRequest(
                cycles = cyclesById.values.toList(),
                days = daysById.values.toList(),
                preferences = preferences,
            )

        when (val result = remote.pushSync(request)) {
            is ApiResult.Success -> {
                pending.forEach { outbox.markSynced(it.id) }
                ensureSyncStateRow()
                result.value.cursor.let { newCursor ->
                    val current = db.syncStateQueries.getCursor().executeAsOneOrNull() ?: 0L
                    if (newCursor > current) {
                        db.syncStateQueries.updateCursor(newCursor)
                    }
                }
            }
            is ApiResult.Failure -> error("Push failed: ${result.message}")
        }
    }

    // ─── Pull ─────────────────────────────────────────────────────────────────

    private suspend fun pull() {
        ensureSyncStateRow()
        val cursor = db.syncStateQueries.getCursor().executeAsOneOrNull() ?: 0L

        when (val result = remote.pullSync(cursor)) {
            is ApiResult.Success -> {
                val response = result.value
                response.cycles.forEach { applier.applyRemoteCycle(it) }
                response.days.forEach { applier.applyRemoteDay(it) }
                response.preferences?.let { applier.applyRemotePreferences(it) }
                // Enforce the at-most-one-open-cycle invariant locally with the shared
                // :core rule (D-16b.7) — deterministic, so it matches the server's own
                // reconcile without re-enqueuing to the outbox (no echo loop).
                reconcileLocalOpenCycles()
                db.syncStateQueries.updateCursor(response.cursor)
            }
            is ApiResult.Failure -> error("Pull failed: ${result.message}")
        }
    }

    /**
     * Collapses multiple open local cycles to one (latest start stays open; earlier ones
     * auto-close at `autoCloseEndDate(nextStart)`) via the shared [reconcileOpenCycles]
     * rule. Writes directly (no outbox record) — the rule is deterministic, so the server
     * reaches the identical result on its own push reconcile and the two converge.
     */
    private fun reconcileLocalOpenCycles() {
        val openRows =
            db.cyclesQueries.selectAll(userId).executeAsList().filter { it.end_date == null }
        if (openRows.size <= 1) return
        val boundaries = openRows.map { CycleBoundary(it.id, LocalDate.parse(it.start_date), null) }
        val rowsById = openRows.associateBy { it.id }
        reconcileOpenCycles(boundaries).forEach { boundary ->
            val newEnd = boundary.endDate ?: return@forEach
            val row = rowsById[boundary.id] ?: return@forEach
            // Preserve updated_at: only the end_date changes, keeping the row deterministic.
            db.cyclesQueries.closeById(newEnd.toString(), row.updated_at, boundary.id, userId)
        }
    }

    private fun ensureSyncStateRow() = db.syncStateQueries.ensureRow()

    companion object {
        const val ENTITY_CYCLE = "cycle"
        const val ENTITY_DAY = "day"
        const val ENTITY_PREFERENCES = "preferences"
    }
}

/**
 * Applies remote entities to the local DB WITHOUT recording outbox entries (preventing
 * echo loops). Uses LWW to decide whether to overwrite a local value.
 */
@OptIn(ExperimentalUuidApi::class)
private class SyncApplier(
    private val db: HomeFlowDb,
    private val userId: String,
) {
    fun applyRemoteCycle(remote: SyncCycle) {
        val local =
            db.cyclesQueries.selectByIdIncludingDeleted(remote.id, userId).executeAsOneOrNull()

        val shouldApply =
            if (local == null) {
                true
            } else {
                mergeDecision(
                    localUpdatedAt = local.updated_at,
                    localId = local.id,
                    remoteUpdatedAt = remote.updatedAt,
                    remoteId = remote.id,
                ) == MergeWinner.REMOTE
            }

        if (!shouldApply) return

        if (remote.deleted) {
            val now = Clock.System.now().toString()
            db.cyclesQueries.softDelete(now, now, remote.id, userId)
        } else {
            db.cyclesQueries.upsert(
                id = remote.id,
                userId = userId,
                startDate = remote.startDate,
                endDate = remote.endDate,
                createdAt = local?.created_at ?: remote.updatedAt,
                updatedAt = remote.updatedAt,
                deletedAt = null,
            )
        }
    }

    fun applyRemoteDay(remote: SyncDay) {
        if (remote.date.isEmpty()) {
            val now = Clock.System.now().toString()
            db.dailyLogsQueries.softDelete(now, now, remote.id, userId)
            return
        }

        val local =
            db.dailyLogsQueries.selectByIdIncludingDeleted(remote.id, userId).executeAsOneOrNull()

        val shouldApply =
            if (local == null) {
                true
            } else {
                mergeDecision(
                    localUpdatedAt = local.updated_at,
                    localId = local.id,
                    remoteUpdatedAt = remote.updatedAt,
                    remoteId = remote.id,
                ) == MergeWinner.REMOTE
            }

        if (!shouldApply) return

        if (remote.deleted) {
            val now = Clock.System.now().toString()
            if (local != null) db.dailyLogsQueries.softDelete(now, now, remote.id, userId)
        } else {
            db.dailyLogsQueries.upsert(
                id = remote.id,
                userId = userId,
                cycleId = remote.cycleId,
                logDate = remote.date,
                notes = remote.notes,
                createdAt = local?.created_at ?: remote.updatedAt,
                updatedAt = remote.updatedAt,
                deletedAt = null,
            )
            applyRemoteDaySubLogs(remote)
        }
    }

    private fun applyRemoteDaySubLogs(remote: SyncDay) {
        val ctx = buildRefContext()
        val logId = remote.id

        fun applyMulti(
            catSlug: String,
            slugs: List<String>,
        ) {
            val catId = ctx.categoryIdBySlug[catSlug] ?: return
            db.dailyLogSubsQueries.deleteMultiByLogAndCategory(logId, catId)
            val now = Clock.System.now().toString()
            slugs.forEach { slug ->
                val optId = ctx.optionIdByCategoryAndSlug[catSlug to slug] ?: return@forEach
                db.dailyLogSubsQueries.insertMulti(
                    Uuid.random().toString(), logId, catId, optId, now, now, null,
                )
            }
        }

        fun applySingle(
            catSlug: String,
            slug: String?,
        ) {
            val catId = ctx.categoryIdBySlug[catSlug] ?: return
            db.dailyLogSubsQueries.deleteSingleByLogAndCategory(logId, catId)
            if (slug != null) {
                val optId = ctx.optionIdByCategoryAndSlug[catSlug to slug] ?: return
                val now = Clock.System.now().toString()
                db.dailyLogSubsQueries.insertSingle(
                    Uuid.random().toString(), logId, catId, optId, now, now, null,
                )
            }
        }

        applyMulti("emotions", remote.emotions)
        applyMulti("sleep_quality", remote.sleep)
        applyMulti("discharge", remote.discharge)
        applyMulti("skin", remote.skin)
        applyMulti("digestion", remote.digestion)
        applyMulti("mind", remote.mind)
        applySingle("blood_flow", remote.flow)
        applySingle("collection_method", remote.collectionMethod)
        applySingle("energy", remote.energy)

        db.dailyLogSubsQueries.deleteSexByLogId(logId)
        if (remote.sex.isNotEmpty()) {
            val sexIds = remote.sex.mapNotNull { slug -> ctx.optionIdByCategoryAndSlug["sex" to slug] }
            if (sexIds.isNotEmpty()) {
                val payload = Json.encodeToString(SEX_LIST, sexIds)
                val now = Clock.System.now().toString()
                db.dailyLogSubsQueries.upsertSex(Uuid.random().toString(), logId, payload, now, now, null)
            }
        }

        val existingPain = db.painLogsQueries.selectByLogId(logId).executeAsOneOrNull()
        if (existingPain != null) {
            db.painLogsQueries.deleteLocationsByPainLogId(existingPain.id)
            db.painLogsQueries.deletePainLogByLogId(logId)
        }
        if (remote.pain.isNotEmpty()) {
            val painLogId = Uuid.random().toString()
            val now = Clock.System.now().toString()
            db.painLogsQueries.insertPainLog(painLogId, logId, now, now, null)
            remote.pain.forEach { p ->
                val locId = ctx.locationIdBySlug[p.location] ?: return@forEach
                db.painLogsQueries.insertLocation(
                    Uuid.random().toString(), painLogId, locId, p.severity?.toLong(), now, now,
                )
            }
        }
    }

    fun applyRemotePreferences(remote: SyncPreferences) {
        val local = db.preferencesQueries.selectByUserId(userId).executeAsOneOrNull()

        val shouldApply =
            if (local == null) {
                true
            } else {
                mergeDecision(
                    localUpdatedAt = local.updated_at,
                    localId = userId,
                    remoteUpdatedAt = remote.updatedAt,
                    remoteId = userId,
                ) == MergeWinner.REMOTE
            }

        if (!shouldApply) return

        val json = Json.encodeToString(SLUG_LIST, remote.categoryOrder)
        db.preferencesQueries.upsert(
            local?.id ?: Uuid.random().toString(),
            userId,
            json,
            remote.updatedAt,
            null,
        )
    }

    private data class RefContext(
        val categoryIdBySlug: Map<String, String>,
        val optionIdByCategoryAndSlug: Map<Pair<String, String>, String>,
        val locationIdBySlug: Map<String, String>,
    )

    private fun buildRefContext(): RefContext {
        val categories = db.refDataQueries.selectAllCategories().executeAsList()
        val catIdBySlug = categories.associate { it.slug to it.id }
        val options = db.refDataQueries.selectAllOptions().executeAsList()
        val catSlugById = categories.associate { it.id to it.slug }
        val locations = db.refDataQueries.selectAllLocations().executeAsList()
        return RefContext(
            categoryIdBySlug = catIdBySlug,
            optionIdByCategoryAndSlug =
                options.associate { opt ->
                    (catSlugById[opt.category_id].orEmpty() to opt.slug) to opt.id
                },
            locationIdBySlug = locations.associate { it.slug to it.id },
        )
    }

    private companion object {
        val SLUG_LIST = ListSerializer(String.serializer())
        val SEX_LIST = ListSerializer(String.serializer())
    }
}
