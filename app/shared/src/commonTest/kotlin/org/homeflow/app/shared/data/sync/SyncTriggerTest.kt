package org.homeflow.app.shared.data.sync

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [SyncTrigger]'s scheduling logic (issue #32). The trigger runs against a
 * counting `runSync` and virtual time: [SyncTrigger.nowMillis] is fed from the test scheduler
 * so debounce windows advance in lockstep with `advanceTimeBy`. The trigger's own coroutines
 * run on [runTest]'s `backgroundScope` (auto-cancelled at test end, so the never-ending
 * periodic loop doesn't leak). Launched work is driven with `runCurrent()` / `advanceTimeBy()`
 * rather than `advanceUntilIdle()`, which does not pump `backgroundScope` in coroutines-test
 * 1.11 (and would spin forever on the periodic loop's always-pending delay).
 */
class SyncTriggerTest {
    @Test
    fun start_syncs_immediately_then_on_each_interval() =
        runTest {
            var count = 0
            val trigger =
                SyncTrigger(
                    scope = backgroundScope,
                    runSync = { count++ },
                    periodicInterval = 15.minutes,
                    nowMillis = { testScheduler.currentTime },
                )

            trigger.start()
            runCurrent()
            assertEquals(1, count, "start() should trigger an immediate sync")

            advanceTimeBy(15.minutes)
            runCurrent()
            assertEquals(2, count, "periodic timer should fire after the interval")

            advanceTimeBy(15.minutes)
            runCurrent()
            assertEquals(3, count, "periodic timer should keep firing")
        }

    @Test
    fun stop_halts_the_periodic_timer() =
        runTest {
            var count = 0
            val trigger =
                SyncTrigger(
                    scope = backgroundScope,
                    runSync = { count++ },
                    periodicInterval = 15.minutes,
                    nowMillis = { testScheduler.currentTime },
                )

            trigger.start()
            runCurrent()
            assertEquals(1, count)

            trigger.stop()
            advanceTimeBy(60.minutes)
            runCurrent()
            assertEquals(1, count, "no syncs should fire after stop()")
        }

    @Test
    fun start_is_idempotent() =
        runTest {
            var count = 0
            val trigger =
                SyncTrigger(
                    scope = backgroundScope,
                    runSync = { count++ },
                    periodicInterval = 15.minutes,
                    nowMillis = { testScheduler.currentTime },
                )

            trigger.start()
            trigger.start() // second call must not spin up a second timer or a second immediate sync
            runCurrent()
            assertEquals(1, count)

            advanceTimeBy(15.minutes)
            runCurrent()
            assertEquals(2, count, "only one periodic timer should be running")
        }

    @Test
    fun foreground_is_debounced_within_the_window() =
        runTest {
            var count = 0
            val trigger =
                SyncTrigger(
                    scope = backgroundScope,
                    runSync = { count++ },
                    debounce = 30.seconds,
                    nowMillis = { testScheduler.currentTime },
                )

            trigger.onForeground()
            runCurrent()
            assertEquals(1, count)

            trigger.onForeground() // still within 30s of the last run → dropped
            runCurrent()
            assertEquals(1, count, "a foreground trigger within the debounce window should be dropped")

            advanceTimeBy(31.seconds)
            trigger.onForeground() // window elapsed → runs
            runCurrent()
            assertEquals(2, count, "a foreground trigger after the window should run")
        }

    @Test
    fun manual_syncNow_always_runs_even_within_debounce() =
        runTest {
            var count = 0
            val trigger =
                SyncTrigger(
                    scope = backgroundScope,
                    runSync = { count++ },
                    debounce = 30.seconds,
                    nowMillis = { testScheduler.currentTime },
                )

            trigger.onForeground()
            runCurrent()
            assertEquals(1, count)

            trigger.syncNow() // suspends until done; bypasses the debounce
            assertEquals(2, count, "manual sync must run despite being within the debounce window")
        }
}
