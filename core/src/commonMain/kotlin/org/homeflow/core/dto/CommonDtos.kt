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

/**
 * `GET /api/v1/version` — no authentication required. Clients call this on every login
 * to verify the server is compatible. If [minClientVersion] is higher than the client's
 * version, the client shows an upgrade prompt and refuses to connect. A server that
 * predates this endpoint returns 404; clients treat 404 as compatible. See COMPATIBILITY.md.
 */
@Serializable
data class VersionDto(
    val serverVersion: String,
    val minClientVersion: String,
    val apiVersion: String,
)
