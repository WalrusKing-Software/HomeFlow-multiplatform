package org.homeflow.app.shared.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// HomeFlow brand palette — a warm rose primary with a muted teal accent. Defined once here
// and consumed by [HomeFlowTheme]; screens should read colors from MaterialTheme.colorScheme,
// never hard-code these values, so light/dark switching stays automatic.

private val Rose40 = Color(0xFFB4265A) // primary (light)
private val Rose80 = Color(0xFFFFB1C5) // primary (dark)
private val Rose90 = Color(0xFFFFD9E1) // primaryContainer (light)
private val Rose30 = Color(0xFF8E0E44) // primaryContainer (dark) / onPrimary text

private val Teal40 = Color(0xFF3A6A63) // secondary (light)
private val Teal80 = Color(0xFFA1D0C7) // secondary (dark)

private val Plum40 = Color(0xFF7A5B8E) // tertiary (light)
private val Plum80 = Color(0xFFDDB9F0) // tertiary (dark)

internal val HomeFlowLightColors =
    lightColorScheme(
        primary = Rose40,
        onPrimary = Color.White,
        primaryContainer = Rose90,
        onPrimaryContainer = Rose30,
        secondary = Teal40,
        onSecondary = Color.White,
        tertiary = Plum40,
        onTertiary = Color.White,
        background = Color(0xFFFFFBFF),
        onBackground = Color(0xFF201A1B),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF201A1B),
        surfaceVariant = Color(0xFFF3DDE1),
        onSurfaceVariant = Color(0xFF524346),
    )

internal val HomeFlowDarkColors =
    darkColorScheme(
        primary = Rose80,
        onPrimary = Rose30,
        primaryContainer = Color(0xFF72002E),
        onPrimaryContainer = Rose90,
        secondary = Teal80,
        onSecondary = Color(0xFF083733),
        tertiary = Plum80,
        onTertiary = Color(0xFF452A59),
        background = Color(0xFF201A1B),
        onBackground = Color(0xFFECE0E1),
        surface = Color(0xFF201A1B),
        onSurface = Color(0xFFECE0E1),
        surfaceVariant = Color(0xFF524346),
        onSurfaceVariant = Color(0xFFD6C2C5),
    )
