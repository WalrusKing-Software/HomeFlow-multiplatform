package org.homeflow.app.shared.data.local

import org.homeflow.app.shared.db.HomeFlowDb
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Seeds reference data and the single local user row on first open.
 * Safe to call every launch — uses INSERT OR IGNORE / count-guard.
 *
 * Call order matters: categories before options, regions before locations.
 */
@OptIn(ExperimentalUuidApi::class)
object LocalBootstrap {

    /** Fixed local user id — there is exactly one local user (D-13.6). */
    const val LOCAL_USER_ID = "00000000-0000-0000-0000-000000000001"

    fun seed(db: HomeFlowDb) {
        val q = db.refDataQueries
        val already = q.countCategories().executeAsOne()
        if (already > 0L) return   // already seeded

        val now = Clock.System.now().toString()

        // ── Users row ──────────────────────────────────────────────────────────
        db.usersQueries.insert(LOCAL_USER_ID, now, now)

        // ── Symptom categories + options ───────────────────────────────────────
        for (cat in RefSeed.CATEGORIES) {
            val catId = Uuid.random().toString()
            q.insertCategory(catId, cat.slug, cat.label, cat.selectionType, cat.phase, cat.sortOrder.toLong())
            for (opt in cat.options) {
                val optId = Uuid.random().toString()
                q.insertOption(optId, catId, opt.slug, opt.label, opt.sortOrder.toLong())
            }
        }

        // ── Pain regions + locations ───────────────────────────────────────────
        for (region in RefSeed.PAIN_REGIONS) {
            val regionId = Uuid.random().toString()
            q.insertRegion(regionId, region.slug, region.label, region.sortOrder.toLong())
            for (loc in region.locations) {
                val locId = Uuid.random().toString()
                q.insertLocation(locId, regionId, loc.slug, loc.label, loc.sortOrder.toLong())
            }
        }
    }
}
