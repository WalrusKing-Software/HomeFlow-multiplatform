package org.homeflow.lib

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorBody
import org.homeflow.core.ErrorCode

/**
 * Typed application errors. Services and routes throw these; the `StatusPages`
 * plugin maps each [code] to its HTTP status and serializes the canonical
 * [ApiError] body (defined once in `:core`). Handlers never format errors by hand.
 *
 * Cross-user reads must throw [NotFoundException], never [ForbiddenException] — we
 * don't reveal that another user's resource exists (see `__docs/threat-model.md`).
 */
sealed class AppException(
    val code: ErrorCode,
    override val message: String,
) : RuntimeException(message)

class UnauthorizedException(
    message: String = "Authentication required.",
) : AppException(ErrorCode.UNAUTHORIZED, message)

class ForbiddenException(
    message: String = "You do not have access to this resource.",
) : AppException(ErrorCode.FORBIDDEN, message)

class NotFoundException(
    message: String = "Resource not found.",
) : AppException(ErrorCode.RESOURCE_NOT_FOUND, message)

class ValidationException(
    message: String = "The request failed validation.",
) : AppException(ErrorCode.VALIDATION_ERROR, message)

class ConflictException(
    message: String = "The resource already exists.",
) : AppException(ErrorCode.CONFLICT, message)

class InternalException(
    message: String = "An unexpected error occurred.",
) : AppException(ErrorCode.INTERNAL_ERROR, message)

/** The fixed HTTP status for each [ErrorCode] (mirrors the table in CLAUDE.md). */
fun ErrorCode.httpStatus(): HttpStatusCode =
    when (this) {
        ErrorCode.UNAUTHORIZED -> HttpStatusCode.Unauthorized
        ErrorCode.FORBIDDEN -> HttpStatusCode.Forbidden
        ErrorCode.RESOURCE_NOT_FOUND -> HttpStatusCode.NotFound
        ErrorCode.VALIDATION_ERROR -> HttpStatusCode.BadRequest
        ErrorCode.CONFLICT -> HttpStatusCode.Conflict
        ErrorCode.INTERNAL_ERROR -> HttpStatusCode.InternalServerError
    }

/** Serializes the canonical error body with the status mapped from [code]. */
suspend fun ApplicationCall.respondError(
    code: ErrorCode,
    message: String,
) = respond(code.httpStatus(), ApiError(ErrorBody(code, message)))
