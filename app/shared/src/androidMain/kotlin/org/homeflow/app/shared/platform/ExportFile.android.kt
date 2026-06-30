package org.homeflow.app.shared.platform

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun writeExportFile(
    suggestedName: String,
    json: String,
): Boolean {
    val launcher =
        AndroidAppContext.exportLauncher
            ?: error("Export launcher not registered. Call AndroidAppContext.registerExportLauncher in MainActivity.")

    val intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    val uri: Uri = launcher(intent) ?: return false

    withContext(Dispatchers.IO) {
        AndroidAppContext.application.contentResolver
            .openOutputStream(uri)
            ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }
    return true
}
