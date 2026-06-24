package org.homeflow.modules.cycles

import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.UpdateCycleRequest
import org.homeflow.core.service.autoCloseEndDate
import org.homeflow.core.validation.validateCycleEnd
import org.homeflow.core.validation.validateCycleStart
import org.homeflow.lib.NotFoundException
import org.homeflow.lib.ValidationException
import org.homeflow.lib.orThrow
import org.homeflow.lib.parseIsoDate
import org.homeflow.lib.toIsoString
import org.homeflow.lib.toUuidOrNull
import org.homeflow.lib.today
import org.homeflow.modules.users.UserPrincipal
import java.util.UUID

/**
 * Cycle lifecycle: listing, creation (with auto-close of the prior open cycle),
 * lookup, and manual closing. All reads/writes are scoped to the principal's
 * internal user id; validation reuses the shared `:core` rules so the server is the
 * real boundary (the client runs the same rules only for UX). See `__docs/API.md`.
 */
class CyclesService(
    private val cyclesRepository: CyclesRepository,
) {
    /** All of the user's cycles, newest first. */
    fun listCycles(principal: UserPrincipal): CyclesResponse =
        CyclesResponse(cyclesRepository.findAllByUser(principal.id).map(::toDto))

    /**
     * Starts a new cycle. A future start date is rejected (400). Any currently open
     * cycle is auto-closed with `end_date = startDate - 1 day` (`__docs/API.md`).
     */
    fun createCycle(
        principal: UserPrincipal,
        request: CreateCycleRequest,
    ): CycleDto {
        val startDate = parseIsoDate(request.startDate, "startDate")
        validateCycleStart(startDate, today()).orThrow()
        val previousEndDate = autoCloseEndDate(startDate)
        val id = request.id?.let { it.toUuidOrNull() ?: throw ValidationException("Invalid id: expected a UUID.") }
        val cycle =
            cyclesRepository.insertClosingOpen(principal.id, startDate, previousEndDate, id ?: UUID.randomUUID())
        return toDto(cycle)
    }

    /** The user's currently open cycle; 404 if none is open. */
    fun getCurrentCycle(principal: UserPrincipal): CycleDto =
        cyclesRepository.findOpen(principal.id)?.let(::toDto)
            ?: throw NotFoundException("No open cycle exists.")

    /** A single cycle by id; 404 if it does not exist or belongs to another user. */
    fun getCycle(
        principal: UserPrincipal,
        cycleId: String,
    ): CycleDto {
        val cycle = findOwnedCycle(principal, cycleId) ?: throw NotFoundException("Cycle not found.")
        return toDto(cycle)
    }

    /**
     * Closes/updates a cycle by setting its end date. The end date must be on/after the
     * start and not in the future (400); an unknown/foreign cycle is 404.
     */
    fun updateCycle(
        principal: UserPrincipal,
        cycleId: String,
        request: UpdateCycleRequest,
    ): CycleDto {
        val cycle = findOwnedCycle(principal, cycleId) ?: throw NotFoundException("Cycle not found.")
        val endDate = parseIsoDate(request.endDate, "endDate")
        validateCycleEnd(cycle.startDate, endDate, today()).orThrow()
        val updated =
            cyclesRepository.updateEndDate(principal.id, cycle.id, endDate)
                ?: throw NotFoundException("Cycle not found.")
        return toDto(updated)
    }

    /** Looks up an owned cycle, treating a malformed id as "not found" rather than throwing. */
    private fun findOwnedCycle(
        principal: UserPrincipal,
        cycleId: String,
    ): CycleRow? {
        val id = cycleId.toUuidOrNull() ?: return null
        return cyclesRepository.findById(principal.id, id)
    }

    private fun toDto(row: CycleRow): CycleDto =
        CycleDto(
            id = row.id.toString(),
            startDate = row.startDate.toString(),
            endDate = row.endDate?.toString(),
            createdAt = row.createdAt.toIsoString(),
            updatedAt = row.updatedAt.toIsoString(),
        )
}
