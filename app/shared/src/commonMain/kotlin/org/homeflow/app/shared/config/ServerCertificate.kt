package org.homeflow.app.shared.config

/** Outcome of prompting the user to select a server CA certificate. */
enum class ServerCertificateResult {
    /** A valid certificate was chosen, persisted, and added to the app's TLS trust. */
    SELECTED,

    /** The user dismissed the picker without choosing a file. */
    CANCELLED,

    /** A file was chosen but it is not a readable X.509 certificate. */
    INVALID,
}

/**
 * Whether this platform lets the user trust a self-hosted server's private CA certificate
 * from within the app.
 *
 * True on desktop: the JVM trusts only its bundled roots, so a Caddy internal-CA /
 * self-signed LAN server (e.g. `https://homeflow.lan`) is unreachable until its CA is added
 * explicitly. False on Android, which trusts CAs installed in the OS trust store.
 */
expect val supportsCustomServerCertificate: Boolean

/** Display name of the currently trusted custom CA certificate, or null if none is set. */
expect fun customServerCertificateName(): String?

/**
 * Prompt the user (native file picker) to choose a PEM/CRT CA certificate, validate it,
 * persist it, and add it to the app's TLS trust for subsequent connections.
 */
expect suspend fun chooseCustomServerCertificate(): ServerCertificateResult

/** Remove any trusted custom CA certificate. */
expect fun clearCustomServerCertificate()
