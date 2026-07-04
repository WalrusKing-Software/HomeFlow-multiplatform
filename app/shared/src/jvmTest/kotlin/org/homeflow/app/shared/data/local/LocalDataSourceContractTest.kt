package org.homeflow.app.shared.data.local

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.core.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [LocalDataSource] — verifies the behavioral contract documented
 * on [HomeFlowDataSource] (absence → NOT_FOUND, duplicate → CONFLICT, etc.).
 */
class LocalDataSourceContractTest {
    private fun ds() = TestDbHelper.seededDataSource()

    // ── getMe ──────────────────────────────────────────────────────────────────

    @Test
    fun `getMe returns success with fixed user id`() =
        runTest {
            val result = ds().getMe()
            assertIs<ApiResult.Success<*>>(result)
            assertEquals(LocalBootstrap.LOCAL_USER_ID, (result as ApiResult.Success).value.id)
        }

    // ── Cycles ────────────────────────────────────────────────────────────────

    @Test
    fun `getCycles returns empty list initially`() =
        runTest {
            val result = ds().getCycles()
            assertIs<ApiResult.Success<*>>(result)
            assertTrue((result as ApiResult.Success).value.cycles.isEmpty())
        }

    @Test
    fun `getCurrentCycle returns NOT_FOUND when no open cycle`() =
        runTest {
            val result = ds().getCurrentCycle()
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, (result as ApiResult.Failure).code)
        }

    @Test
    fun `createCycle creates an open cycle`() =
        runTest {
            val ds = ds()
            val result = ds.createCycle("2024-01-15")
            assertIs<ApiResult.Success<*>>(result)
            val cycle = (result as ApiResult.Success).value
            assertEquals("2024-01-15", cycle.startDate)
            assertNull(cycle.endDate)
        }

    @Test
    fun `createCycle auto-closes previous open cycle`() =
        runTest {
            val ds = ds()
            ds.createCycle("2024-01-15")
            val result = ds.createCycle("2024-02-12")
            assertIs<ApiResult.Success<*>>(result)

            // First cycle should now be closed with end = 2024-02-11
            val cycles = (ds.getCycles() as ApiResult.Success).value.cycles
            assertEquals(2, cycles.size)
            val first = cycles.find { it.startDate == "2024-01-15" }
            assertNotNull(first)
            assertEquals("2024-02-11", first.endDate)
        }

    @Test
    fun `closeCycle sets end date`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            val result = ds.closeCycle(cycle.id, "2024-02-11")
            assertIs<ApiResult.Success<*>>(result)
            assertEquals("2024-02-11", (result as ApiResult.Success).value.endDate)
        }

    @Test
    fun `closeCycle unknown id returns NOT_FOUND`() =
        runTest {
            val result = ds().closeCycle("no-such-id", "2024-02-11")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, (result as ApiResult.Failure).code)
        }

    @Test
    fun `createCycle future date returns VALIDATION_ERROR`() =
        runTest {
            val result = ds().createCycle("2099-01-01")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, (result as ApiResult.Failure).code)
        }

    // ── Daily logs ────────────────────────────────────────────────────────────

    @Test
    fun `getDailyLog returns NOT_FOUND when no anchor`() =
        runTest {
            val result = ds().getDailyLog("2024-01-15")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, (result as ApiResult.Failure).code)
        }

    @Test
    fun `createDailyLog then getDailyLog returns the log`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)
            val result = ds.getDailyLog("2024-01-15")
            assertIs<ApiResult.Success<*>>(result)
            val log = (result as ApiResult.Success).value
            assertEquals("2024-01-15", log.logDate)
            assertEquals(cycle.id, log.cycleId)
        }

    @Test
    fun `createDailyLog duplicate date returns CONFLICT`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)
            val result = ds.createDailyLog("2024-01-15", cycle.id)
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.CONFLICT, (result as ApiResult.Failure).code)
        }

    @Test
    fun `recreate a previously deleted day does not crash and starts empty`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            // Log the day with a selection, then delete it (leaves a soft-delete tombstone
            // that still occupies the UNIQUE(user_id, log_date) slot).
            ds.createDailyLog("2024-01-16", cycle.id)
            val emotionOption =
                (ds.getSymptomCategories() as ApiResult.Success)
                    .value.categories
                    .first { it.slug == "emotions" }
                    .options
                    .first()
                    .id
            ds.putOptionIds("2024-01-16", "emotions", listOf(emotionOption))
            ds.deleteDay("2024-01-16")

            // Re-adding the same day must NOT throw a UNIQUE-constraint violation (the crash).
            val recreate = ds.createDailyLog("2024-01-16", cycle.id)
            assertIs<ApiResult.Success<*>>(recreate)

            // And the resurrected day is a fresh, empty log (old selections cleared).
            val log = (ds.getDailyLog("2024-01-16") as ApiResult.Success).value
            assertNull(log.emotions)
            assertNull(log.notes)
        }

    @Test
    fun `createDailyLog unknown cycle returns VALIDATION_ERROR`() =
        runTest {
            val result = ds().createDailyLog("2024-01-15", "no-such-cycle")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, (result as ApiResult.Failure).code)
        }

    @Test
    fun `createDailyLog date before cycle start returns VALIDATION_ERROR`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            val result = ds.createDailyLog("2024-01-14", cycle.id)
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, (result as ApiResult.Failure).code)
        }

    // ── Sub-logs ──────────────────────────────────────────────────────────────

    @Test
    fun `putOptionIds without anchor returns NOT_FOUND`() =
        runTest {
            val result = ds().putOptionIds("2024-01-15", "emotions", listOf())
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, (result as ApiResult.Failure).code)
        }

    @Test
    fun `putOptionIds sets and reads back emotions`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)

            // Look up valid option ids from ref data.
            val cats = (ds.getSymptomCategories() as ApiResult.Success).value
            val emotionsCat = cats.categories.find { it.slug == "emotions" }!!
            val fineId = emotionsCat.options.find { it.slug == "fine" }!!.id
            val anxiousId = emotionsCat.options.find { it.slug == "anxious" }!!.id

            ds.putOptionIds("2024-01-15", "emotions", listOf(fineId, anxiousId))

            val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertNotNull(log.emotions)
            assertEquals(setOf(fineId, anxiousId), log.emotions!!.toSet())
        }

    @Test
    fun `putOptionId single-select sets energy`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)

            val cats = (ds.getSymptomCategories() as ApiResult.Success).value
            val energyCat = cats.categories.find { it.slug == "energy" }!!
            val tiredId = energyCat.options.find { it.slug == "tired" }!!.id

            ds.putOptionId("2024-01-15", "energy", tiredId)
            val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertEquals(tiredId, log.energy)
        }

    @Test
    fun `putOptionId null clears single-select`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)

            val cats = (ds.getSymptomCategories() as ApiResult.Success).value
            val energyCat = cats.categories.find { it.slug == "energy" }!!
            val tiredId = energyCat.options.find { it.slug == "tired" }!!.id

            ds.putOptionId("2024-01-15", "energy", tiredId)
            ds.putOptionId("2024-01-15", "energy", null)

            val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertNull(log.energy)
        }

    @Test
    fun `putOptionId with invalid id rejects without clearing the existing value`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)

            val cats = (ds.getSymptomCategories() as ApiResult.Success).value
            val energyCat = cats.categories.find { it.slug == "energy" }!!
            val tiredId = energyCat.options.find { it.slug == "tired" }!!.id

            ds.putOptionId("2024-01-15", "energy", tiredId)

            // A bad id (here: an id from a different category) must be rejected AND
            // must not clear the previously-set value.
            val emotionId =
                cats.categories
                    .find { it.slug == "emotions" }!!
                    .options
                    .first()
                    .id
            val result = ds.putOptionId("2024-01-15", "energy", emotionId)
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, (result as ApiResult.Failure).code)

            val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertEquals(tiredId, log.energy)
        }

    // ── Notes ──────────────────────────────────────────────────────────────────

    @Test
    fun `patchNotes without anchor returns NOT_FOUND`() =
        runTest {
            val result = ds().patchNotes("2024-01-15", "hello")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, (result as ApiResult.Failure).code)
        }

    @Test
    fun `patchNotes sets and clears notes`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)

            ds.patchNotes("2024-01-15", "feeling okay")
            var log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertEquals("feeling okay", log.notes)

            ds.patchNotes("2024-01-15", null)
            log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertNull(log.notes)
        }

    @Test
    fun `patchNotes too long returns VALIDATION_ERROR`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)
            val result = ds.patchNotes("2024-01-15", "x".repeat(5001))
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, (result as ApiResult.Failure).code)
        }

    // ── Pain ─────────────────────────────────────────────────────────────────

    @Test
    fun `putPain without anchor returns NOT_FOUND`() =
        runTest {
            val result = ds().putPain("2024-01-15", listOf())
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, (result as ApiResult.Failure).code)
        }

    @Test
    fun `putPain sets and clears pain`() =
        runTest {
            val ds = ds()
            val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
            ds.createDailyLog("2024-01-15", cycle.id)

            val regions = (ds.getPainRegions() as ApiResult.Success).value
            val headNeck = regions.regions.find { it.slug == "head_neck" }!!
            val migraineId = headNeck.locations.find { it.slug == "migraine" }!!.id

            ds.putPain(
                "2024-01-15",
                listOf(
                    org.homeflow.core.dto
                        .PainLocationDto(migraineId, 7),
                ),
            )
            var log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertNotNull(log.pain)
            assertEquals(1, log.pain!!.locations.size)
            assertEquals(migraineId, log.pain!!.locations[0].locationId)
            assertEquals(7, log.pain!!.locations[0].severity)

            // Clear pain.
            ds.putPain("2024-01-15", emptyList())
            log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
            assertNull(log.pain)
        }

    // ── Ref data ──────────────────────────────────────────────────────────────

    @Test
    fun `getSymptomCategories returns 10 categories`() =
        runTest {
            val result = ds().getSymptomCategories()
            assertIs<ApiResult.Success<*>>(result)
            assertEquals(10, (result as ApiResult.Success).value.categories.size)
        }

    @Test
    fun `getPainRegions returns 5 regions`() =
        runTest {
            val result = ds().getPainRegions()
            assertIs<ApiResult.Success<*>>(result)
            assertEquals(5, (result as ApiResult.Success).value.regions.size)
        }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Test
    fun `getPreferences returns default order initially`() =
        runTest {
            val result = ds().getPreferences()
            assertIs<ApiResult.Success<*>>(result)
            val order = (result as ApiResult.Success).value.categoryOrder
            assertEquals(10, order.size)
        }

    @Test
    fun `putPreferences saves custom order`() =
        runTest {
            val ds = ds()
            val allSlugs = (ds.getPreferences() as ApiResult.Success).value.categoryOrder.toMutableList()
            // Reverse as a simple test order change.
            val reversed = allSlugs.reversed()
            val result = ds.putPreferences(reversed)
            assertIs<ApiResult.Success<*>>(result)

            val read = (ds.getPreferences() as ApiResult.Success).value
            assertEquals(reversed, read.categoryOrder)
        }

    @Test
    fun `putPreferences unknown slug returns VALIDATION_ERROR`() =
        runTest {
            val ds = ds()
            val cats = (ds.getSymptomCategories() as ApiResult.Success).value.categories.map { it.slug }
            val badOrder = cats.dropLast(1) + "unknown_slug"
            val result = ds.putPreferences(badOrder)
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, (result as ApiResult.Failure).code)
        }

    // ── deleteAccount ─────────────────────────────────────────────────────────

    @Test
    fun `deleteAccount wipes all user data`() =
        runTest {
            val ds = ds()
            ds.createCycle("2024-01-15")
            ds.deleteAccount()

            // After wipe, cycles list is empty (users row gone too).
            val result = ds.getCycles()
            // The query runs but returns empty (user row gone, so no rows match).
            assertIs<ApiResult.Success<*>>(result)
            assertTrue((result as ApiResult.Success).value.cycles.isEmpty())
        }
}
