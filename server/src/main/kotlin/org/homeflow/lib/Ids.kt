package org.homeflow.lib

import java.util.UUID

/** Parses a UUID, returning null instead of throwing on a malformed string. */
fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
