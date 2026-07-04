package org.homeflow.lib

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate

/**
 * Parses an ISO-8601 `YYYY-MM-DD` string, throwing [ValidationException] (→ 400) on
 * anything malformed. [field] names the offending field in the error message.
 */
fun parseIsoDate(
    value: String,
    field: String = "date",
): LocalDate =
    runCatching { LocalDate.parse(value) }
        .getOrElse { throw ValidationException("Invalid $field: expected an ISO-8601 date (YYYY-MM-DD).") }

/**
 * The server's current local date, used for "not in the future" checks. The server is
 * self-hosted for a single user, so its local timezone is the user's day boundary.
 */
fun today(): LocalDate =
    java.time.LocalDate
        .now()
        .toKotlinLocalDate()
