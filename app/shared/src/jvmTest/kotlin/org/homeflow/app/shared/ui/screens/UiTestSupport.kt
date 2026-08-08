@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import org.homeflow.app.shared.config.ThemePreference
import org.homeflow.app.shared.config.ThemeStore
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.ui.theme.LocalThemeController
import org.homeflow.app.shared.ui.theme.ThemeController
import org.homeflow.core.ErrorCode

/** The failure every screen must surface as a retryable error state. */
internal val TEST_FAILURE = ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "boom", 500)

/** Matches the indeterminate CircularProgressIndicator rendered by Loading states. */
internal fun SemanticsNodeInteractionsProvider.onLoadingSpinner(): SemanticsNodeInteraction =
    onNode(
        SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate,
        ),
    )

private class FakeThemeStore : ThemeStore {
    private var preference = ThemePreference.SYSTEM

    override fun load(): ThemePreference = preference

    override fun save(preference: ThemePreference) {
        this.preference = preference
    }
}

/** Provides the [LocalThemeController] PreferencesScreen requires. */
@Composable
internal fun WithTestTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalThemeController provides ThemeController(FakeThemeStore())) {
        content()
    }
}
