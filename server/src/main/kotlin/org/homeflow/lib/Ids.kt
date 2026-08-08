package org.homeflow.lib

import java.util.UUID

/** Parses a UUID, returning null instead of throwing on a malformed string. */
fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

/**
 * Parses a UUID, throwing [ValidationException] (→ 400) on a malformed string. [field]
 * names the offending field in the error. Use this for untrusted client input so a bad
 * value surfaces as a clean 400 rather than an opaque 500.
 */
fun parseUuid(
    value: String,
    field: String = "id",
): UUID = value.toUuidOrNull() ?: throw ValidationException("Invalid $field: expected a UUID.")
