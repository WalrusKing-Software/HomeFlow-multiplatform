package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/**
 * Sync wire DTOs — the `GET/POST /api/v1/sync/changes` protocol (see `__docs/API.md`).
 *
 * Key differences from the export/import shape (Phase 12):
 * - Each aggregate carries its UUID `id` and `cycleId` (D1) so identity is preserved
 *   across stores — the client does NOT need to re-mint ids on apply.
 * - Day→cycle links are by `cycleId` UUID, not by `cycleStartDate` (unlike export).
 * - Every aggregate carries `updatedAt` (the LWW comparator) and a `deleted` flag
 *   (tombstones propagate deletes; D3).
 * - Selections are slug-keyed (D4); the server decrypts notes/sex before sending
 *   and encrypts on receive — the wire is plaintext over TLS.
 */
@Serializable
data class SyncCycle(
    val id: String,
    val startDate: String,
    val endDate: String? = null,
    val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class SyncDay(
    val id: String,
    val date: String,
    val cycleId: String,
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
    val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class SyncPreferences(
    val categoryOrder: List<String>,
    val updatedAt: String,
)

@Serializable
data class SyncPushRequest(
    val cycles: List<SyncCycle> = emptyList(),
    val days: List<SyncDay> = emptyList(),
    val preferences: SyncPreferences? = null,
)

@Serializable
data class SyncPullResponse(
    val cycles: List<SyncCycle> = emptyList(),
    val days: List<SyncDay> = emptyList(),
    val preferences: SyncPreferences? = null,
    val cursor: Long,
    /**
     * True when the server capped this page and more changes exist after [cursor].
     * Additive (defaults false): an older client ignores it, persists the page
     * cursor, and picks up the remainder on its next scheduled sync.
     */
    val hasMore: Boolean = false,
)

@Serializable
data class SyncPushResponse(
    val cycles: List<SyncCycle> = emptyList(),
    val days: List<SyncDay> = emptyList(),
    val preferences: SyncPreferences? = null,
    val cursor: Long,
)
