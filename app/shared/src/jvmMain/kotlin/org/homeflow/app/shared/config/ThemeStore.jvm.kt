package org.homeflow.app.shared.config

import java.io.File
import java.util.Properties

/**
 * Desktop [ThemeStore]: reads/writes `~/.homeflow/theme.properties`. Plain text (not
 * encrypted) — the theme choice is not sensitive.
 */
class DesktopThemeStore(
    baseDir: File = File(System.getProperty("user.home"), ".homeflow"),
) : ThemeStore {
    private val file = File(baseDir, "theme.properties")

    override fun load(): ThemePreference {
        if (!file.exists()) return ThemePreference.SYSTEM
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }.onFailure { return ThemePreference.SYSTEM }
        val raw = props.getProperty(KEY) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(raw) }.getOrDefault(ThemePreference.SYSTEM)
    }

    override fun save(preference: ThemePreference) {
        file.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty(KEY, preference.name)
        file.outputStream().use { props.store(it, null) }
    }

    private companion object {
        const val KEY = "theme"
    }
}

actual fun createThemeStore(): ThemeStore = DesktopThemeStore()
