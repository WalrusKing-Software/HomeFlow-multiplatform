package org.homeflow.app.shared.data.sync

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.homeflow.app.shared.db.Cycles
import org.homeflow.app.shared.db.Daily_logs
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.dto.ExportPain
import org.homeflow.core.dto.SyncCycle
import org.homeflow.core.dto.SyncDay
import org.homeflow.core.dto.SyncPreferences

/**
 * Assembles sync wire DTOs from the local SQLDelight database. Sub-log option IDs are
 * mapped to slugs via the seeded ref tables — the same data the server uses, so slugs
 * are stable across both sides.
 *
 * Notes are stored as plaintext inside the SQLCipher-encrypted DB; they flow to the
 * server over TLS as plaintext and are re-encrypted server-side (never on the client).
 * Sex payloads are stored as a plaintext JSON array of option-id strings locally;
 * the assembler maps them to slugs before sending.
 */
class LocalSyncAssembler(
    private val db: HomeFlowDb,
) {
    /** Builds a [SyncCycle] from a local cycles row. */
    fun assembleCycle(row: Cycles): SyncCycle =
        SyncCycle(
            id = row.id,
            startDate = row.start_date,
            endDate = row.end_date,
            updatedAt = row.updated_at,
            deleted = row.deleted_at != null,
        )

    /**
     * Builds a [SyncDay] from a local daily_logs anchor row [row] plus all its sub-logs.
     * Returns a tombstone (deleted=true, minimal fields) if [row.deleted_at] is set.
     */
    fun assembleDay(row: Daily_logs): SyncDay {
        if (row.deleted_at != null) {
            return SyncDay(
                id = row.id,
                date = row.log_date,
                cycleId = row.cycle_id,
                updatedAt = row.updated_at,
                deleted = true,
            )
        }

        val ctx = buildRefContext()
        val logId = row.id

        val multiEntries = db.dailyLogSubsQueries.selectAllMultiByLog(logId).executeAsList()
        val singleEntries = db.dailyLogSubsQueries.selectAllSingleByLog(logId).executeAsList()

        val multiByCategory = multiEntries.groupBy { it.category_id }
        val singleByCategory = singleEntries.associate { it.category_id to it.option_id }

        fun multiSlugs(catSlug: String): List<String> {
            val catId = ctx.categoryIdBySlug[catSlug] ?: return emptyList()
            return multiByCategory[catId]?.mapNotNull { ctx.optionSlugById[it.option_id] } ?: emptyList()
        }

        fun singleSlug(catSlug: String): String? {
            val catId = ctx.categoryIdBySlug[catSlug] ?: return null
            val optId = singleByCategory[catId] ?: return null
            return ctx.optionSlugById[optId]
        }

        val sexSlugs =
            db.dailyLogSubsQueries.selectSexByLogId(logId).executeAsOneOrNull()?.let { payload ->
                val ids = Json.decodeFromString(ID_LIST, payload)
                ids.mapNotNull { ctx.optionSlugById[it] }
            } ?: emptyList()

        val painRow = db.painLogsQueries.selectByLogId(logId).executeAsOneOrNull()
        val painSlugs =
            if (painRow != null) {
                db.painLogsQueries
                    .selectLocationsByPainLogId(painRow.id)
                    .executeAsList()
                    .mapNotNull { loc ->
                        ctx.locationSlugById[loc.location_id]?.let { slug ->
                            ExportPain(location = slug, severity = loc.severity?.toInt())
                        }
                    }
            } else {
                emptyList()
            }

        return SyncDay(
            id = row.id,
            date = row.log_date,
            cycleId = row.cycle_id,
            flow = singleSlug(BLOOD_FLOW),
            collectionMethod = singleSlug(COLLECTION_METHOD),
            energy = singleSlug(ENERGY),
            emotions = multiSlugs(EMOTIONS),
            sleep = multiSlugs(SLEEP_QUALITY),
            discharge = multiSlugs(DISCHARGE),
            skin = multiSlugs(SKIN),
            digestion = multiSlugs(DIGESTION),
            mind = multiSlugs(MIND),
            sex = sexSlugs,
            pain = painSlugs,
            notes = row.notes,
            updatedAt = row.updated_at,
            deleted = false,
        )
    }

    /** Builds [SyncPreferences] from the user's locally saved preferences, or null if none saved. */
    fun assemblePreferences(userId: String): SyncPreferences? {
        val row = db.preferencesQueries.selectByUserId(userId).executeAsOneOrNull() ?: return null
        if (row.deleted_at != null) return null
        val order = Json.decodeFromString(SLUG_LIST, row.category_order)
        return SyncPreferences(categoryOrder = order, updatedAt = row.updated_at)
    }

    // ── Ref data context ──────────────────────────────────────────────────────

    private data class RefContext(
        val categoryIdBySlug: Map<String, String>,
        val optionSlugById: Map<String, String>,
        val locationSlugById: Map<String, String>,
    )

    private fun buildRefContext(): RefContext {
        val categories = db.refDataQueries.selectAllCategories().executeAsList()
        val options = db.refDataQueries.selectAllOptions().executeAsList()
        val locations = db.refDataQueries.selectAllLocations().executeAsList()
        return RefContext(
            categoryIdBySlug = categories.associate { it.slug to it.id },
            optionSlugById = options.associate { it.id to it.slug },
            locationSlugById = locations.associate { it.id to it.slug },
        )
    }

    private companion object {
        const val EMOTIONS = "emotions"
        const val SLEEP_QUALITY = "sleep_quality"
        const val ENERGY = "energy"
        const val DISCHARGE = "discharge"
        const val SKIN = "skin"
        const val DIGESTION = "digestion"
        const val BLOOD_FLOW = "blood_flow"
        const val COLLECTION_METHOD = "collection_method"
        const val MIND = "mind"

        val ID_LIST = ListSerializer(String.serializer())
        val SLUG_LIST = ListSerializer(String.serializer())
    }
}
