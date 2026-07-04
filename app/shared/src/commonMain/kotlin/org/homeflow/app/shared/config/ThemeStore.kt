package org.homeflow.app.shared.config

/**
 * Persists the user's [ThemePreference] in a **non-encrypted** store so it can be read
 * before the app-lock gate is satisfied (and before the DEK is available) — the theme
 * must apply to the login and lock screens too. Mirrors [AppModeStore].
 *
 * [load] returns [ThemePreference.SYSTEM] when unset (first run) or unreadable.
 * Android: plain SharedPreferences. Desktop: `~/.homeflow/theme.properties`.
 */
interface ThemeStore {
    fun load(): ThemePreference

    fun save(preference: ThemePreference)
}

expect fun createThemeStore(): ThemeStore
