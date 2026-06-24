package org.homeflow.core.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/import` result summary.
 *
 * Only this summary DTO and the native `homeflow` export/import envelope below are
 * shared. The other source-specific import payloads (Clue, Apple Health, CSV) are
 * multipart/file-format concerns that belong with a later import-source feature, not
 * the shared contract — they are intentionally not modelled here yet.
 */
@Serializable
data class ImportResultDto(
    val cyclesCreated: Int,
    val dailyLogsCreated: Int,
    val dailyLogsSkipped: Int,
    val warnings: List<String> = emptyList(),
)

/**
 * The native `homeflow` export/import envelope (`GET /api/v1/export?format=json`,
 * `POST /api/v1/import?source=homeflow`). Slug-and-date-keyed, UUID-free — see
 * `__docs/API.md` "Import & Export" and decision D4 in
 * `__docs/IMPLEMENTATION-PHASES-modular-offline.md`.
 */
@Serializable
data class HomeFlowExport(
    @SerialName("homeflow_export") val version: Int = 1,
    val cycles: List<ExportCycle>,
    val days: List<ExportDay>,
)

@Serializable
data class ExportCycle(
    val startDate: String,
    val endDate: String? = null,
)

@Serializable
data class ExportDay(
    val date: String,
    val cycleStartDate: String,
    val flow: String? = null,
    val collectionMethod: String? = null,
    val energy: String? = null,
    val emotions: List<String> = emptyList(),
    val sleep: List<String> = emptyList(),
    val discharge: List<String> = emptyList(),
    val skin: List<String> = emptyList(),
    val digestion: List<String> = emptyList(),
    val mind: List<String> = emptyList(),
    val sex: List<String> = emptyList(),
    val pain: List<ExportPain> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class ExportPain(
    val location: String,
    val severity: Int? = null,
)
