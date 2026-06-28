package org.homeflow.app.shared.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual suspend fun writeExportFile(
    suggestedName: String,
    json: String,
): Boolean {
    val dialog =
        FileDialog(null as Frame?, "Save HomeFlow export", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
    val dir = dialog.directory ?: return false
    val filename = dialog.file ?: return false
    File(dir, filename).writeText(json, Charsets.UTF_8)
    return true
}
