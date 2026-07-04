package org.homeflow.app.shared.ui.components.kmp.feedback

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// ---------------------------------------------------------------------------
// ConfirmDialog
//
// A two-button Material 3 AlertDialog for confirming destructive or
// irreversible actions. The confirm button can be styled as destructive
// (uses error color) for delete / discard flows.
//
// Fully hoisted — the caller controls visibility via `visible`.
//
// Usage — destructive action:
//   ConfirmDialog(
//       visible = showDeleteDialog,
//       title = "Delete record?",
//       body = "This cannot be undone. The record will be permanently removed.",
//       confirmLabel = "Delete",
//       onConfirm = { viewModel.deleteRecord(); showDeleteDialog = false },
//       onDismiss = { showDeleteDialog = false },
//       destructive = true,
//   )
//
// Usage — standard confirmation:
//   ConfirmDialog(
//       visible = showSubmitDialog,
//       title = "Submit report?",
//       body = "Once submitted the report will be sent to your supervisor.",
//       confirmLabel = "Submit",
//       onConfirm = { viewModel.submit(); showSubmitDialog = false },
//       onDismiss = { showSubmitDialog = false },
//   )
// ---------------------------------------------------------------------------

@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    if (!visible) return

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = if (icon != null) ({
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary,
            )
        }) else null,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ) else ButtonDefaults.textButtonColors(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}
