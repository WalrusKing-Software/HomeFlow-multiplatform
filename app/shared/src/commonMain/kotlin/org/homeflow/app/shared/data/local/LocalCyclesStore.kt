package org.homeflow.app.shared.data.local

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.db.Cycles
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.service.autoCloseEndDate
import org.homeflow.core.validation.ValidationResult
import org.homeflow.core.validation.validateCycleEnd
import org.homeflow.core.validation.validateCycleStart
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LocalCyclesStore(
    private val db: HomeFlowDb,
    private val userId: String,
) {
    private val q get() = db.cyclesQueries

    fun getCycles(): ApiResult<CyclesResponse> {
        val rows = q.selectAll(userId).executeAsList()
        return ApiResult.Success(
            CyclesResponse(cycles = rows.map { it.toDto() }),
        )
    }

    fun getCurrentCycle(): ApiResult<CycleDto> {
        val row =
            q.selectOpen(userId).executeAsOneOrNull()
                ?: return ApiResult.Failure(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "No open cycle found.",
                    404,
                )
        return ApiResult.Success(row.toDto())
    }

    fun createCycle(startDate: String): ApiResult<CycleDto> {
        val start =
            runCatching { LocalDate.parse(startDate) }.getOrElse {
                return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Invalid start date format.", 400)
            }
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        val startValidation = validateCycleStart(start, today)
        if (!startValidation.isValid) {
            return ApiResult.Failure(
                ErrorCode.VALIDATION_ERROR,
                (startValidation as ValidationResult.Invalid).message,
                400,
            )
        }

        val now = Clock.System.now().toString()

        // Auto-close any open cycle.
        val openCycle = q.selectOpen(userId).executeAsOneOrNull()
        if (openCycle != null) {
            val closeDate = autoCloseEndDate(start)
            q.closeById(closeDate.toString(), now, openCycle.id, userId)
        }

        val id = Uuid.random().toString()
        q.insert(id, userId, startDate, null, now, now, null)

        val created = q.selectById(id, userId).executeAsOne()
        return ApiResult.Success(created.toDto())
    }

    fun closeCycle(
        cycleId: String,
        endDate: String,
    ): ApiResult<CycleDto> {
        val row =
            q.selectById(cycleId, userId).executeAsOneOrNull()
                ?: return ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "Cycle not found.", 404)

        val start = LocalDate.parse(row.start_date)
        val end =
            runCatching { LocalDate.parse(endDate) }.getOrElse {
                return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Invalid end date format.", 400)
            }
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        val result = validateCycleEnd(start, end, today)
        if (!result.isValid) {
            return ApiResult.Failure(
                ErrorCode.VALIDATION_ERROR,
                (result as ValidationResult.Invalid).message,
                400,
            )
        }

        val now = Clock.System.now().toString()
        q.closeById(endDate, now, cycleId, userId)

        val updated = q.selectById(cycleId, userId).executeAsOne()
        return ApiResult.Success(updated.toDto())
    }

    /** Checks that the given cycleId exists for this user. */
    fun cycleExists(cycleId: String): Boolean = q.selectById(cycleId, userId).executeAsOneOrNull() != null

    /** Returns cycle start/end for a given id (for daily-log date validation). */
    fun getCycleRange(cycleId: String): Pair<String, String?>? {
        val row = q.selectById(cycleId, userId).executeAsOneOrNull() ?: return null
        return Pair(row.start_date, row.end_date)
    }

    private fun Cycles.toDto() =
        CycleDto(
            id = id,
            startDate = start_date,
            endDate = end_date,
            createdAt = created_at,
            updatedAt = updated_at,
        )
}
