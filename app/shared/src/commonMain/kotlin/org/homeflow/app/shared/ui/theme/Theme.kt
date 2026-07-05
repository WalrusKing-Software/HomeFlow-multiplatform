package org.homeflow.app.shared.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.homeflow.app.shared.config.ThemeMode

/**
 * The single Material 3 theme wrapper for both clients. Resolves [themeMode] to a concrete
 * light/dark [androidx.compose.material3.ColorScheme] and applies it app-wide.
 *
 * [ThemeMode.SYSTEM] follows the OS setting via [isSystemInDarkTheme]; [ThemeMode.LIGHT],
 * [ThemeMode.DARK] (branded) and [ThemeMode.CLASSIC_DARK] (neutral) force the scheme. Applied
 * once at the composition root ([AppRoot]) so every screen — including the login/lock gate — is
 * themed consistently.
 */
@Composable
fun HomeFlowTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when (themeMode) {
            ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) HomeFlowDarkColors else HomeFlowLightColors
            ThemeMode.LIGHT -> HomeFlowLightColors
            ThemeMode.DARK -> HomeFlowDarkColors
            ThemeMode.CLASSIC_DARK -> HomeFlowClassicDarkColors
        }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
