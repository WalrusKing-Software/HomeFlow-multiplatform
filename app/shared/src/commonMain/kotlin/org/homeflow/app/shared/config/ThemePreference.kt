package org.homeflow.app.shared.config

/**
 * The user's chosen app theme. [SYSTEM] follows the OS light/dark setting; the others force a
 * mode regardless of the OS. [DARK] is the branded (coral/crimson) dark theme; [CLASSIC_DARK]
 * is a plain, neutral Material dark theme. Persisted via [ThemeStore]; the default (unset) is
 * [SYSTEM].
 */
enum class ThemePreference(
    val label: String,
) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    CLASSIC_DARK("Classic Dark"),
}

/**
 * Resolve whether dark colors should apply, given whether the OS is currently in dark mode.
 * Pure so it can be unit-tested without Compose. (Which dark palette — branded vs classic — is
 * decided separately in the theme; see `HomeFlowTheme`.)
 */
fun ThemePreference.isDark(systemDark: Boolean): Boolean =
    when (this) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.CLASSIC_DARK -> true
    }
