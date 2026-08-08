package org.homeflow.app.shared.config

import java.io.File
import java.util.Properties

/**
 * Desktop [AppModeStore]: reads/writes `~/.homeflow/mode.properties`.
 * Plain text (not encrypted) — [AppMode] is not sensitive.
 */
class DesktopAppModeStore(
    baseDir: File = desktopDataDir,
) : AppModeStore {
    private val file = File(baseDir, "mode.properties")

    override fun load(): AppMode? {
        if (!file.exists()) return null
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }.onFailure { return null }
        val raw = props.getProperty(KEY) ?: return null
        return runCatching { AppMode.valueOf(raw) }.getOrNull()
    }

    override fun save(mode: AppMode) {
        file.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty(KEY, mode.name)
        file.outputStream().use { props.store(it, null) }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val KEY = "app_mode"
    }
}

actual fun createAppModeStore(): AppModeStore = DesktopAppModeStore()
