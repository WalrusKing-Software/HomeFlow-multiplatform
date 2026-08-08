package org.homeflow.app.shared.ui.screens.preferences

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

@Composable
internal fun ExportSection(onExport: suspend () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showExportWarning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionCard("Export data") {
        Text(
            "Save all your cycles and daily logs as a portable HomeFlow file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { showExportWarning = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            Text("Export my data")
        }
    }

    // SEC-13: the export is deliberately plaintext (portable backup) — the user must
    // knowingly accept that before health data is written to an unencrypted file.
    ConfirmDialog(
        visible = showExportWarning,
        title = "Export unencrypted data?",
        body =
            "The export is a plain, unencrypted JSON file containing all of your health data. " +
                "Anyone with access to the file can read it. Save it only to a location you " +
                "trust, and delete it when you no longer need it.",
        confirmLabel = "Export",
        onConfirm = {
            showExportWarning = false
            scope.launch {
                busy = true
                status = null
                runCatching { onExport() }
                    .onSuccess { status = "Export saved." }
                    .onFailure { status = it.message ?: "Export failed." }
                busy = false
            }
        },
        onDismiss = { showExportWarning = false },
    )
}
