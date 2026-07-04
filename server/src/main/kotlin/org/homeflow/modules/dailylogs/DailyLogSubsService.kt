package org.homeflow.modules.dailylogs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.homeflow.core.dto.CollectionResponse
import org.homeflow.core.dto.DigestionResponse
import org.homeflow.core.dto.DischargeResponse
import org.homeflow.core.dto.EmotionsResponse
import org.homeflow.core.dto.EnergyResponse
import org.homeflow.core.dto.FlowResponse
import org.homeflow.core.dto.MindResponse
import org.homeflow.core.dto.OptionIdRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.PainDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainResponse
import org.homeflow.core.dto.PainUpdateRequest
import org.homeflow.core.dto.SexResponse
import org.homeflow.core.dto.SkinResponse
import org.homeflow.core.dto.SleepResponse
import org.homeflow.core.validation.validatePainLocations
import org.homeflow.db.DailyLogCollection
import org.homeflow.db.DailyLogDigestion
import org.homeflow.db.DailyLogDischarge
import org.homeflow.db.DailyLogEmotions
import org.homeflow.db.DailyLogEnergy
import org.homeflow.db.DailyLogFlow
import org.homeflow.db.DailyLogMind
import org.homeflow.db.DailyLogSkin
import org.homeflow.db.DailyLogSleep
import org.homeflow.db.MultiSelectLog
import org.homeflow.db.SingleSelectLog
import org.homeflow.lib.Encryption
import org.homeflow.lib.NotFoundException
import org.homeflow.lib.ValidationException
import org.homeflow.lib.orThrow
import org.homeflow.lib.parseIsoDate
import org.homeflow.lib.toIsoString
import org.homeflow.lib.toUuidOrNull
import org.homeflow.modules.refdata.RefDataRepository
import org.homeflow.modules.users.UserPrincipal
import java.util.UUID

/**
 * The symptom sub-log writes (`PUT /daily-logs/:date/{category}` and `/pain`). Each
 * uses `PUT` replace semantics from `__docs/API.md`: validate the submitted option IDs
 * against the category's reference data (400 on anything unknown), then replace the set.
 *
 * Sex is the one encrypted category — option IDs are validated against the `sex`
 * category, serialized to a JSON array, and encrypted here (service layer only) before
 * the repository stores ciphertext (see the `daily_log_sex` encryption addendum). A
 * missing anchor for the date surfaces as `404` (the frontend must `POST` it first).
 */
