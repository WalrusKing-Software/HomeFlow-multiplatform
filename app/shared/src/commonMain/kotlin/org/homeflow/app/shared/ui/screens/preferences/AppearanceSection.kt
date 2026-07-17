package org.homeflow.app.shared.ui.screens.preferences

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.homeflow.app.shared.config.ThemePreference
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.kmp.navigation.SegmentedTabBar
import org.homeflow.app.shared.ui.theme.LocalThemeController

/** Theme selector (System / Light / Dark). Shown on both platforms. */
@Composable
internal fun AppearanceSection() {
    val themeController = LocalThemeController.current
    SectionCard("Appearance") {
        Text(
            "Choose a light or dark theme, or follow your device setting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SegmentedTabBar(
            tabs = ThemePreference.entries,
            selectedIndex = themeController.preference.ordinal,
            onTabSelected = { themeController.set(ThemePreference.entries[it]) },
            label = { it.label },
        )
    }
}
