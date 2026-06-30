package org.homeflow.core.service

/**
 * Last-Write-Wins conflict resolution for the sync engine (Phase 16).
 *
 * Both the server push handler (`SyncService.push`) and the client reconciler
 * (`SyncEngine`) use the identical function so tie-breaking is deterministic and
 * symmetric — neither side needs to know "who is server."
 *
 * Rule: later `updatedAt` wins (ISO-8601 UTC instant strings sort lexicographically
 * in chronological order). On a tie, the lexicographically greater `id` wins —
 * deterministic and requires no coordination.
 */
enum class MergeWinner { LOCAL, REMOTE }

/**
 * Decides which version of an aggregate wins in a two-way sync conflict.
 *
 * @param localUpdatedAt  ISO-8601 instant string of the local (receiver's) version.
 * @param localId         UUID string of the local version (for tie-breaking).
 * @param remoteUpdatedAt ISO-8601 instant string of the incoming (sender's) version.
 * @param remoteId        UUID string of the incoming version.
 * @return [MergeWinner.REMOTE] if the incoming version should be applied; [MergeWinner.LOCAL] otherwise.
 */
fun mergeDecision(
    localUpdatedAt: String,
    localId: String,
    remoteUpdatedAt: String,
    remoteId: String,
): MergeWinner =
    when {
        remoteUpdatedAt > localUpdatedAt -> MergeWinner.REMOTE
        remoteUpdatedAt < localUpdatedAt -> MergeWinner.LOCAL
        // Tie: greater id wins (lexicographic on UUID strings — deterministic and symmetric).
        remoteId > localId -> MergeWinner.REMOTE
        else -> MergeWinner.LOCAL
    }
