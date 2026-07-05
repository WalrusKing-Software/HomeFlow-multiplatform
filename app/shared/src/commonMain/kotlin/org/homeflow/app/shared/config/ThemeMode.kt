package org.homeflow.app.shared.config

/**
 * The user's chosen appearance for the app.
 *
 * - [SYSTEM] follows the OS light/dark setting (the default on first run).
 * - [LIGHT] / [DARK] force that scheme regardless of the OS.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Persists the user's chosen [ThemeMode] in a **non-encrypted** store — the appearance
 * preference is not sensitive and must be readable before the app-lock gate is satisfied
 * (the login/lock screens are themed too).
 *
 * [load] returns [ThemeMode.SYSTEM] on first run (unset). Mirrors [AppModeStore].
 * Android: plain SharedPreferences. Desktop: `~/.homeflow/theme.properties`.
 */
interface ThemePreferenceStore {
    fun load(): ThemeMode

    fun save(mode: ThemeMode)
}

expect fun createThemePreferenceStore(): ThemePreferenceStore
