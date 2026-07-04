package org.homeflow.app.shared.config

import java.io.File
import java.util.Properties

/**
 * Desktop [ServerConfigStore]: reads/writes `~/.homeflow/server.properties`.
 * Not encrypted — the host and migrated flag are not sensitive.
 */
class DesktopServerConfigStore(
    baseDir: File = File(System.getProperty("user.home"), ".homeflow"),
) : ServerConfigStore {
    private val file = File(baseDir, "server.properties")

    override fun loadHost(): String? = load().getProperty(KEY_HOST)

    override fun saveHost(host: String) {
        val props = load()
        props.setProperty(KEY_HOST, host)
        store(props)
    }

    /**
     * Path to the CA certificate the user selected to trust a self-hosted server with a
     * private/self-signed cert (desktop-only; see [org.homeflow.app.shared.data.DesktopTls]).
     * Not part of the [ServerConfigStore] interface — Android trusts CAs via the OS store.
     */
    fun loadCaCertPath(): String? = load().getProperty(KEY_CA_CERT_PATH)?.takeIf { it.isNotBlank() }

    /** The original file name of the trusted CA certificate, for display. */
    fun loadCaCertName(): String? = load().getProperty(KEY_CA_CERT_NAME)?.takeIf { it.isNotBlank() }

    /** Persists the trusted CA certificate location and its display name. */
    fun saveCaCert(
        path: String,
        displayName: String,
    ) {
        val props = load()
        props.setProperty(KEY_CA_CERT_PATH, path)
        props.setProperty(KEY_CA_CERT_NAME, displayName)
        store(props)
    }

    /** Forgets the trusted CA certificate (keeps host/migrated). */
    fun clearCaCert() {
        val props = load()
        props.remove(KEY_CA_CERT_PATH)
        props.remove(KEY_CA_CERT_NAME)
        store(props)
    }

    override fun isMigrated(): Boolean = load().getProperty(KEY_MIGRATED) == "true"

    override fun setMigrated() {
        val props = load()
        props.setProperty(KEY_MIGRATED, "true")
        store(props)
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private fun load(): Properties {
        val props = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        return props
    }

    private fun store(props: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    private companion object {
        const val KEY_HOST = "server_host"
        const val KEY_MIGRATED = "migrated"
        const val KEY_CA_CERT_PATH = "ca_cert_path"
        const val KEY_CA_CERT_NAME = "ca_cert_name"
    }
}

actual fun createServerConfigStore(): ServerConfigStore = DesktopServerConfigStore()
