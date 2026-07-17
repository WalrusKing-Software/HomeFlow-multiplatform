@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.homeflow.app.shared.ui.screens

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.homeflow.app.shared.data.FakeHomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.PeriodLengthChartDto
import kotlin.test.Test

/**
 * Compose UI tests for [AnalyticsScreen]. Covers Loading, Error, all four section headers
 * in the default loaded state (including empty-state hints for null ovulation/sleep fields),
 * and the thin-stats variant with no closed-cycle data.
 *
 * Verifies that empty states render (not crash) when analytics fields are null —
 * as required by `__docs/TESTING.md`.
 *
 * Runs on the JVM target (headless Skiko) — see [UiTestSupport] and `__docs/TESTING.md`.
 */
class AnalyticsScreenTest {
    @Test
    fun loading_shows_spinner() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { neverComplete = true }
            setContent { AnalyticsScreen(HomeFlowRepository(fake)) }
            onLoadingSpinner().assertExists()
        }

    @Test
    fun error_shows_retry() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource().apply { failure = TEST_FAILURE }
            setContent { AnalyticsScreen(HomeFlowRepository(fake)) }
            onNodeWithText("Couldn't load this").assertExists()
            onNodeWithText("Try again").assertExists()
        }

    @Test
    fun loaded_renders_sections_with_defaults() =
        runComposeUiTest {
            val fake = FakeHomeFlowDataSource()
            setContent { AnalyticsScreen(HomeFlowRepository(fake)) }
            // Section headers
            onNodeWithText("Cycle stats").assertExists()
            onNodeWithText("Average cycle length").assertExists()
            onNodeWithText("28 days").assertExists()
            onNodeWithText("Period length").assertExists()
            onNodeWithText("2024-01-01").assertExists()
            onNodeWithText("5 bleeding days").assertExists()
            onNodeWithText("Ovulation prediction").assertExists()
            // Null predictions → empty hint
            onNodeWithText("Not enough data yet — track at least two cycles.").assertExists()
            onNodeWithText("Sleep by phase").assertExists()
            // Empty phases → empty hint
            onNodeWithText("Not enough sleep data logged yet.").assertExists()
        }

    @Test
    fun thin_stats_show_empty_hints() =
        runComposeUiTest {
            val fake =
                FakeHomeFlowDataSource().apply {
                    cycleStats = CycleStatsDto()
                    periodChart = PeriodLengthChartDto(emptyList())
                }
            setContent { AnalyticsScreen(HomeFlowRepository(fake)) }
            // Two "not enough data" hints appear: one in Cycle stats, one in Ovulation prediction.
            onAllNodesWithText("Not enough data yet — track at least two cycles.")[0].assertExists()
            onNodeWithText("No closed cycles to chart yet.").assertExists()
        }
}
