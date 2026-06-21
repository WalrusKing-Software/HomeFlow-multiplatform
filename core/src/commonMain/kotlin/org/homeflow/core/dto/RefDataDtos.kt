package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/** `GET /api/v1/ref-data/symptom-categories` — categories with nested options, in display order. */
@Serializable
data class SymptomCategoriesResponse(
    val categories: List<SymptomCategoryDto>,
)

@Serializable
data class SymptomCategoryDto(
    val id: String,
    val slug: String,
    val label: String,
    /** `single` or `multi`. */
    val selectionType: String,
    /** `always` or `menstruation`. */
    val phase: String,
    val sortOrder: Int,
    val options: List<SymptomOptionDto>,
)

@Serializable
data class SymptomOptionDto(
    val id: String,
    val slug: String,
    val label: String,
    val sortOrder: Int,
)

/** `GET /api/v1/ref-data/pain-regions` — regions with nested locations, in display order. */
@Serializable
data class PainRegionsResponse(
    val regions: List<PainRegionDto>,
)

@Serializable
data class PainRegionDto(
    val id: String,
    val slug: String,
    val label: String,
    val sortOrder: Int,
    val locations: List<PainLocationRefDto>,
)

@Serializable
data class PainLocationRefDto(
    val id: String,
    val slug: String,
    val label: String,
    val sortOrder: Int,
)
