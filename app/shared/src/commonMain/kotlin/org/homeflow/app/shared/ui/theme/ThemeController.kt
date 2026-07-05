package org.homeflow.app.shared.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.homeflow.app.shared.config.ThemePreference
import org.homeflow.app.shared.config.ThemeStore

/**
 * Holds the current [ThemePreference] as Compose state and persists every change through
 * [ThemeStore]. Created once in `AppRoot` and provided via [LocalThemeController] so screens
 * can read/change the theme without threading a callback through the whole composable tree.
 */
class ThemeController(
    private val store: ThemeStore,
) {
    var preference by mutableStateOf(store.load())
        private set

    fun set(preference: ThemePreference) {
        this.preference = preference
        store.save(preference)
    }
}

/** Provides the app's [ThemeController]; must be supplied by `AppRoot`. */
val LocalThemeController =
    staticCompositionLocalOf<ThemeController> {
        error("LocalThemeController not provided")
    }
