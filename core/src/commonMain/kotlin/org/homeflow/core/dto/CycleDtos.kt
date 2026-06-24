package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/**
 * A single cycle. `startDate`/`endDate` are ISO-8601 `yyyy-MM-dd` strings;
 * `endDate` is null for the currently open cycle. Cycle length is derived
 * (see domain/CycleMath), never stored.
 */
@Serializable
data class CycleDto(
    val id: String,
    val startDate: String,
    val endDate: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** `GET /api/v1/cycles` — all cycles, newest first. */
@Serializable
data class CyclesResponse(
    val cycles: List<CycleDto>,
)

/** `POST /api/v1/cycles` request. */
@Serializable
data class CreateCycleRequest(
    val startDate: String,
    val id: String? = null,
)

/** `PATCH /api/v1/cycles/:id` request — currently only closes the cycle. */
@Serializable
data class UpdateCycleRequest(
    val endDate: String,
)
