package org.homeflow.app.shared.data.local

import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.PainDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.validation.ValidationResult
import org.homeflow.core.validation.validateDailyLogWithinCycle
import org.homeflow.core.validation.validateNotes
import org.homeflow.core.validation.validatePainLocations
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// SQLDelight generates a row class named after the table in PascalCase.
// Table `daily_logs` → class `DailyLogs` in package org.homeflow.app.shared.db.
private typealias DailyLogRow = org.homeflow.app.shared.db.DailyLogs

@OptIn(ExperimentalUuidApi::class)
class LocalDailyLogsStore(
    private val db: HomeFlowDb,
    private val userId: String,
    private val cyclesStore: LocalCyclesStore,
    private val subsStore: LocalSubsStore,
    private val refData: LocalRefData,
) {
    private val q get() = db.dailyLogsQueries
    private val painQ get() = db.painLogsQueries

    fun getDailyLog(date: String): ApiResult<DailyLogDto> {
        val row = q.selectByDate(userId, date).executeAsOneOrNull()
            ?: return ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "Daily log not found for the given date.", 404)
        return ApiResult.Success(assembleDto(row))
    }

    fun createDailyLog(date: String, cycleId: String): ApiResult<Unit> {
        // Cycle must exist.
        val range = cyclesStore.getCycleRange(cycleId)
            ?: return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Cycle not found.", 400)

        // Date must be within cycle range.
        val logDate = runCatching { LocalDate.parse(date) }.getOrElse {
            return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Invalid date format.", 400)
        }
        val cycleStart = LocalDate.parse(range.first)
        val cycleEnd = range.second?.let { LocalDate.parse(it) }
        val rangeCheck = validateDailyLogWithinCycle(logDate, cycleStart, cycleEnd)
        if (!rangeCheck.isValid) {
            return ApiResult.Failure(
                ErrorCode.VALIDATION_ERROR,
                (rangeCheck as ValidationResult.Invalid).message,
                400,
            )
        }

        // Conflict check.
        val existing = q.selectByDate(userId, date).executeAsOneOrNull()
        if (existing != null) {
            return ApiResult.Failure(ErrorCode.CONFLICT, "A daily log already exists for this date.", 409)
        }

        val now = Clock.System.now().toString()
        val id = Uuid.random().toString()
        q.insert(id, userId, cycleId, date, null, now, now, null)
        return ApiResult.Success(Unit)
    }

    fun patchNotes(date: String, notes: String?): ApiResult<Unit> {
        val row = q.selectByDate(userId, date).executeAsOneOrNull()
            ?: return ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "Daily log not found for the given date.", 404)

        val result = validateNotes(notes)
        if (!result.isValid) {
            return ApiResult.Failure(
                ErrorCode.VALIDATION_ERROR,
                (result as ValidationResult.Invalid).message,
                400,
            )
        }
        val now = Clock.System.now().toString()
        q.updateNotes(notes, now, row.id, userId)
        return ApiResult.Success(Unit)
    }

    fun putPain(date: String, locations: List<PainLocationDto>): ApiResult<Unit> {
        val row = q.selectByDate(userId, date).executeAsOneOrNull()
            ?: return ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "Daily log not found for the given date.", 404)

        val validation = validatePainLocations(locations)
        if (!validation.isValid) {
            return ApiResult.Failure(
                ErrorCode.VALIDATION_ERROR,
                (validation as ValidationResult.Invalid).message,
                400,
            )
        }

        // Validate each location id is known.
        for (loc in locations) {
            if (!refData.locationExists(loc.locationId)) {
                return ApiResult.Failure(
                    ErrorCode.VALIDATION_ERROR,
                    "Unknown pain location: ${loc.locationId}",
                    400,
                )
            }
        }

        val now = Clock.System.now().toString()

        // Clear existing pain log for this daily log.
        val existingPain = painQ.selectByLogId(row.id).executeAsOneOrNull()
        if (existingPain != null) {
            painQ.deleteLocationsByPainLogId(existingPain.id)
            painQ.deletePainLogByLogId(row.id)
        }

        if (locations.isEmpty()) {
            q.updateTimestamp(now, row.id, userId)
            return ApiResult.Success(Unit)
        }

        // Insert new pain log + locations.
        val painLogId = Uuid.random().toString()
        painQ.insertPainLog(painLogId, row.id, now, now, null)
        for (loc in locations) {
            val locRowId = Uuid.random().toString()
            painQ.insertLocation(locRowId, painLogId, loc.locationId, loc.severity?.toLong(), now, now)
        }
        q.updateTimestamp(now, row.id, userId)
        return ApiResult.Success(Unit)
    }

    /** Returns a fully assembled DailyLogDto for the given anchor row. */
    internal fun assembleDto(row: DailyLogRow): DailyLogDto {
        val subs = subsStore.loadForLog(row.id)
        val pain = loadPain(row.id)
        return DailyLogDto(
            id = row.id,
            logDate = row.log_date,
            cycleId = row.cycle_id,
            notes = row.notes,
            emotions = subs["emotions"]?.takeIf { it.isNotEmpty() },
            sleep = subs["sleep_quality"]?.takeIf { it.isNotEmpty() },
            energy = subs["energy"]?.firstOrNull(),
            sex = subs["sex"]?.takeIf { it.isNotEmpty() },
            discharge = subs["discharge"]?.takeIf { it.isNotEmpty() },
            skin = subs["skin"]?.takeIf { it.isNotEmpty() },
            digestion = subs["digestion"]?.takeIf { it.isNotEmpty() },
            flow = subs["blood_flow"]?.firstOrNull(),
            collection = subs["collection_method"]?.firstOrNull(),
            mind = subs["mind"]?.takeIf { it.isNotEmpty() },
            pain = pain,
            createdAt = row.created_at,
            updatedAt = row.updated_at,
        )
    }

    private fun loadPain(logId: String): PainDto? {
        val painLog = painQ.selectByLogId(logId).executeAsOneOrNull() ?: return null
        val locs = painQ.selectLocationsByPainLogId(painLog.id).executeAsList()
        return PainDto(
            id = painLog.id,
            locations = locs.map { PainLocationDto(it.location_id, it.severity?.toInt()) },
        )
    }

    /** Returns all daily log rows for a cycle (for analytics). */
    fun getLogsByCycleId(cycleId: String): List<DailyLogRow> =
        q.selectByCycleId(cycleId).executeAsList()

    /** Returns log dates that have a blood_flow selection (for bleeding-day count). */
    fun getFlowDatesByCycleId(cycleId: String): List<String> =
        q.selectDatesWithFlow(cycleId).executeAsList()
}
