package org.homeflow.app.shared.ui.screens.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.userMessage
import org.homeflow.app.shared.ui.components.kmp.layout.ExpandableCard

/** The text the user must type to confirm the irreversible account deletion. */
private const val DELETE_CONFIRMATION = "DELETE"

/**
 * Permanent account deletion. Guarded by a type-to-confirm dialog; on success the
 * session controller clears local storage and drops the app back to the chooser/login
 * screen, so this composable simply leaves the tree. A failure is shown inline and the
 * session is left intact.
 */
@Composable
internal fun DangerZone(onDeleteAccount: suspend () -> ApiResult<Unit>) {
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
