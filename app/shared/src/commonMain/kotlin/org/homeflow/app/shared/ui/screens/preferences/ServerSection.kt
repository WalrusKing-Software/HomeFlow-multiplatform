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
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.kmp.feedback.ConfirmDialog
import org.homeflow.core.dto.ImportResultDto

/**
 * Server section: visible in both modes but with different content.
 * - Mode A ([onConnectServer] != null): "Connect to a server" button.
 * - Mode B ([connectedHost] != null): shows the host; if [onUploadToServer] != null,
 *   also shows "Upload local data to server" with a result summary; if [onSwitchToLocal]
 *   != null, offers a reversible "Switch to local-only mode".
 * - Neither set: nothing rendered.
 */
@Composable
internal fun ServerSection(
    connectedHost: String?,
    onConnectServer: (() -> Unit)?,
    onUploadToServer: (suspend () -> ImportResultDto?)?,
    onSwitchToLocal: (() -> Unit)?,
) {
    if (connectedHost == null && onConnectServer == null) return

    SectionCard("Server") {
        if (connectedHost != null) {
            Text(
                "Connected to $connectedHost",
                style = MaterialTheme.typography.bodyMedium,
            )
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
