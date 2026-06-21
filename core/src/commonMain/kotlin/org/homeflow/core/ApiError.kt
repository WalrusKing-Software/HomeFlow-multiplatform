package org.homeflow.core

import kotlinx.serialization.Serializable

/**
 * The canonical API error shape, defined once and shared by the Ktor server (which
 * serializes it) and the clients (which deserialize and pattern-match on [ErrorBody.code]).
 *
 * Wire shape:
 * ```json
 * { "error": { "code": "RESOURCE_NOT_FOUND", "message": "Daily log not found for the given date." } }
 * ```
 *
 * HTTP status mapping lives on the server (see CLAUDE.md); the codes themselves are shared.
 */
@Serializable
data class ApiError(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: ErrorCode,
    val message: String,
)

@Serializable
enum class ErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    RESOURCE_NOT_FOUND,
    VALIDATION_ERROR,
    CONFLICT,
    INTERNAL_ERROR,
}
