@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.FakeHomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.core.ErrorCode
import kotlin.test.Test

/**
 * Compose UI tests for [DashboardScreen]. Covers the Loading spinner, the Error state with
 * recovery, the loaded view with cycle stats and today's log, and the empty-state variants.
 *
 * Runs on the JVM target (headless Skiko) — see [UiTestSupport] and `__docs/TESTING.md`.
 */
class DashboardScreenTest {
    @Test
    fun loading_shows_spinner() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { neverComplete = true }
            setContent { DashboardScreen(HomeFlowRepository(fake)) }
            onLoadingSpinner().assertExists()
        }

    @Test
    fun error_shows_retry_and_recovers() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { failure = TEST_FAILURE }
            setContent { DashboardScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Couldn't load this").assertExists()

            fake.failure = null
            onNodeWithText("Try again").performClick()
            onNodeWithText("Current cycle").assertExists()
        }

    @Test
    fun loaded_renders_cycle_stats_and_today() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent { DashboardScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Current cycle").assertExists()
            onNodeWithText("Started").assertExists()
            onNodeWithText("2024-01-01").assertExists()
            onNodeWithText("28 days").assertExists()
            onNodeWithText("Fine").assertExists()
            onNodeWithText("Feeling fine.").assertExists()
        }

    @Test
    fun loaded_empty_states_render() =
        runComposeUiTest {
            val fake =
                FakeHomeFlowDataSource().apply {
                    currentCycle = ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "none", 404)
                    dailyLog = ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "none", 404)
                }
            setContent { DashboardScreen(HomeFlowRepository(fake)) }
            onNodeWithText("No open cycle. Start one to begin tracking.").assertExists()
            onNodeWithText("Nothing logged today yet.").assertExists()
        }
}
