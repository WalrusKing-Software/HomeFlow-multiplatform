@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.FakeHomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import kotlin.test.Test

/**
 * Compose UI tests for [PreferencesScreen]. Every test wraps the screen in [WithTestTheme]
 * because PreferencesScreen reads [org.homeflow.app.shared.ui.theme.LocalThemeController],
 * which errors when unprovided. Only the core chrome (Appearance, Danger zone, Category order)
 * is asserted — see plan: "keep these narrow".
 *
 * Runs on the JVM target (headless Skiko) — see [UiTestSupport] and `__docs/TESTING.md`.
 */
class PreferencesScreenTest {
    @Test
    fun loading_shows_inline_spinner() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { neverComplete = true }
            setContent {
                WithTestTheme {
                    PreferencesScreen(
                        repository = HomeFlowRepository(fake),
                        onDeleteAccount = { ApiResult.Success(Unit) },
                    )
                }
            }
            onLoadingSpinner().assertExists()
            onNodeWithText("Appearance").assertExists()
            onNodeWithText("Danger zone").assertExists()
        }

    @Test
    fun error_shows_inline_retry_and_recovers() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { failure = TEST_FAILURE }
            setContent {
                WithTestTheme {
                    PreferencesScreen(
                        repository = HomeFlowRepository(fake),
                        onDeleteAccount = { ApiResult.Success(Unit) },
                    )
                }
            }
            onNodeWithText("Category order").assertExists()
            onNodeWithText("Try again").assertExists()

            fake.failure = null
            onNodeWithText("Try again").performClick()
            onNodeWithText("Emotions").assertExists()
        }

    @Test
    fun loaded_renders_category_order() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent {
                WithTestTheme {
                    PreferencesScreen(
                        repository = HomeFlowRepository(fake),
                        onDeleteAccount = { ApiResult.Success(Unit) },
                    )
                }
            }
            onNodeWithText("Category order").assertExists()
            onNodeWithText("Emotions").assertExists()
            onNodeWithText("Blood Flow").assertExists()
            onNodeWithText("Save order").assertExists()
        }

    @Test
    fun danger_zone_present() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent {
                WithTestTheme {
                    PreferencesScreen(
                        repository = HomeFlowRepository(fake),
                        onDeleteAccount = { ApiResult.Success(Unit) },
                    )
                }
            }
            onNodeWithText("Danger zone").assertExists()
        }
}
