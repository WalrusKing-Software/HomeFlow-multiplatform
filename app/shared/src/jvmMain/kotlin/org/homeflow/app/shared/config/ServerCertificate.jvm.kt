package org.homeflow.app.shared.config

import org.homeflow.app.shared.data.DesktopTls
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.security.cert.CertificateFactory

private val homeflowDir = File(System.getProperty("user.home"), ".homeflow")

/** The user's chosen CA is copied here so trust survives the original being moved/deleted. */
private val storedCaFile = File(homeflowDir, "server-ca.pem")

actual val supportsCustomServerCertificate: Boolean = true

actual fun customServerCertificateName(): String? {
    val store = DesktopServerConfigStore()
    // Only report a name if the copied CA is actually present.
    return if (store.loadCaCertPath()?.let { File(it).isFile } == true) store.loadCaCertName() else null
}

actual suspend fun chooseCustomServerCertificate(): ServerCertificateResult {
    // Shown on the caller's UI dispatcher (Swing EDT), matching writeExportFile.
    val dialog =
        FileDialog(null as Frame?, "Select your server's certificate (.crt / .pem)", FileDialog.LOAD)
            .apply { isVisible = true }
    val dir = dialog.directory ?: return ServerCertificateResult.CANCELLED
    val name = dialog.file ?: return ServerCertificateResult.CANCELLED
    val picked = File(dir, name).takeIf { it.isFile } ?: return ServerCertificateResult.CANCELLED

    // Reject anything that isn't a parseable X.509 certificate before trusting it.
    val valid =
        runCatching {
            picked.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input).isNotEmpty()
            }
        }.getOrDefault(false)
    if (!valid) return ServerCertificateResult.INVALID

    homeflowDir.mkdirs()
    picked.copyTo(storedCaFile, overwrite = true)
    DesktopServerConfigStore().saveCaCert(storedCaFile.absolutePath, name)
    DesktopTls.invalidate()
    return ServerCertificateResult.SELECTED
}

actual fun clearCustomServerCertificate() {
    DesktopServerConfigStore().clearCaCert()
    runCatching { storedCaFile.delete() }
    DesktopTls.invalidate()
}
