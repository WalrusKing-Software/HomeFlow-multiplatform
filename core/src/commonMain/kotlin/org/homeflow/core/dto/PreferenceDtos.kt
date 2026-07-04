package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/** `GET /api/v1/preferences` — the dashboard category ordering (slugs). */
@Serializable
data class PreferencesDto(
    val categoryOrder: List<String>,
)

/** `PUT /api/v1/preferences` request — the full replacement ordering. */
@Serializable
data class UpdatePreferencesRequest(
    val categoryOrder: List<String>,
)

/** `PUT /api/v1/preferences` response. */
@Serializable
data class PreferencesResponse(
    val categoryOrder: List<String>,
    val updatedAt: String,
)
