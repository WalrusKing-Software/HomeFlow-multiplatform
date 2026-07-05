package org.homeflow.app.shared.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.homeflow.app.shared.config.ThemeMode

/**
 * The single Material 3 theme wrapper for both clients. Resolves [themeMode] to a concrete
 * light/dark [androidx.compose.material3.ColorScheme] and applies it app-wide.
 *
 * [ThemeMode.SYSTEM] follows the OS setting via [isSystemInDarkTheme]; [ThemeMode.LIGHT] and
 * [ThemeMode.DARK] force the scheme. Applied once at the composition root ([AppRoot]) so every
 * screen — including the login/lock gate — is themed consistently.
 */
@Composable
fun HomeFlowTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    MaterialTheme(
        colorScheme = if (dark) HomeFlowDarkColors else HomeFlowLightColors,
        content = content,
    )
}
