package org.homeflow.app.shared.data.sync

/** The observable state of the background sync process (Phase 16b). */
sealed interface SyncStatus {
    /** No sync in progress. This is the initial and post-success state. */
    data object Idle : SyncStatus

    /** A sync cycle is running (push + pull in progress). */
    data object Syncing : SyncStatus

    /** Last sync completed successfully. [lastSyncAt] is the ISO instant string. */
    data class Success(val lastSyncAt: String) : SyncStatus

    /** Last sync failed. [message] is a user-visible or log-safe description. */
    data class Error(val message: String) : SyncStatus
}
