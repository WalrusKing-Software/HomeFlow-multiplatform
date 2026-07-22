@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.homeflow.app.shared.data.FakeHomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.core.dto.CyclesResponse
import kotlin.test.Test

/**
 * Compose UI tests for [CyclesScreen]. The [StartCycleCard] is always rendered above the
 * [Loadable], so "Start a new cycle" coexists with both the loading spinner and the error
 * card. Covers Loading, Error, the loaded cycle list, and the empty-list hint.
 *
 * Runs on the JVM target (headless Skiko) — see [UiTestSupport] and `__docs/TESTING.md`.
 */
class CyclesScreenTest {
    @Test
    fun loading_shows_spinner_and_start_card() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { neverComplete = true }
            setContent { CyclesScreen(HomeFlowRepository(fake)) }
            onLoadingSpinner().assertExists()
            onNodeWithText("Start a new cycle").assertExists()
        }

    @Test
    fun error_shows_retry_and_start_card() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { failure = TEST_FAILURE }
            setContent { CyclesScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Couldn't load this").assertExists()
            onNodeWithText("Try again").assertExists()
            onNodeWithText("Start a new cycle").assertExists()
        }

    @Test
    fun loaded_lists_cycles() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent { CyclesScreen(HomeFlowRepository(fake)) }
            onNodeWithText("2024-01-01 → open").assertExists()
            onNodeWithText("Status").assertExists()
            onNodeWithText("Open").assertExists()
            onNodeWithText("Close cycle").assertExists()
            onNodeWithText("Delete cycle").assertExists()
        }

    @Test
    fun empty_list_shows_hint() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { cycles = CyclesResponse(emptyList()) }
            setContent { CyclesScreen(HomeFlowRepository(fake)) }
            onNodeWithText("No cycles yet.").assertExists()
        }
}
