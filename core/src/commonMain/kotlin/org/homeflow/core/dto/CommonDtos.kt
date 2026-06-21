package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/** `GET /health` — `{ "status": "ok" }`. */
@Serializable
data class HealthDto(
    val status: String,
)

/**
 * `GET /api/v1/users/me`. The app-side user record (identity itself is owned by Keycloak).
 * Timestamps are ISO-8601 strings at the wire boundary.
 */
@Serializable
data class UserDto(
    val id: String,
    val createdAt: String,
)
