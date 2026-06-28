package org.homeflow.app.shared.platform

/**
 * Opens a platform save-file dialog, writes [json] as UTF-8 to the chosen path, and
 * returns true on success. Returns false if the user cancels without choosing a file.
 *
 * Desktop: AWT/Swing [java.awt.FileDialog] save dialog.
 * Android: Storage Access Framework [android.content.Intent.ACTION_CREATE_DOCUMENT].
 */
expect suspend fun writeExportFile(
    suggestedName: String,
    json: String,
): Boolean
