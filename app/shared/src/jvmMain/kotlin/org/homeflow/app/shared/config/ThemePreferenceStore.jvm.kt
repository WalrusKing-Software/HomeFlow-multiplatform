package org.homeflow.app.shared.config

import java.io.File
import java.util.Properties

/**
 * Desktop [ThemePreferenceStore]: reads/writes `~/.homeflow/theme.properties`.
 * Plain text (not encrypted) — the appearance preference is not sensitive.
 */
class DesktopThemePreferenceStore(
    baseDir: File = File(System.getProperty("user.home"), ".homeflow"),
) : ThemePreferenceStore {
    private val file = File(baseDir, "theme.properties")

    override fun load(): ThemeMode {
        if (!file.exists()) return ThemeMode.SYSTEM
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }.onFailure { return ThemeMode.SYSTEM }
        val raw = props.getProperty(KEY) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    override fun save(mode: ThemeMode) {
        file.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty(KEY, mode.name)
        file.outputStream().use { props.store(it, null) }
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}

actual fun createThemePreferenceStore(): ThemePreferenceStore = DesktopThemePreferenceStore()
