package org.homeflow.app.shared.ui.screens.preferences

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.homeflow.app.shared.data.sync.SyncStatus
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.kmp.feedback.ConfirmDialog
import org.homeflow.core.dto.ImportResultDto

/**
 * Server section: visible in both modes but with different content.
 * - Mode A ([onConnectServer] != null): "Connect to a server" button.
 * - Mode B ([connectedHost] != null): shows the host; if [onUploadToServer] != null,
 *   also shows "Upload local data to server" with a result summary; if [onSwitchToLocal]
 *   != null, offers a reversible "Switch to local-only mode".
 * - Mode C ([onSyncNow] != null): a "Sync now" button plus the current [syncStatus].
 * - Neither set: nothing rendered.
 */
@Composable
internal fun ServerSection(
    connectedHost: String?,
    onConnectServer: (() -> Unit)?,
    onUploadToServer: (suspend () -> ImportResultDto?)?,
    onSwitchToLocal: (() -> Unit)?,
    onSyncNow: (suspend () -> Unit)? = null,
    syncStatus: SyncStatus? = null,
) {
    if (connectedHost == null && onConnectServer == null) return

    SectionCard("Server") {
        if (connectedHost != null) {
            Text(
                "Connected to $connectedHost",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (onSyncNow != null) {
            SyncNowButton(onSyncNow, syncStatus)
        }

        if (onConnectServer != null) {
            Text(
                "Link this device to your self-hosted server to back up your data and access it across devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onConnectServer,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect to a server") }
        }

        if (onUploadToServer != null) {
            UploadToServerButton(onUploadToServer)
        }

        if (onSwitchToLocal != null) {
            SwitchToLocalButton(onSwitchToLocal)
        }
    }
}

/**
 * Reversible mode switch (Mode B → Mode A). The local database keeps the synced data, so
 * switching just stops syncing; the server connection is retained so the user can reconnect
 * later from Settings. Guarded by a confirm dialog.
 */
@Composable
private fun SwitchToLocalButton(onSwitchToLocal: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Text(
        "Use this device without a server. Your data stays on this device and stops syncing " +
            "until you reconnect.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Switch to local-only mode") }

    ConfirmDialog(
        visible = showDialog,
        title = "Switch to local-only mode?",
        body =
            "Your data stays on this device and will stop syncing with the server. " +
                "You can reconnect later from Settings.",
        confirmLabel = "Switch",
        onConfirm = {
            showDialog = false
            onSwitchToLocal()
        },
        onDismiss = { showDialog = false },
    )
}

/**
 * Manual "Sync now" (Mode C). Kicks off a sync through the trigger and reflects progress: the
 * button shows a spinner while running, and a line below reports the live [syncStatus]. The
 * button is disabled while a sync is in flight (either this one or a background cycle) so a
 * user can't stack requests.
 */
@Composable
private fun SyncNowButton(
    onSyncNow: suspend () -> Unit,
    syncStatus: SyncStatus?,
) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val syncing = busy || syncStatus is SyncStatus.Syncing

    Text(
        "Push your latest changes to the server and pull in anything from your other devices.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = {
            scope.launch {
                busy = true
                runCatching { onSyncNow() }
                busy = false
            }
        },
        enabled = !syncing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (syncing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
        Text("Sync now")
    }

    syncStatusLine(syncStatus)?.let { (line, isError) ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Maps a [SyncStatus] to a short status line and whether it is an error. `null` when there is
 * nothing worth showing (no sync has run yet). Returns the last-synced time in UTC, formatted
 * with pure string ops to stay free of platform date APIs.
 */
private fun syncStatusLine(status: SyncStatus?): Pair<String, Boolean>? =
    when (status) {
        is SyncStatus.Syncing -> "Syncing…" to false
        is SyncStatus.Success -> "Last synced ${formatSyncedAt(status.lastSyncAt)}" to false
        is SyncStatus.Error -> status.message to true
        else -> null
    }

/** "2026-08-07T14:33:12.918Z" → "2026-08-07 14:33 UTC"; falls back to the raw string. */
private fun formatSyncedAt(iso: String): String {
    val trimmed = iso.substringBefore('.').substringBefore('Z').replace('T', ' ')
    return if (trimmed.length >= 16) "${trimmed.substring(0, 16)} UTC" else iso
}

@Composable
private fun UploadToServerButton(onUploadToServer: suspend () -> ImportResultDto?) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportResultDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Text(
        "Upload your locally stored data to the server.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    result?.let { r ->
        Text(
            "Uploaded: ${r.cyclesCreated} cycle(s), ${r.dailyLogsCreated} day(s) " +
                "(${r.dailyLogsSkipped} skipped).",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    error?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    Button(
        onClick = {
            scope.launch {
                busy = true
                result = null
                error = null
                val r = runCatching { onUploadToServer() }.getOrNull()
                if (r != null) result = r else error = "Upload failed. Try again."
                busy = false
            }
        },
        enabled = !busy && result == null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
        Text(if (result != null) "Uploaded" else "Upload local data to server")
    }
}
