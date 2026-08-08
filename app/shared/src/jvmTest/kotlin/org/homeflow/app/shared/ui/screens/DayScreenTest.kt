@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.FakeHomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.core.ErrorCode
import kotlin.test.Test

/**
 * Compose UI tests for [DayScreen]. Covers Loading, Error with "Try again", a loaded day
 * with its edit/delete affordances, and the empty-day logging entry point.
 *
 * Runs on the JVM target (headless Skiko) — see [UiTestSupport] and `__docs/TESTING.md`.
 */
class DayScreenTest {
    @Test
    fun loading_shows_spinner() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { neverComplete = true }
            setContent { DayScreen(HomeFlowRepository(fake)) }
            onLoadingSpinner().assertExists()
        }

    @Test
    fun error_shows_retry() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { failure = TEST_FAILURE }
            setContent { DayScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Couldn't load this").assertExists()
            onNodeWithText("Try again").assertExists()
        }

    @Test
    fun loaded_day_shows_details_and_actions() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent { DayScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Edit this day").assertExists()
            onNodeWithText("Delete this day").assertExists()
            onNodeWithText("Fine").assertExists()
            onNodeWithText("Feeling fine.").assertExists()
        }

    @Test
    fun empty_day_offers_logging() =
        runComposeUiTest {
            val fake =
                FakeHomeFlowDataSource().apply {
                    dailyLog = ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "none", 404)
                }
            setContent { DayScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Nothing logged on this day.").assertExists()
            onNodeWithText("Log this day").assertExists()
        }
}
