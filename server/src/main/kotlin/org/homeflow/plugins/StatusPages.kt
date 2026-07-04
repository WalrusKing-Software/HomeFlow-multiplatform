package org.homeflow.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import org.homeflow.core.ErrorCode
import org.homeflow.lib.AppException
import org.homeflow.lib.respondError
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.homeflow.plugins.StatusPages")

/**
 * Maps thrown errors to the canonical [org.homeflow.core.ApiError] shape. Typed
 * [AppException]s carry their own [ErrorCode]; malformed request bodies become
 * `400 VALIDATION_ERROR`; anything else is a `500 INTERNAL_ERROR` with a generic
 * message (never leak internals or PII — see CLAUDE.md "What Never Gets Logged").
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<AppException> { call, cause ->
            call.respondError(cause.code, cause.message)
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(ErrorCode.VALIDATION_ERROR, "The request body was missing or malformed.")
        }
        exception<Throwable> { call, cause ->
            // Log the type/message for diagnosis, never the body or any PII.
            logger.error("Unhandled error: {}", cause::class.simpleName)
            call.respondError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.")
        }
    }
}
