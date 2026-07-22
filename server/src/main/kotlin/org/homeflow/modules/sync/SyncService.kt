package org.homeflow.modules.sync

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.homeflow.core.dto.ExportPain
import org.homeflow.core.dto.SyncCycle
import org.homeflow.core.dto.SyncDay
import org.homeflow.core.dto.SyncPreferences
import org.homeflow.core.dto.SyncPullResponse
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.core.dto.SyncPushResponse
import org.homeflow.core.service.CycleBoundary
import org.homeflow.core.service.MergeWinner
import org.homeflow.core.service.mergeDecision
import org.homeflow.core.service.reconcileOpenCycles
import org.homeflow.lib.Encryption
import org.homeflow.lib.parseIsoDate
import org.homeflow.lib.toIsoString
import org.homeflow.modules.cycles.CyclesRepository
import org.homeflow.modules.dailylogs.AssembledPainLocation
import org.homeflow.modules.dailylogs.DailyLogSubsRepository
import org.homeflow.modules.dailylogs.DailyLogsRepository
import org.homeflow.modules.preferences.PreferencesRepository
import org.homeflow.modules.refdata.RefDataRepository
import org.homeflow.modules.refdata.SymptomOptionRow
import org.homeflow.modules.users.UserPrincipal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Push and pull handlers for the Phase 16a sync endpoints (`__docs/API.md`).
 *
 * **Push** (`POST /api/v1/sync/changes`): applies incoming entity updates from a client
 * using Last-Write-Wins (LWW) merge. After applying, returns the server's current state
 * of every pushed entity plus the new cursor.
 *
 * **Pull** (`GET /api/v1/sync/changes?cursor=N`): returns all change rows recorded
 * since the cursor, with full entity data (decrypted notes + sex slugs) for live rows
 * and tombstone shells for deleted rows.
 *
 * Encryption boundary: this service decrypts notes/sex on pull and encrypts on push.
 * No plaintext health data is logged; the repositories never see plaintext.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one repository per synced entity, by design
