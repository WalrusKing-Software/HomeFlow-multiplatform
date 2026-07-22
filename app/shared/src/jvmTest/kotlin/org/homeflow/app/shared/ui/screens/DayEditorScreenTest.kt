@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.FakeHomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.core.dto.CyclesResponse
import kotlin.test.Test

/**
 * Compose UI tests for [DayEditorScreen]. A fixed [date] of 2024-01-06 is used — it falls
 * inside the fake's open cycle (2024-01-01 → open), so [DayEditor.canLog] is true without
 * any wall-clock dependency. Covers Loading, Error, the loaded editing form, and the
 * no-covering-cycle hint.
 *
 * Runs on the JVM target (headless Skiko) — see [UiTestSupport] and `__docs/TESTING.md`.
 */
class DayEditorScreenTest {
    private val date = LocalDate(2024, 1, 6)

    @Test
    fun loading_shows_spinner() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { neverComplete = true }
            setContent {
                DayEditorScreen(
                    repository = HomeFlowRepository(fake),
                    date = date,
                    onSaved = {},
                    onCancel = {},
                )
            }
            onLoadingSpinner().assertExists()
        }

    @Test
    fun error_shows_retry() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { failure = TEST_FAILURE }
            setContent {
                DayEditorScreen(
                    repository = HomeFlowRepository(fake),
                    date = date,
                    onSaved = {},
                    onCancel = {},
                )
            }
            onNodeWithText("Couldn't load this").assertExists()
            onNodeWithText("Try again").assertExists()
        }

    @Test
    fun loaded_renders_category_editors() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent {
                DayEditorScreen(
                    repository = HomeFlowRepository(fake),
                    date = date,
                    onSaved = {},
                    onCancel = {},
                )
            }
            onNodeWithText("Emotions").assertExists()
            onNodeWithText("During menstruation").assertExists()
            onNodeWithText("Blood Flow").assertExists()
            onNodeWithText("Fine").assertExists()
            onNodeWithText("Anxious").assertExists()
        }

    @Test
    fun no_covering_cycle_shows_hint() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { cycles = CyclesResponse(emptyList()) }
            setContent {
                DayEditorScreen(
                    repository = HomeFlowRepository(fake),
                    date = date,
                    onSaved = {},
                    onCancel = {},
                )
            }
            onNodeWithText("No cycle covers this date. Start a cycle on the Cycles tab to log here.").assertExists()
            onNodeWithText("Back").assertExists()
        }
}
