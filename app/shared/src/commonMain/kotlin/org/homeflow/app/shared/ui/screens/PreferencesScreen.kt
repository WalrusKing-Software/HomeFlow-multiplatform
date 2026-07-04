package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.CategoryLabel
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.PreferencesEditor
import org.homeflow.app.shared.data.userFacingMessage
import org.homeflow.app.shared.data.userMessage
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.kmp.feedback.ConfirmDialog
import org.homeflow.app.shared.ui.components.kmp.layout.ExpandableCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.dto.ImportResultDto

/** The text the user must type to confirm the irreversible account deletion. */
private const val DELETE_CONFIRMATION = "DELETE"

/**
 * Settings: reorder the dashboard tracking categories and persist them, a danger zone for
 * permanent account deletion, and mode-specific actions:
 * - Mode A: "Export my data" and "Connect to a server".
 * - Mode B: connected host, and (while not yet migrated) "Upload local data to server".
 */
@Composable
fun PreferencesScreen(
    repository: HomeFlowRepository,
    onDeleteAccount: suspend () -> ApiResult<Unit>,
    onExport: (suspend () -> Unit)? = null,
    onConnectServer: (() -> Unit)? = null,
    onUploadToServer: (suspend () -> ImportResultDto?)? = null,
    connectedHost: String? = null,
    onSwitchToLocal: (() -> Unit)? = null,
) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<PreferencesEditor>>(Loadable.Loading, repository, reloadKey) {
        value = repository.loadPreferences().toLoadable()
    }

    // One scrolling column so the danger zone is reachable regardless of the preferences
    // load state. The shared `Loadable` fills max size and can't live inside a scroll, so the
    // three states are rendered inline here with bounded heights.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val current = state) {
            is Loadable.Loading ->
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            is Loadable.Error ->
                SectionCard("Category order") {
                    Text(
                        userFacingMessage(current.code, current.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { reloadKey++ }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }

            is Loadable.Loaded -> ReorderForm(repository, current.value)
        }
        if (onExport != null) ExportSection(onExport)
        ServerSection(
            connectedHost = connectedHost,
            onConnectServer = onConnectServer,
            onUploadToServer = onUploadToServer,
            onSwitchToLocal = onSwitchToLocal,
        )
        DangerZone(onDeleteAccount)
    }
}

@Composable
private fun ReorderForm(
    repository: HomeFlowRepository,
    editor: PreferencesEditor,
) {
    var order by remember(editor) { mutableStateOf(editor.order) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard("Category order") {
        Text(
            "Arrange the order your tracking categories appear in when logging a day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        order.forEachIndexed { index, category ->
            ReorderRow(
                category = category,
                canMoveUp = index > 0,
                canMoveDown = index < order.lastIndex,
                onMoveUp = {
                    order = order.swapped(index, index - 1)
                    status = null
                },
                onMoveDown = {
                    order = order.swapped(index, index + 1)
                    status = null
                },
            )
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    status = null
                    when (val result = repository.savePreferences(order.map { it.slug })) {
                        is ApiResult.Success -> status = "Saved."
                        is ApiResult.Failure -> status = result.userMessage()
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            Text("Save order")
        }
    }
}

@Composable
private fun ReorderRow(
    category: CategoryLabel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
            OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
        }
    }
}

@Composable
private fun ExportSection(onExport: suspend () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard("Export data") {
        Text(
            "Save all your cycles and daily logs as a portable HomeFlow file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    status = null
                    runCatching { onExport() }
                        .onSuccess { status = "Export saved." }
                        .onFailure { status = it.message ?: "Export failed." }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            Text("Export my data")
        }
    }
}

/**
 * Server section: visible in both modes but with different content.
 * - Mode A ([onConnectServer] != null): "Connect to a server" button.
 * - Mode B ([connectedHost] != null): shows the host; if [onUploadToServer] != null,
 *   also shows "Upload local data to server" with a result summary; if [onSwitchToLocal]
 *   != null, offers a reversible "Switch to local-only mode".
 * - Neither set: nothing rendered.
 */
@Composable
private fun ServerSection(
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

/**
 * Permanent account deletion. Guarded by a type-to-confirm dialog; on success the
 * session controller clears local storage and drops the app back to the chooser/login
 * screen, so this composable simply leaves the tree. A failure is shown inline and the
 * session is left intact.
 */
@Composable
private fun DangerZone(onDeleteAccount: suspend () -> ApiResult<Unit>) {
    var showDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ExpandableCard(title = "Danger zone") {
        Text(
            "Permanently delete your account and all tracked data. This cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                error = null
                showDialog = true
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete account") }
    }

    if (showDialog) {
        DeleteAccountDialog(
            busy = busy,
            onDismiss = { if (!busy) showDialog = false },
            onConfirm = {
                scope.launch {
                    busy = true
                    error = null
                    when (val result = onDeleteAccount()) {
                        // Success transitions the auth gate to LoggedOut; this screen is removed.
                        is ApiResult.Success -> Unit
                        is ApiResult.Failure -> {
                            error = result.userMessage()
                            busy = false
                            showDialog = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DeleteAccountDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var confirmText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This permanently deletes your account and every cycle, daily log, and note. " +
                        "It cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Type $DELETE_CONFIRMATION to confirm.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && confirmText.trim() == DELETE_CONFIRMATION,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Delete")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

private fun <T> List<T>.swapped(
    a: Int,
    b: Int,
): List<T> =
    toMutableList().apply {
        val tmp = this[a]
        this[a] = this[b]
        this[b] = tmp
    }
