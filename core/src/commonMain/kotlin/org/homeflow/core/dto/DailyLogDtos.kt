package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/**
 * Full day log (`GET /api/v1/daily-logs/:date`). Symptom fields are arrays of option-id
 * strings (or a single id for single-select), or null when not logged. `flow`,
 * `collection`, and `mind` are null outside menstruation; `pain` is null when none logged.
 *
 * The `sex` field is exchanged as **plaintext** option ids — encryption/decryption is a
 * server-only concern (see SHARED-MODULE.md); the DTO never carries ciphertext.
 */
@Serializable
data class DailyLogDto(
    val id: String,
    val logDate: String,
    val cycleId: String,
    val notes: String? = null,
    val emotions: List<String>? = null,
    val sleep: List<String>? = null,
    val energy: String? = null,
    val sex: List<String>? = null,
    val discharge: List<String>? = null,
    val skin: List<String>? = null,
    val digestion: List<String>? = null,
    val flow: String? = null,
    val collection: String? = null,
    val mind: List<String>? = null,
    val pain: PainDto? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** Pain log: every selected location with its own severity (1–10) or null when unrated. */
@Serializable
data class PainDto(
    val id: String,
    val locations: List<PainLocationDto>,
)

@Serializable
data class PainLocationDto(
    val locationId: String,
    val severity: Int? = null,
)

/** `POST /api/v1/daily-logs` request — creates the anchor row before any sub-log PUTs. */
@Serializable
data class CreateDailyLogRequest(
    val date: String,
    val cycleId: String,
)

/** `POST /api/v1/daily-logs` response — the bare anchor (no sub-logs yet). */
@Serializable
data class DailyLogAnchorDto(
    val id: String,
    val logDate: String,
    val cycleId: String,
    val createdAt: String,
    val updatedAt: String,
)

// ── Sub-log requests (shared across categories) ──────────────────────────────

/** Multi-select replace request (emotions, sleep, sex, discharge, skin, digestion, mind). */
@Serializable
data class OptionIdsRequest(
    val optionIds: List<String>,
)

/** Single-select replace request (energy, flow, collection); null clears the selection. */
@Serializable
data class OptionIdRequest(
    val optionId: String? = null,
)

/** `PATCH /api/v1/daily-logs/:date/notes` request; null clears the notes. */
@Serializable
data class NotesUpdateRequest(
    val notes: String? = null,
)

/** `PUT /api/v1/daily-logs/:date/pain` request; empty `locations` clears the pain log. */
@Serializable
data class PainUpdateRequest(
    val locations: List<PainLocationDto>,
)

// ── Sub-log responses (each echoes its own field + updatedAt) ─────────────────

@Serializable
data class NotesResponse(
    val notes: String? = null,
    val updatedAt: String,
)

@Serializable
data class EmotionsResponse(
    val emotions: List<String>,
    val updatedAt: String,
)

@Serializable
data class SleepResponse(
    val sleep: List<String>,
    val updatedAt: String,
)

@Serializable
data class SexResponse(
    val sex: List<String>,
    val updatedAt: String,
)

@Serializable
data class DischargeResponse(
    val discharge: List<String>,
    val updatedAt: String,
)

@Serializable
data class SkinResponse(
    val skin: List<String>,
    val updatedAt: String,
)

@Serializable
data class DigestionResponse(
    val digestion: List<String>,
    val updatedAt: String,
)

@Serializable
data class MindResponse(
    val mind: List<String>,
    val updatedAt: String,
)

@Serializable
data class EnergyResponse(
    val energy: String? = null,
    val updatedAt: String,
)

@Serializable
data class FlowResponse(
    val flow: String? = null,
    val updatedAt: String,
)

@Serializable
data class CollectionResponse(
    val collection: String? = null,
    val updatedAt: String,
)

@Serializable
data class PainResponse(
    val pain: PainDto? = null,
    val updatedAt: String,
)
