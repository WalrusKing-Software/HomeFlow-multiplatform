package org.homeflow.modules.refdata

import org.homeflow.core.dto.PainLocationRefDto
import org.homeflow.core.dto.PainRegionDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.SymptomCategoryDto
import org.homeflow.core.dto.SymptomOptionDto

/**
 * Assembles the read-only reference data into the nested response shapes from
 * `__docs/API.md` (categories with their options; regions with their locations).
 * A thin service over [RefDataRepository] — no business logic, just grouping and
 * DTO mapping (the layering rule still requires the service to exist).
 */
class RefDataService(
    private val refDataRepository: RefDataRepository,
) {
    /** `GET /api/v1/ref-data/symptom-categories` — categories with nested options. */
    fun getSymptomCategories(): SymptomCategoriesResponse {
        val optionsByCategory = refDataRepository.symptomOptions().groupBy { it.categoryId }
        val categories =
            refDataRepository.symptomCategories().map { category ->
                SymptomCategoryDto(
                    id = category.id.toString(),
                    slug = category.slug,
                    label = category.label,
                    selectionType = category.selectionType,
                    phase = category.phase,
                    sortOrder = category.sortOrder,
                    options =
                        optionsByCategory[category.id].orEmpty().map { option ->
                            SymptomOptionDto(
                                id = option.id.toString(),
                                slug = option.slug,
                                label = option.label,
                                sortOrder = option.sortOrder,
                            )
                        },
                )
            }
        return SymptomCategoriesResponse(categories)
    }

    /** `GET /api/v1/ref-data/pain-regions` — regions with nested locations. */
    fun getPainRegions(): PainRegionsResponse {
        val locationsByRegion = refDataRepository.painLocations().groupBy { it.regionId }
        val regions =
            refDataRepository.painRegions().map { region ->
                PainRegionDto(
                    id = region.id.toString(),
                    slug = region.slug,
                    label = region.label,
                    sortOrder = region.sortOrder,
                    locations =
                        locationsByRegion[region.id].orEmpty().map { location ->
                            PainLocationRefDto(
                                id = location.id.toString(),
                                slug = location.slug,
                                label = location.label,
                                sortOrder = location.sortOrder,
                            )
                        },
                )
            }
        return PainRegionsResponse(regions)
    }
}
