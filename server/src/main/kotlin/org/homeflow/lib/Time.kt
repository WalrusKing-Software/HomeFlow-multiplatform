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
