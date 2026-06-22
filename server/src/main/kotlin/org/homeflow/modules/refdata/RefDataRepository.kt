package org.homeflow.modules.refdata

import org.homeflow.db.RefPainLocations
import org.homeflow.db.RefPainRegions
import org.homeflow.db.RefSymptomCategories
import org.homeflow.db.RefSymptomOptions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** A `ref_symptom_categories` row. */
data class SymptomCategoryRow(
    val id: UUID,
    val slug: String,
    val label: String,
    val selectionType: String,
    val phase: String,
    val sortOrder: Int,
)

/** A `ref_symptom_options` row (grouped under its category by the service). */
data class SymptomOptionRow(
    val id: UUID,
    val categoryId: UUID,
    val slug: String,
    val label: String,
    val sortOrder: Int,
)

/** A `ref_pain_regions` row. */
data class PainRegionRow(
    val id: UUID,
    val slug: String,
    val label: String,
    val sortOrder: Int,
)

/** A `ref_pain_locations` row (grouped under its region by the service). */
data class PainLocationRow(
    val id: UUID,
    val regionId: UUID,
    val slug: String,
    val label: String,
    val sortOrder: Int,
)

/**
 * Read-only access to the seeded reference tables. These rows are not user-scoped —
 * they are the same for everyone and never change at runtime (seeded by Flyway). The
 * lookups [optionIdsForCategory] and [painLocationIds] are used by the symptom
 * sub-log service to validate submitted IDs before a write (see `__docs/API.md`).
 */
class RefDataRepository(
    private val db: Database,
) {
    /** All symptom categories, in display order. */
    fun symptomCategories(): List<SymptomCategoryRow> =
        transaction(db) {
            RefSymptomCategories
                .selectAll()
                .orderBy(RefSymptomCategories.sortOrder to SortOrder.ASC)
                .map(::toCategoryRow)
        }

    /** All symptom options across every category, ordered for grouping by the service. */
    fun symptomOptions(): List<SymptomOptionRow> =
        transaction(db) {
            RefSymptomOptions
                .selectAll()
                .orderBy(RefSymptomOptions.sortOrder to SortOrder.ASC)
                .map(::toOptionRow)
        }

    /** All pain regions, in display order. */
    fun painRegions(): List<PainRegionRow> =
        transaction(db) {
            RefPainRegions
                .selectAll()
                .orderBy(RefPainRegions.sortOrder to SortOrder.ASC)
                .map(::toRegionRow)
        }

    /** All pain locations across every region, ordered for grouping by the service. */
    fun painLocations(): List<PainLocationRow> =
        transaction(db) {
            RefPainLocations
                .selectAll()
                .orderBy(RefPainLocations.sortOrder to SortOrder.ASC)
                .map(::toLocationRow)
        }

    /** The set of valid option IDs in the category identified by [categorySlug]. */
    fun optionIdsForCategory(categorySlug: String): Set<UUID> =
        transaction(db) {
            (RefSymptomOptions innerJoin RefSymptomCategories)
                .selectAll()
                .where { RefSymptomCategories.slug eq categorySlug }
                .map { it[RefSymptomOptions.id] }
                .toSet()
        }

    /** The set of all valid pain location IDs (used to validate a pain log write). */
    fun painLocationIds(): Set<UUID> =
        transaction(db) {
            RefPainLocations
                .selectAll()
                .map { it[RefPainLocations.id] }
                .toSet()
        }

    private fun toCategoryRow(row: ResultRow): SymptomCategoryRow =
        SymptomCategoryRow(
            id = row[RefSymptomCategories.id],
            slug = row[RefSymptomCategories.slug],
            label = row[RefSymptomCategories.label],
            selectionType = row[RefSymptomCategories.selectionType],
            phase = row[RefSymptomCategories.phase],
            sortOrder = row[RefSymptomCategories.sortOrder],
        )

    private fun toOptionRow(row: ResultRow): SymptomOptionRow =
        SymptomOptionRow(
            id = row[RefSymptomOptions.id],
            categoryId = row[RefSymptomOptions.categoryId],
            slug = row[RefSymptomOptions.slug],
            label = row[RefSymptomOptions.label],
            sortOrder = row[RefSymptomOptions.sortOrder],
        )

    private fun toRegionRow(row: ResultRow): PainRegionRow =
        PainRegionRow(
            id = row[RefPainRegions.id],
            slug = row[RefPainRegions.slug],
            label = row[RefPainRegions.label],
            sortOrder = row[RefPainRegions.sortOrder],
        )

    private fun toLocationRow(row: ResultRow): PainLocationRow =
        PainLocationRow(
            id = row[RefPainLocations.id],
            regionId = row[RefPainLocations.regionId],
            slug = row[RefPainLocations.slug],
            label = row[RefPainLocations.label],
            sortOrder = row[RefPainLocations.sortOrder],
        )
}
