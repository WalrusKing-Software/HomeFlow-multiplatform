package org.homeflow.app.shared.config

/**
 * Android trusts CAs installed in the OS trust store (Settings → Security → Install a
 * certificate), so there is no in-app certificate picker. See `SETUP-ANDROID.md`.
 */
actual val supportsCustomServerCertificate: Boolean = false

actual fun customServerCertificateName(): String? = null

actual suspend fun chooseCustomServerCertificate(): ServerCertificateResult =
    ServerCertificateResult.CANCELLED

actual fun clearCustomServerCertificate() = Unit
