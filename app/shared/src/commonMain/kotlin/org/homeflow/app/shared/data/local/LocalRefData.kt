package org.homeflow.app.shared.data.local

import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.dto.PainLocationRefDto
import org.homeflow.core.dto.PainRegionDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.SymptomCategoryDto
import org.homeflow.core.dto.SymptomOptionDto

/**
 * Read-only access to the seeded reference tables.
 */
class LocalRefData(
    private val db: HomeFlowDb,
) {
    fun getSymptomCategories(): SymptomCategoriesResponse {
        val q = db.refDataQueries
        val cats = q.selectAllCategories().executeAsList()
        return SymptomCategoriesResponse(
            categories =
                cats.map { cat ->
                    val opts = q.selectOptionsByCategory(cat.id).executeAsList()
                    SymptomCategoryDto(
                        id = cat.id,
                        slug = cat.slug,
                        label = cat.label,
                        selectionType = cat.selection_type,
                        phase = cat.phase,
                        sortOrder = cat.sort_order.toInt(),
                        options =
                            opts.map { opt ->
                                SymptomOptionDto(
                                    id = opt.id,
                                    slug = opt.slug,
                                    label = opt.label,
                                    sortOrder = opt.sort_order.toInt(),
                                )
                            },
                    )
                },
        )
    }

    fun getPainRegions(): PainRegionsResponse {
        val q = db.refDataQueries
        val regions = q.selectAllRegions().executeAsList()
        return PainRegionsResponse(
            regions =
                regions.map { region ->
                    val locs = q.selectLocationsByRegion(region.id).executeAsList()
                    PainRegionDto(
                        id = region.id,
                        slug = region.slug,
                        label = region.label,
                        sortOrder = region.sort_order.toInt(),
                        locations =
                            locs.map { loc ->
                                PainLocationRefDto(
                                    id = loc.id,
                                    slug = loc.slug,
                                    label = loc.label,
                                    sortOrder = loc.sort_order.toInt(),
                                )
                            },
                    )
                },
        )
    }

    /** Returns the category id for a given slug, or null if not found. */
    fun categoryIdBySlug(slug: String): String? =
        db.refDataQueries
            .selectCategoryBySlug(slug)
            .executeAsOneOrNull()
            ?.id

    /** Returns the set of valid option ids for a given category id. */
    fun optionIdsForCategory(categoryId: String): Set<String> =
        db.refDataQueries
            .selectOptionsByCategory(categoryId)
            .executeAsList()
            .map { it.id }
            .toSet()

    /** Returns the slug→id map for all categories (for preference ordering). */
    fun allCategorySlugs(): Set<String> =
        db.refDataQueries
            .selectAllCategories()
            .executeAsList()
            .map { it.slug }
            .toSet()

    /** Validates that a location id is known in the ref data. */
    fun locationExists(locationId: String): Boolean =
        db.refDataQueries.selectLocationById(locationId).executeAsOneOrNull() != null

    /** Returns the default ordering: category slugs in sort_order. */
    fun defaultCategoryOrder(): List<String> =
        db.refDataQueries
            .selectAllCategories()
            .executeAsList()
            .sortedBy { it.sort_order }
            .map { it.slug }
}
