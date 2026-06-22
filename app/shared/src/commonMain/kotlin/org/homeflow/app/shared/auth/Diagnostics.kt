package org.homeflow.app.shared.auth

/**
 * Minimal diagnostic logging for the auth flow. Logs **only** non-sensitive signal —
 * step names, exception class/message chains, error codes, endpoint paths — never
 * tokens, request/response bodies, or health data (CLAUDE.md "What Never Gets Logged").
 */
internal expect fun authDebugLog(message: String)

/** Flattens a throwable's cause chain to class+message, for "why did the network call fail" logs. */
internal fun Throwable.describe(): String =
    buildString {
        var cause: Throwable? = this@describe
        var depth = 0
        while (cause != null && depth < 6) {
            if (depth > 0) append(" <- ")
            append(cause::class.simpleName).append(": ").append(cause.message)
            cause = cause.cause
            depth++
        }
    }
