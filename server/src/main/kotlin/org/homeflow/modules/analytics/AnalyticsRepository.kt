package org.homeflow.modules.analytics

import kotlinx.datetime.LocalDate
import org.homeflow.core.domain.ClosedCycleInput
import org.homeflow.db.Cycles
import org.homeflow.db.DailyLogFlow
import org.homeflow.db.DailyLogSleep
import org.homeflow.db.DailyLogs
import org.homeflow.db.userScopedTransaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/** One sleep selection on a logged day, with the day's cycle start so its phase can be derived. */
data class SleepLogRow(
    val dailyLogId: UUID,
    val logDate: LocalDate,
    val cycleStart: LocalDate,
    val optionId: UUID,
)

/**
 * Read-only access for the analytics routes. Every query is scoped to the authenticated
 * `userId`; the repository only fetches and shapes rows into the `:core` analytics inputs
 * — all of the statistics are computed by the shared domain math in the service layer
 * (see `__docs/ARCHITECTURE-server.md`). A cross-user read simply returns nothing.
 */
class AnalyticsRepository(
    private val db: Database,
) {
    /**
     * The user's closed cycles (those with an `end_date`), each carrying its bleeding-day
     * count — the number of distinct dates in the cycle that have a blood-flow log. Used
     * for cycle-length stats and the period-length chart.
     */
    fun closedCycles(userId: UUID): List<ClosedCycleInput> =
        userScopedTransaction(db, userId) {
            val flowCountByCycle =
                (DailyLogFlow innerJoin DailyLogs)
                    .selectAll()
                    .where { DailyLogs.userId eq userId }
                    .map { it[DailyLogs.cycleId] }
                    .groupingBy { it }
                    .eachCount()
            Cycles
                .selectAll()
                .where { (Cycles.userId eq userId) and Cycles.endDate.isNotNull() }
                .mapNotNull { row ->
                    val end = row[Cycles.endDate] ?: return@mapNotNull null
                    ClosedCycleInput(
                        startDate = row[Cycles.startDate],
                        endDate = end,
                        bleedingDays = flowCountByCycle[row[Cycles.id]] ?: 0,
                    )
                }
        }

    /** The start date of the user's most recent cycle (open or closed), or null if none exist. */
    fun mostRecentCycleStart(userId: UUID): LocalDate? =
        userScopedTransaction(db, userId) {
            val maxStart = Cycles.startDate.max()
            Cycles
                .select(maxStart)
                .where { Cycles.userId eq userId }
                .map { it[maxStart] }
                .singleOrNull()
        }

    /**
     * Every sleep selection the user has logged, paired with its day's cycle start so the
     * service can compute each day's cycle phase. Days span multiple rows (one per selected
     * option); the service groups them by [SleepLogRow.dailyLogId].
     */
    fun sleepLogs(userId: UUID): List<SleepLogRow> =
        userScopedTransaction(db, userId) {
            val cycleStarts =
                Cycles
                    .selectAll()
                    .where { Cycles.userId eq userId }
                    .associate { it[Cycles.id] to it[Cycles.startDate] }
            (DailyLogSleep innerJoin DailyLogs)
                .selectAll()
                .where { DailyLogs.userId eq userId }
                .mapNotNull { row ->
                    val cycleStart = cycleStarts[row[DailyLogs.cycleId]] ?: return@mapNotNull null
                    SleepLogRow(
                        dailyLogId = row[DailyLogs.id],
                        logDate = row[DailyLogs.logDate],
                        cycleStart = cycleStart,
                        optionId = row[DailyLogSleep.optionId],
                    )
                }
        }
}
