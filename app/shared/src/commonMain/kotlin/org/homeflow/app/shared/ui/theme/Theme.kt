package org.homeflow.app.shared.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.homeflow.app.shared.config.ThemePreference
import org.homeflow.app.shared.config.isDark

// HomeFlow brand palette — the coral-to-crimson family of the bloom app icon. Only the
// brand slots are overridden; the remaining neutrals come from the Material 3 baseline
// light/dark schemes.

private val LightColors =
    lightColorScheme(
        primary = Color(0xFFB4123E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9DE),
        onPrimaryContainer = Color(0xFF40000C),
        secondary = Color(0xFF9F4149),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF8F4C34),
        onTertiary = Color(0xFFFFFFFF),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFFFB2BB),
        onPrimary = Color(0xFF67001B),
        primaryContainer = Color(0xFF8E1230),
        onPrimaryContainer = Color(0xFFFFD9DE),
        secondary = Color(0xFFE7BDBF),
        onSecondary = Color(0xFF44262A),
        tertiary = Color(0xFFE7BD9E),
        onTertiary = Color(0xFF48291A),
    )

// Classic dark — a plain, neutral Material 3 dark scheme with no brand overrides, for users
// who prefer a standard dark mode over the branded one.
private val ClassicDarkColors = darkColorScheme()

/**
 * The single MaterialTheme wrapper for the whole app. [preference] selects the color scheme;
 * [ThemePreference.SYSTEM] follows the OS via [isSystemInDarkTheme]. [ThemePreference.DARK] is
 * the branded dark palette and [ThemePreference.CLASSIC_DARK] the neutral one. Applied once in
 * `AppRoot`, above the auth gate, so login and lock screens are themed too.
 *
 * Wraps [content] in a full-size [Surface] painted with the theme's `background` color. A
 * `MaterialTheme` alone only supplies color *values* — it paints nothing. Without this Surface
 * the pre-shell screens (mode chooser, server connect, passphrase/lock) render their bare
 * `Column`/`Box` over the OS-default white window while their text picks up the themed (light,
 * in dark mode) `onBackground` color, leaving light text on white and near-invisible. The
 * signed-in shell already paints via `Scaffold`; this gives every other screen the same
 * themed background.
 */
@Composable
fun HomeFlowTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            !preference.isDark(isSystemInDarkTheme()) -> LightColors
            preference == ThemePreference.CLASSIC_DARK -> ClassicDarkColors
            else -> DarkColors
        }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}
