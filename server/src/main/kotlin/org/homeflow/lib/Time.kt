package org.homeflow.lib

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Renders a [OffsetDateTime] as the ISO-8601 string the API contract uses at the
 * wire boundary (`2024-02-14T20:00:00Z`), normalised to UTC. Centralised so every
 * service formats timestamps the same way.
 */
fun OffsetDateTime.toIsoString(): String =
    toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/**
 * Parses an ISO-8601 offset timestamp (e.g. `2024-02-14T20:00:00Z`), throwing
 * [ValidationException] (→ 400) on anything malformed. [field] names the offending
 * field. Use this for untrusted client input so a bad value surfaces as a clean 400
 * rather than an opaque 500.
 */
fun parseIsoTimestamp(
    value: String,
    field: String = "updatedAt",
): OffsetDateTime =
    runCatching { OffsetDateTime.parse(value) }
        .getOrElse { throw ValidationException("Invalid $field: expected an ISO-8601 timestamp.") }