@Suppress("TooManyFunctions") // one method per symptom category route, by design
class DailyLogSubsService(
    private val subsRepository: DailyLogSubsRepository,
    private val refDataRepository: RefDataRepository,
    private val encryption: Encryption,
) {
    fun setEmotions(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): EmotionsResponse {
        val ids = replaceMulti(DailyLogEmotions, principal, dateStr, request, EMOTIONS)
        return EmotionsResponse(emotions = ids.asStrings(), updatedAt = ids.updatedAt)
    }

    fun setSleep(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): SleepResponse {
        val ids = replaceMulti(DailyLogSleep, principal, dateStr, request, SLEEP_QUALITY)
        return SleepResponse(sleep = ids.asStrings(), updatedAt = ids.updatedAt)
    }

    fun setDischarge(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): DischargeResponse {
        val ids = replaceMulti(DailyLogDischarge, principal, dateStr, request, DISCHARGE)
        return DischargeResponse(discharge = ids.asStrings(), updatedAt = ids.updatedAt)
    }

    fun setSkin(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): SkinResponse {
        val ids = replaceMulti(DailyLogSkin, principal, dateStr, request, SKIN)
        return SkinResponse(skin = ids.asStrings(), updatedAt = ids.updatedAt)
    }

    fun setDigestion(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): DigestionResponse {
        val ids = replaceMulti(DailyLogDigestion, principal, dateStr, request, DIGESTION)
        return DigestionResponse(digestion = ids.asStrings(), updatedAt = ids.updatedAt)
    }

    fun setMind(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): MindResponse {
        val ids = replaceMulti(DailyLogMind, principal, dateStr, request, MIND)
        return MindResponse(mind = ids.asStrings(), updatedAt = ids.updatedAt)
    }

    fun setEnergy(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdRequest,
    ): EnergyResponse {
        val result = replaceSingle(DailyLogEnergy, principal, dateStr, request, ENERGY)
        return EnergyResponse(energy = result.id?.toString(), updatedAt = result.updatedAt)
    }

    fun setFlow(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdRequest,
    ): FlowResponse {
        val result = replaceSingle(DailyLogFlow, principal, dateStr, request, BLOOD_FLOW)
        return FlowResponse(flow = result.id?.toString(), updatedAt = result.updatedAt)
    }

    fun setCollection(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdRequest,
    ): CollectionResponse {
        val result = replaceSingle(DailyLogCollection, principal, dateStr, request, COLLECTION_METHOD)
        return CollectionResponse(collection = result.id?.toString(), updatedAt = result.updatedAt)
    }

    /**
     * Replaces the day's sex selections. Validated against the `sex` category, then
     * encrypted as a JSON array of option-id strings; an empty selection clears the entry.
     */
    fun setSex(
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
    ): SexResponse {
        val date = parseIsoDate(dateStr)
        val ids = validateOptionIds(request.optionIds, SEX)
        val payload =
            if (ids.isEmpty()) {
                null
            } else {
                encryption.encrypt(Json.encodeToString(ID_LIST_SERIALIZER, ids.map(UUID::toString)))
            }
        val updatedAt =
            subsRepository.replaceSex(principal.id, date, payload)
                ?: throw NotFoundException("No log exists for this date.")
        return SexResponse(sex = ids.map(UUID::toString), updatedAt = updatedAt.toIsoString())
    }

    /**
     * Replaces the day's pain log: every selected location with its own severity. An
     * empty `locations` array clears the entry. Severity range and duplicate-location
     * checks reuse the shared `:core` rule; location IDs are validated against ref data.
     */
    fun setPain(
        principal: UserPrincipal,
        dateStr: String,
        request: PainUpdateRequest,
    ): PainResponse {
        val date = parseIsoDate(dateStr)
        validatePainLocations(request.locations).orThrow()
        val validLocationIds = refDataRepository.painLocationIds()
        val locations = request.locations.map { toPainLocation(it, validLocationIds) }
        val result =
            subsRepository.replacePain(principal.id, date, locations)
                ?: throw NotFoundException("No log exists for this date.")
        val pain = result.painLogId?.let { PainDto(id = it.toString(), locations = request.locations) }
        return PainResponse(pain = pain, updatedAt = result.updatedAt.toIsoString())
    }

    /** Resolves a submitted pain location to a validated UUID (400 on malformed/unknown). */
    private fun toPainLocation(
        location: PainLocationDto,
        validLocationIds: Set<UUID>,
    ): AssembledPainLocation {
        val id = location.locationId.toUuidOrNull() ?: throw ValidationException("Invalid pain location id.")
        if (id !in validLocationIds) throw ValidationException("Unknown pain location id.")
        return AssembledPainLocation(locationId = id, severity = location.severity)
    }

    private fun replaceMulti(
        table: MultiSelectLog,
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdsRequest,
        categorySlug: String,
    ): MultiResult {
        val date = parseIsoDate(dateStr)
        val ids = validateOptionIds(request.optionIds, categorySlug)
        val updatedAt =
            subsRepository.replaceMultiSelect(table, principal.id, date, ids)
                ?: throw NotFoundException("No log exists for this date.")
        return MultiResult(ids, updatedAt.toIsoString())
    }

    private fun replaceSingle(
        table: SingleSelectLog,
        principal: UserPrincipal,
        dateStr: String,
        request: OptionIdRequest,
        categorySlug: String,
    ): SingleResult {
        val date = parseIsoDate(dateStr)
        val id = validateOptionId(request.optionId, categorySlug)
        val updatedAt =
            subsRepository.replaceSingleSelect(table, principal.id, date, id)
                ?: throw NotFoundException("No log exists for this date.")
        return SingleResult(id, updatedAt.toIsoString())
    }

    /**
     * Parses and validates submitted [optionIds] against the [categorySlug]'s reference
     * options (deduplicated). A malformed UUID or an ID outside the category is a 400.
     */
    private fun validateOptionIds(
        optionIds: List<String>,
        categorySlug: String,
    ): List<UUID> {
        val valid = refDataRepository.optionIdsForCategory(categorySlug)
        val parsed =
            optionIds.map { it.toUuidOrNull() ?: throw ValidationException("Invalid option id.") }.distinct()
        parsed.forEach { id ->
            if (id !in valid) throw ValidationException("Option id is not valid for this category.")
        }
        return parsed
    }

    /** Validates a single optional [optionId] against the [categorySlug] (null is allowed = clear). */
    private fun validateOptionId(
        optionId: String?,
        categorySlug: String,
    ): UUID? {
        if (optionId == null) return null
        val id = optionId.toUuidOrNull() ?: throw ValidationException("Invalid option id.")
        if (id !in refDataRepository.optionIdsForCategory(categorySlug)) {
            throw ValidationException("Option id is not valid for this category.")
        }
        return id
    }

    private data class MultiResult(
        val ids: List<UUID>,
        val updatedAt: String,
    ) {
        fun asStrings(): List<String> = ids.map(UUID::toString)
    }

    private data class SingleResult(
        val id: UUID?,
        val updatedAt: String,
    )

    private companion object {
        const val EMOTIONS = "emotions"
        const val SLEEP_QUALITY = "sleep_quality"
        const val ENERGY = "energy"
        const val SEX = "sex"
        const val DISCHARGE = "discharge"
        const val SKIN = "skin"
        const val DIGESTION = "digestion"
        const val BLOOD_FLOW = "blood_flow"
        const val COLLECTION_METHOD = "collection_method"
        const val MIND = "mind"

        val ID_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
