package org.homeflow.app.shared.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [SyncEngine.syncNow] on a schedule (issue #32). It owns the trigger sources the
 * engine's own KDoc promises, keeping the engine free of any timing/lifecycle concerns:
 *
 * - **periodic timer** — fires every [periodicInterval] while [start]ed;
 * - **foreground** — [onForeground] fires when the app returns to the foreground, debounced
 *   (see [debounce]) so a resume moments after a periodic run does not sync twice;
 * - **manual** — [syncNow] (e.g. a "Sync now" button) always runs and is awaitable, so the
 *   caller can show progress.
 *
 * A future connectivity monitor can call [onConnectivityAvailable] the moment the network
 * returns; it is debounced like [onForeground]. No platform connectivity source is wired yet
 * — the hook exists so that work is additive.
 *
 * Coalescing of overlapping runs is delegated to [SyncEngine.syncNow], which returns
 * immediately when a cycle is already in progress. Every trigger launches into [scope];
 * cancelling that scope (or calling [stop]) halts the periodic timer.
 */
class SyncTrigger(
    private val scope: CoroutineScope,
    private val runSync: suspend () -> Unit,
    private val periodicInterval: Duration = DEFAULT_PERIODIC_INTERVAL,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var periodicJob: Job? = null
    private var lastRunAtMillis: Long? = null

    /**
     * Requests an immediate (foreground) sync and starts the periodic timer. Idempotent: a
     * second call while already started is a no-op, so it is safe to re-invoke on recomposition.
     */
    fun start() {
        if (periodicJob != null) return
        request(debounced = false)
        periodicJob =
            scope.launch {
                while (isActive) {
                    delay(periodicInterval)
                    request(debounced = false)
                }
            }
    }

    /** Cancels the periodic timer. Safe to call more than once. */
    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
    }

    /** The app returned to the foreground: sync unless one ran within [debounce]. */
    fun onForeground() = request(debounced = true)

    /**
     * Network connectivity returned: sync unless one ran within [debounce]. Not yet wired to a
     * platform connectivity monitor — reserved for that follow-up.
     */
    fun onConnectivityAvailable() = request(debounced = true)

    /**
     * Manual "Sync now": always runs, bypassing the debounce, and suspends until the cycle
     * finishes so a caller (e.g. a button) can reflect progress.
     */
    suspend fun syncNow() {
        lastRunAtMillis = nowMillis()
        runSync()
    }

    private fun request(debounced: Boolean) {
        val now = nowMillis()
        if (debounced) {
            val last = lastRunAtMillis
            if (last != null && now - last < debounce.inWholeMilliseconds) return
        }
        lastRunAtMillis = now
        scope.launch { runSync() }
    }

    companion object {
        /** How often the periodic timer fires while the app is open. */
        val DEFAULT_PERIODIC_INTERVAL = 15.minutes

        /** Foreground/connectivity triggers within this window of the last run are dropped. */
        val DEFAULT_DEBOUNCE = 30.seconds
    }
}