class SyncService(
    private val cyclesRepository: CyclesRepository,
    private val dailyLogsRepository: DailyLogsRepository,
    private val dailyLogSubsRepository: DailyLogSubsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val changeLogRepository: ChangeLogRepository,
    private val refDataRepository: RefDataRepository,
    private val encryption: Encryption,
    /** Max change rows per pull page (SEC-02). Injectable so tests can use a tiny page. */
    private val pullPageSize: Int = SYNC_PULL_PAGE_SIZE,
) {
    // ──────────────────────────────────────────────────────────────────────────
    // PUSH
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Applies each entity in [request] using LWW. For every pushed entity, the server's
     * post-apply state is returned so the client can confirm what was accepted and update
     * its cursor.
     *
     * Slug mapping is loaded once per call (ref data is immutable at runtime).
     */
    fun push(
        principal: UserPrincipal,
        request: SyncPushRequest,
    ): SyncPushResponse {
        val ctx = buildRefDataContext()
        val userId = principal.id

        request.cycles.forEach { applyPushCycle(userId, it) }
        val resultDays = request.days.map { applyPushDay(userId, it, ctx) }
        val resultPrefs = request.preferences?.let { applyPushPreferences(userId, it) }

        // After applying incoming cycles via LWW, enforce the at-most-one-open-cycle
        // invariant with the shared :core rule (D-16a.6) rather than raw LWW — two devices
        // each starting a cycle offline must converge to one open cycle.
        reconcileServerOpenCycles(userId)

        // Re-read each pushed cycle so the response reflects any auto-close from reconcile
        // (the client adopts the authoritative post-merge state immediately).
        val resultCycles =
            request.cycles.map { incoming ->
                cyclesRepository.findByIdIncludingDeleted(userId, UUID.fromString(incoming.id))?.toSyncCycle()
                    ?: incoming.copy(deleted = true)
            }

        return SyncPushResponse(
            cycles = resultCycles,
            days = resultDays,
            preferences = resultPrefs,
            cursor = changeLogRepository.maxSeq(userId),
        )
    }

    /**
     * Collapses multiple open cycles to one (latest start stays open; earlier ones are
     * auto-closed at `autoCloseEndDate(nextStart)`) using the shared [reconcileOpenCycles]
     * rule, so the server and every client compute identical boundaries (D-16a.6 / D-16b.3).
     */
    private fun reconcileServerOpenCycles(userId: UUID) {
        val open = cyclesRepository.findAllByUserAscending(userId).filter { it.endDate == null }
        if (open.size <= 1) return
        val boundaries = open.map { CycleBoundary(it.id.toString(), it.startDate, it.endDate) }
        reconcileOpenCycles(boundaries).forEach { boundary ->
            val newEnd = boundary.endDate ?: return@forEach
            cyclesRepository.updateEndDate(userId, UUID.fromString(boundary.id), newEnd)
        }
    }

    private fun applyPushCycle(
        userId: UUID,
        incoming: SyncCycle,
    ): SyncCycle {
        val cycleId = UUID.fromString(incoming.id)
        val existing = cyclesRepository.findByIdIncludingDeleted(userId, cycleId)

        val shouldApply =
            if (existing == null) {
                true
            } else {
                mergeDecision(
                    localUpdatedAt = existing.updatedAt.toIsoString(),
                    localId = existing.id.toString(),
                    remoteUpdatedAt = incoming.updatedAt,
                    remoteId = incoming.id,
                ) == MergeWinner.REMOTE
            }

        if (shouldApply) {
            if (incoming.deleted) {
                cyclesRepository.softDeleteCascade(userId, cycleId)
            } else {
                val start = parseIsoDate(incoming.startDate)
                val end = incoming.endDate?.let { parseIsoDate(it) }
                val updatedAt = OffsetDateTime.parse(incoming.updatedAt)
                if (existing == null) {
                    cyclesRepository.insertExplicit(userId, start, end, cycleId)
                } else {
                    cyclesRepository.updateDates(userId, cycleId, start, end, updatedAt)
                }
            }
        }

        val current = cyclesRepository.findByIdIncludingDeleted(userId, cycleId)
        return current?.toSyncCycle() ?: incoming.copy(deleted = true)
    }

    private fun applyPushDay(
        userId: UUID,
        incoming: SyncDay,
        ctx: RefDataContext,
    ): SyncDay {
        val dayId = UUID.fromString(incoming.id)
        val existing = dailyLogsRepository.findByIdIncludingDeleted(userId, dayId)

        val shouldApply =
            if (existing == null) {
                true
            } else {
                mergeDecision(
                    localUpdatedAt = existing.updatedAt.toIsoString(),
                    localId = existing.id.toString(),
                    remoteUpdatedAt = incoming.updatedAt,
                    remoteId = incoming.id,
                ) == MergeWinner.REMOTE
            }

        if (shouldApply) {
            if (incoming.deleted) {
                dailyLogsRepository.softDeleteById(userId, dayId)
            } else {
                val cycleId = UUID.fromString(incoming.cycleId)
                val date = parseIsoDate(incoming.date)
                val updatedAt = OffsetDateTime.parse(incoming.updatedAt)

                dailyLogsRepository.upsertById(userId, dayId, cycleId, date, updatedAt)

                val notesEncrypted = incoming.notes?.let(encryption::encrypt)
                val sexEncrypted = encryptSexPayload(incoming.sex, ctx)

                dailyLogSubsRepository.applyAllFromSync(
                    userId = userId,
                    anchorId = dayId,
                    emotions = resolveSlugs(incoming.emotions, EMOTIONS, ctx.optionsByCategoryAndSlug),
                    sleep = resolveSlugs(incoming.sleep, SLEEP_QUALITY, ctx.optionsByCategoryAndSlug),
                    discharge = resolveSlugs(incoming.discharge, DISCHARGE, ctx.optionsByCategoryAndSlug),
                    skin = resolveSlugs(incoming.skin, SKIN, ctx.optionsByCategoryAndSlug),
                    digestion = resolveSlugs(incoming.digestion, DIGESTION, ctx.optionsByCategoryAndSlug),
                    mind = resolveSlugs(incoming.mind, MIND, ctx.optionsByCategoryAndSlug),
                    energy = resolveSlug(incoming.energy, ENERGY, ctx.optionsByCategoryAndSlug),
                    flow = resolveSlug(incoming.flow, BLOOD_FLOW, ctx.optionsByCategoryAndSlug),
                    collection =
                        resolveSlug(incoming.collectionMethod, COLLECTION_METHOD, ctx.optionsByCategoryAndSlug),
                    sexEncryptedPayload = sexEncrypted,
                    painLocations = resolvePain(incoming.pain, ctx.locationIdBySlug),
                    notesEncrypted = notesEncrypted,
                    updatedAt = updatedAt,
                )
            }
        }

        val assembledCurrent = dailyLogSubsRepository.assembleDayById(userId, dayId)
        return if (assembledCurrent == null || assembledCurrent.anchor.deletedAt != null) {
            incoming.copy(deleted = true, sex = emptyList(), pain = emptyList(), notes = null)
        } else {
            assembleSyncDay(assembledCurrent, ctx)
        }
    }

    /** Encrypts the sex selection (slugs → option ids → JSON), or null when nothing is selected. */
    private fun encryptSexPayload(
        sex: List<String>,
        ctx: RefDataContext,
    ): String? {
        if (sex.isEmpty()) return null
        val sexIds = resolveSlugs(sex, SEX, ctx.optionsByCategoryAndSlug)
        if (sexIds.isEmpty()) return null
        return encryption.encrypt(Json.encodeToString(ID_LIST, sexIds.map(UUID::toString)))
    }

    private fun applyPushPreferences(
        userId: UUID,
        incoming: SyncPreferences,
    ): SyncPreferences {
        val existing = preferencesRepository.find(userId)

        val shouldApply =
            if (existing == null) {
                true
            } else {
                mergeDecision(
                    localUpdatedAt = existing.updatedAt.toIsoString(),
                    localId = userId.toString(),
                    remoteUpdatedAt = incoming.updatedAt,
                    remoteId = userId.toString(),
                ) == MergeWinner.REMOTE
            }

        if (shouldApply) {
            val json = Json.encodeToString(SLUG_LIST, incoming.categoryOrder)
            preferencesRepository.upsert(userId, json)
        }

        val current = preferencesRepository.find(userId)
        return current?.let {
            SyncPreferences(
                categoryOrder = Json.decodeFromString(SLUG_LIST, it.categoryOrder),
                updatedAt = it.updatedAt.toIsoString(),
            )
        } ?: incoming
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PULL
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns changes since [cursor] (exclusive), capped at [pullPageSize] rows per
     * page (SEC-02). Each change row causes a full entity read — live entities are
     * returned with all their data (decrypted), deleted entities are returned as
     * tombstones (id + updatedAt + deleted=true). The response `cursor` is the seq of
     * the last row in this page and `hasMore` tells the client to pull again from it.
     */
    fun pull(
        principal: UserPrincipal,
        cursor: Long,
    ): SyncPullResponse {
        val ctx = buildRefDataContext()
        val userId = principal.id
        val page = changeLogRepository.findChangesSince(userId, cursor, pullPageSize + 1)
        val hasMore = page.size > pullPageSize
        val changes = if (hasMore) page.subList(0, pullPageSize) else page

        val resultCycles = mutableListOf<SyncCycle>()
        val resultDays = mutableListOf<SyncDay>()
        var resultPrefs: SyncPreferences? = null

        for (change in changes) {
            when (change.entityType) {
                ChangeLogRepository.TYPE_CYCLE -> {
                    val cycle = cyclesRepository.findByIdIncludingDeleted(userId, change.entityId)
                    if (cycle != null) resultCycles.add(cycle.toSyncCycle())
                }
                ChangeLogRepository.TYPE_DAY ->
                    readDayChange(userId, change, ctx)?.let(resultDays::add)
                ChangeLogRepository.TYPE_PREFERENCES -> {
                    val prefs = preferencesRepository.find(userId)
                    if (prefs != null) {
                        resultPrefs =
                            SyncPreferences(
                                categoryOrder = Json.decodeFromString(SLUG_LIST, prefs.categoryOrder),
                                updatedAt = prefs.updatedAt.toIsoString(),
                            )
                    }
                }
            }
        }

        val newCursor = changes.maxOfOrNull { it.serverSeq } ?: cursor
        return SyncPullResponse(
            cycles = resultCycles,
            days = resultDays,
            preferences = resultPrefs,
            cursor = newCursor,
            hasMore = hasMore,
        )
    }

    /** A pulled `day` change → a tombstone [SyncDay] when deleted, the assembled day when live, else null. */
    private fun readDayChange(
        userId: UUID,
        change: SyncChangeRow,
        ctx: RefDataContext,
    ): SyncDay? {
        if (change.deleted) {
            return SyncDay(
                id = change.entityId.toString(),
                date = "",
                cycleId = "",
                updatedAt = change.updatedAt.toIsoString(),
                deleted = true,
            )
        }
        val assembled = dailyLogSubsRepository.assembleDayById(userId, change.entityId) ?: return null
        return assembleSyncDay(assembled, ctx)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Assembly helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun assembleSyncDay(
        assembled: org.homeflow.modules.dailylogs.AssembledDay,
        ctx: RefDataContext,
    ): SyncDay {
        val anchor = assembled.anchor
        val sexSlugs =
            assembled.sexEncryptedPayload?.let { ciphertext ->
                val ids = Json.decodeFromString(ID_LIST, encryption.decrypt(ciphertext))
                ids.mapNotNull { idStr -> ctx.optionSlugById[UUID.fromString(idStr)] }
            } ?: emptyList()

        return SyncDay(
            id = anchor.id.toString(),
            date = anchor.logDate.toString(),
            cycleId = anchor.cycleId.toString(),
            flow = assembled.flow?.let(ctx.optionSlugById::get),
            collectionMethod = assembled.collection?.let(ctx.optionSlugById::get),
            energy = assembled.energy?.let(ctx.optionSlugById::get),
            emotions = assembled.emotions.mapNotNull(ctx.optionSlugById::get),
            sleep = assembled.sleep.mapNotNull(ctx.optionSlugById::get),
            discharge = assembled.discharge.mapNotNull(ctx.optionSlugById::get),
            skin = assembled.skin.mapNotNull(ctx.optionSlugById::get),
            digestion = assembled.digestion.mapNotNull(ctx.optionSlugById::get),
            mind = assembled.mind.mapNotNull(ctx.optionSlugById::get),
            sex = sexSlugs,
            pain =
                assembled.pain?.locations?.mapNotNull { loc ->
                    ctx.locationSlugById[loc.locationId]?.let { slug ->
                        ExportPain(location = slug, severity = loc.severity)
                    }
                } ?: emptyList(),
            notes = anchor.notes?.let(encryption::decrypt),
            updatedAt = anchor.updatedAt.toIsoString(),
            deleted = anchor.deletedAt != null,
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Ref-data context
    // ──────────────────────────────────────────────────────────────────────────

    private data class RefDataContext(
        /** (categorySlug, optionSlug) → option UUID */
        val optionsByCategoryAndSlug: Map<Pair<String, String>, UUID>,
        /** option UUID → slug */
        val optionSlugById: Map<UUID, String>,
        /** location slug → location UUID */
        val locationIdBySlug: Map<String, UUID>,
        /** location UUID → slug */
        val locationSlugById: Map<UUID, String>,
    )

    private fun buildRefDataContext(): RefDataContext {
        val categories = refDataRepository.symptomCategories().associate { it.id to it.slug }
        val options: List<SymptomOptionRow> = refDataRepository.symptomOptions()
        val locations = refDataRepository.painLocations()

        return RefDataContext(
            optionsByCategoryAndSlug =
                options.associate { opt ->
                    (categories[opt.categoryId].orEmpty() to opt.slug) to opt.id
                },
            optionSlugById = options.associate { it.id to it.slug },
            locationIdBySlug = locations.associate { it.slug to it.id },
            locationSlugById = locations.associate { it.id to it.slug },
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Slug resolution helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Resolves a list of slugs in [category] to UUIDs, silently dropping unknowns (like import). */
    private fun resolveSlugs(
        slugs: List<String>,
        category: String,
        map: Map<Pair<String, String>, UUID>,
    ): List<UUID> = slugs.mapNotNull { slug -> map[category to slug] }

    /** Resolves a single optional slug (null → null, unknown slug → null). */
    private fun resolveSlug(
        slug: String?,
        category: String,
        map: Map<Pair<String, String>, UUID>,
    ): UUID? = slug?.let { map[category to it] }

    /** Resolves pain location slugs in [pain] to AssembledPainLocation, dropping unknowns. */
    private fun resolvePain(
        pain: List<ExportPain>,
        locationIdBySlug: Map<String, UUID>,
    ): List<AssembledPainLocation> =
        pain.mapNotNull { p ->
            locationIdBySlug[p.location]?.let { id ->
                AssembledPainLocation(locationId = id, severity = p.severity)
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Mappers
    // ──────────────────────────────────────────────────────────────────────────

    private fun org.homeflow.modules.cycles.CycleRow.toSyncCycle(): SyncCycle =
        SyncCycle(
            id = id.toString(),
            startDate = startDate.toString(),
            endDate = endDate?.toString(),
            updatedAt = updatedAt.toIsoString(),
            deleted = deletedAt != null,
        )

    companion object {
        /** Max change rows returned per pull request (SEC-02). */
        const val SYNC_PULL_PAGE_SIZE = 500

        private const val EMOTIONS = "emotions"
        private const val SLEEP_QUALITY = "sleep_quality"
        private const val ENERGY = "energy"
        private const val SEX = "sex"
        private const val DISCHARGE = "discharge"
        private const val SKIN = "skin"
        private const val DIGESTION = "digestion"
        private const val BLOOD_FLOW = "blood_flow"
        private const val COLLECTION_METHOD = "collection_method"
        private const val MIND = "mind"

        private val ID_LIST = ListSerializer(String.serializer())
        private val SLUG_LIST = ListSerializer(String.serializer())
    }
}
