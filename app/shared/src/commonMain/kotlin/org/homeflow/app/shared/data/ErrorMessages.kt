package org.homeflow.app.shared.data

import org.homeflow.core.ErrorCode

/**
 * Maps a typed backend [ErrorCode] (and the server's own message) to a single human-readable
 * line for the UI. The goal is error UX that is calm and actionable rather than raw codes:
 *
 * - For codes whose server message is already user-facing and specific (validation, conflict,
 *   not-found), we surface the server's [serverMessage].
 * - For transport/auth/server faults, we show a friendly, generic line — the server message for
 *   these is often an exception string or `null`, and must never leak internals to the user.
 *
 * This keeps health data and stack details out of the UI while still telling the user what to do.
 */
fun userFacingMessage(
    code: ErrorCode,
    serverMessage: String?,
): String {
    val server = serverMessage?.takeIf { it.isNotBlank() }
    return when (code) {
        ErrorCode.VALIDATION_ERROR -> server ?: "That input wasn't valid. Please check it and try again."
        ErrorCode.CONFLICT -> server ?: "That conflicts with something already saved."
        ErrorCode.RESOURCE_NOT_FOUND -> server ?: "We couldn't find that."
        ErrorCode.UNAUTHORIZED -> "Your session has expired. Please log in again."
        ErrorCode.FORBIDDEN -> "You don't have access to that."
        ErrorCode.INTERNAL_ERROR -> "Something went wrong reaching the server. Check your connection and try again."
    }
}

/** Convenience for surfacing an [ApiResult.Failure] to the user. */
fun ApiResult.Failure.userMessage(): String = userFacingMessage(code, message)
