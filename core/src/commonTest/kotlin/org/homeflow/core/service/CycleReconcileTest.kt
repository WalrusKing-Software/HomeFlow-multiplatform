package org.homeflow.core.service

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [reconcileOpenCycles] — the shared cycle-boundary rule used by both the
 * server push handler (D-16a.6) and the client reconciler (D-16b.7).
 */
class CycleReconcileTest {
    @Test
    fun singleOpenCycleIsUnchanged() {
        val cycles = listOf(CycleBoundary("a", LocalDate(2024, 3, 1), null))
        assertEquals(cycles, reconcileOpenCycles(cycles))
    }

    @Test
    fun noOpenCyclesAreUnchanged() {
        val cycles =
            listOf(
                CycleBoundary("a", LocalDate(2024, 1, 1), LocalDate(2024, 1, 28)),
                CycleBoundary("b", LocalDate(2024, 2, 1), LocalDate(2024, 2, 27)),
            )
        assertEquals(cycles, reconcileOpenCycles(cycles))
    }

    @Test
    fun twoOpenCyclesCollapseToTheLaterStartStayingOpen() {
        val earlier = CycleBoundary("earlier", LocalDate(2024, 3, 1), null)
        val later = CycleBoundary("later", LocalDate(2024, 4, 5), null)

        val result = reconcileOpenCycles(listOf(earlier, later)).associateBy { it.id }

        // The later-started cycle stays open; the earlier one is auto-closed the day before it.
        assertNull(result.getValue("later").endDate)
        assertEquals(autoCloseEndDate(LocalDate(2024, 4, 5)), result.getValue("earlier").endDate)
    }

    @Test
    fun threeOpenCyclesEachCloseAtTheNextStart() {
        val c1 = CycleBoundary("c1", LocalDate(2024, 1, 1), null)
        val c2 = CycleBoundary("c2", LocalDate(2024, 2, 1), null)
        val c3 = CycleBoundary("c3", LocalDate(2024, 3, 1), null)

        val result = reconcileOpenCycles(listOf(c3, c1, c2)).associateBy { it.id }

        assertEquals(autoCloseEndDate(LocalDate(2024, 2, 1)), result.getValue("c1").endDate)
        assertEquals(autoCloseEndDate(LocalDate(2024, 3, 1)), result.getValue("c2").endDate)
        assertNull(result.getValue("c3").endDate)
    }

    @Test
    fun closedCyclesAreLeftUntouchedWhileOpenOnesReconcile() {
        val closed = CycleBoundary("closed", LocalDate(2024, 1, 1), LocalDate(2024, 1, 20))
        val earlierOpen = CycleBoundary("earlierOpen", LocalDate(2024, 2, 1), null)
        val laterOpen = CycleBoundary("laterOpen", LocalDate(2024, 3, 1), null)

        val result = reconcileOpenCycles(listOf(closed, earlierOpen, laterOpen)).associateBy { it.id }

        assertEquals(LocalDate(2024, 1, 20), result.getValue("closed").endDate)
        assertEquals(autoCloseEndDate(LocalDate(2024, 3, 1)), result.getValue("earlierOpen").endDate)
        assertNull(result.getValue("laterOpen").endDate)
    }
}
