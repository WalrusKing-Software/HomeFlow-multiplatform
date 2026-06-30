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
    }
}

actual fun createServerConfigStore(): ServerConfigStore = DesktopServerConfigStore()
