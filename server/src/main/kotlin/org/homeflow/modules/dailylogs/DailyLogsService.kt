package org.homeflow.modules.dailylogs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.DailyLogAnchorDto
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.NotesResponse
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.PainDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.validation.validateDailyLogWithinCycle
import org.homeflow.core.validation.validateNotes
import org.homeflow.lib.ConflictException
import org.homeflow.lib.Encryption
import org.homeflow.lib.NotFoundException
import org.homeflow.lib.ValidationException
import org.homeflow.lib.orThrow
import org.homeflow.lib.parseIsoDate
import org.homeflow.lib.toIsoString
import org.homeflow.lib.toUuidOrNull
import org.homeflow.modules.cycles.CycleRow
import org.homeflow.modules.cycles.CyclesRepository
import org.homeflow.modules.users.UserPrincipal
import java.util.UUID

/**
 * The per-day anchor: creating it, fetching the assembled day, and updating its
 * encrypted notes. [getDailyLog] now assembles every symptom sub-log (read via
 * [DailyLogSubsRepository]). Cycle ownership and the date-in-range rule are validated
 * here (reusing `:core` rules) before any write.
 *
 * Notes and the sex payload are encrypted/decrypted in this service only (never the
 * repository, route, or client) — see `__docs/ARCHITECTURE-server.md` "Encryption".
 */
class DailyLogsService(
    private val dailyLogsRepository: DailyLogsRepository,
    private val cyclesRepository: CyclesRepository,
    private val subsRepository: DailyLogSubsRepository,
    private val encryption: Encryption,
) {
    /**
     * The full day log for [dateStr]; 404 if no anchor exists. Notes and sex are
     * decrypted here; a category with no rows logged is returned as null.
     */
    fun getDailyLog(
        principal: UserPrincipal,
        dateStr: String,
    ): DailyLogDto {
        val date = parseIsoDate(dateStr)
        val day =
            subsRepository.assembleDay(principal.id, date)
                ?: throw NotFoundException("No log exists for this date.")
        val anchor = day.anchor
        val sex =
            day.sexEncryptedPayload?.let {
                Json.decodeFromString(ID_LIST_SERIALIZER, encryption.decrypt(it))
            }
        return DailyLogDto(
            id = anchor.id.toString(),
            logDate = anchor.logDate.toString(),
            cycleId = anchor.cycleId.toString(),
            notes = anchor.notes?.let(encryption::decrypt),
            emotions = day.emotions.asNullableStrings(),
            sleep = day.sleep.asNullableStrings(),
            energy = day.energy?.toString(),
            sex = sex,
            discharge = day.discharge.asNullableStrings(),
            skin = day.skin.asNullableStrings(),
            digestion = day.digestion.asNullableStrings(),
            flow = day.flow?.toString(),
            collection = day.collection?.toString(),
            mind = day.mind.asNullableStrings(),
            pain =
                day.pain?.let { pain ->
                    PainDto(
                        id = pain.painLogId.toString(),
                        locations =
                            pain.locations.map {
                                PainLocationDto(locationId = it.locationId.toString(), severity = it.severity)
                            },
                    )
                },
            createdAt = anchor.createdAt.toIsoString(),
            updatedAt = anchor.updatedAt.toIsoString(),
        )
    }

    /**
     * Creates the anchor row, after checking the cycle belongs to the user and the
     * date falls within it (both 400 on failure). 409 if a log already exists for the
     * day. Must be called before any sub-log write for that date.
     */
    fun createAnchor(
        principal: UserPrincipal,
        request: CreateDailyLogRequest,
    ): DailyLogAnchorDto {
        val date = parseIsoDate(request.date)
        val cycle = requireOwnedCycle(principal, request.cycleId)
        validateDailyLogWithinCycle(date, cycle.startDate, cycle.endDate).orThrow()
        val id = request.id?.let { it.toUuidOrNull() ?: throw ValidationException("Invalid id: expected a UUID.") }

        val row =
            dailyLogsRepository.insertIfAbsent(principal.id, cycle.id, date, id ?: UUID.randomUUID())
                ?: throw ConflictException("A log already exists for this date.")
        return DailyLogAnchorDto(
            id = row.id.toString(),
            logDate = row.logDate.toString(),
            cycleId = row.cycleId.toString(),
            createdAt = row.createdAt.toIsoString(),
            updatedAt = row.updatedAt.toIsoString(),
        )
    }

    /**
     * Replaces the day's free-text notes (encrypted before storage; null clears it).
     * 404 if no anchor exists for the date. The response echoes the plaintext just set.
     */
    fun updateNotes(
        principal: UserPrincipal,
        dateStr: String,
        request: NotesUpdateRequest,
    ): NotesResponse {
        val date = parseIsoDate(dateStr)
        validateNotes(request.notes).orThrow()
        // Encrypt only when there is something to store; a null clears the column.
        val ciphertext = request.notes?.let(encryption::encrypt)
        val row =
            dailyLogsRepository.updateNotes(principal.id, date, ciphertext)
                ?: throw NotFoundException("No log exists for this date.")
        return NotesResponse(notes = request.notes, updatedAt = row.updatedAt.toIsoString())
    }

    /**
     * Resolves the request's `cycleId` to a cycle owned by the user, or rejects it
     * (400): a malformed UUID and a cycle that isn't the user's are both validation
     * failures here (`__docs/API.md` — `POST /daily-logs`), never a 404 disclosure.
     */
    private fun requireOwnedCycle(
        principal: UserPrincipal,
        cycleIdStr: String,
    ): CycleRow {
        val cycleId =
            cycleIdStr.toUuidOrNull()
                ?: throw ValidationException("Invalid cycleId: expected a UUID.")
        return cyclesRepository.findById(principal.id, cycleId)
            ?: throw ValidationException("cycleId must reference one of your cycles.")
    }

    /** A category with no rows logged renders as null (not `[]`) per `__docs/API.md`. */
    private fun List<UUID>.asNullableStrings(): List<String>? = if (isEmpty()) null else map(UUID::toString)

    private companion object {
        val ID_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
