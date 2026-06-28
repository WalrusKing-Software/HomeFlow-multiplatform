package org.homeflow.app.shared.data.local

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.DayEdits
import org.homeflow.app.shared.data.HomeFlowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real [HomeFlowRepository] over a [LocalDataSource] — the headline Phase 13
 * parity check (and the exact seam Phase 14 wires into the UI). Proves the repository's
 * shaping, label resolution, editor ordering, and `saveDay` diff all work end-to-end
 * against the local store, not just the raw data-source contract.
 */
class HomeFlowRepositoryLocalTest {
    private fun repo() = HomeFlowRepository(TestDbHelper.seededDataSource())

    @Test
    fun `loadDayEditor then saveDay then loadDay round-trips with resolved labels`() =
        runTest {
            val repo = repo()
            assertIs<ApiResult.Success<*>>(repo.startCycle(LocalDate.parse("2024-01-15")))

            val date = LocalDate.parse("2024-01-20")
            val editor = (repo.loadDayEditor(date) as ApiResult.Success).value
            assertNotNull(editor.cycleId, "a cycle should cover the date")

            // Pick a real emotions option from the editor's resolved categories.
            val emotions = editor.categories.first { it.slug == "emotions" }
            val moodSwings = emotions.options.first { it.slug == "mood_swings" }

            val edits =
                DayEdits(
                    selections = mapOf("emotions" to listOf(moodSwings.id)),
                    notes = "felt better",
                    pain = emptyList(),
                )
            assertIs<ApiResult.Success<*>>(repo.saveDay(date, editor, edits))

            // Re-read through the repository: ids are resolved to labels.
            val day = (repo.loadDay(date) as ApiResult.Success).value
            assertNotNull(day)
            val emotionsRow = day.categories.first { it.label == emotions.label }
            assertEquals(listOf(moodSwings.label), emotionsRow.values)
            assertEquals("felt better", day.notes)
        }

    @Test
    fun `saveDay diffs - re-saving unchanged edits is a no-op success`() =
        runTest {
            val repo = repo()
            repo.startCycle(LocalDate.parse("2024-01-15"))
            val date = LocalDate.parse("2024-01-20")
            val editor = (repo.loadDayEditor(date) as ApiResult.Success).value
            val emotions = editor.categories.first { it.slug == "emotions" }
            val edits =
                DayEdits(
                    selections = mapOf("emotions" to listOf(emotions.options.first().id)),
                    notes = null,
                    pain = emptyList(),
                )
            assertIs<ApiResult.Success<*>>(repo.saveDay(date, editor, edits))

            // Reload the editor (now pre-populated) and save the same selections again.
            val editor2 = (repo.loadDayEditor(date) as ApiResult.Success).value
            assertIs<ApiResult.Success<*>>(repo.saveDay(date, editor2, editor2.initial))
            val day = (repo.loadDay(date) as ApiResult.Success).value
            assertNotNull(day)
            assertTrue(day.categories.any { it.label == emotions.label })
        }

    @Test
    fun `loadDashboard composes open cycle, phase, and today over the local store`() =
        runTest {
            val repo = repo()
            repo.startCycle(LocalDate.parse("2024-01-15"))

            val dashboard = (repo.loadDashboard(LocalDate.parse("2024-01-20")) as ApiResult.Success).value
            assertNotNull(dashboard.currentCycle)
            assertEquals("2024-01-15", dashboard.currentCycle!!.startDate)
            assertEquals(6, dashboard.cycleDay) // 2024-01-20 is day 6 of the cycle
            assertNotNull(dashboard.phase)
        }

    @Test
    fun `savePreferences reorders the dashboard categories`() =
        runTest {
            val repo = repo()
            val original = (repo.loadPreferences() as ApiResult.Success).value.order.map { it.slug }
            val reordered = original.reversed()

            val saved = (repo.savePreferences(reordered) as ApiResult.Success).value
            assertEquals(reordered, saved)
            assertEquals(reordered, (repo.loadPreferences() as ApiResult.Success).value.order.map { it.slug })
        }
}
