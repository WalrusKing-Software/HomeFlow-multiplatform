package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/import` result summary.
 *
 * Only this summary DTO is shared. The source-specific import payloads (Clue, Apple
 * Health, CSV) and the native export envelope are multipart/file-format concerns that
 * belong with the import/export feature phase, not the shared contract — they are
 * intentionally not modelled here yet.
 */
@Serializable
data class ImportResultDto(
    val cyclesCreated: Int,
    val dailyLogsCreated: Int,
    val dailyLogsSkipped: Int,
    val warnings: List<String> = emptyList(),
)
