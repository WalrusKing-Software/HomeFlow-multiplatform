package org.homeflow.modules.preferences

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.dto.UpdatePreferencesRequest
import org.homeflow.core.validation.validateCategoryOrder
import org.homeflow.lib.orThrow
import org.homeflow.lib.toIsoString
import org.homeflow.modules.refdata.RefDataRepository
import org.homeflow.modules.users.UserPrincipal

/**
 * The dashboard category ordering (`__docs/API.md`). The default order — used until the user
 * first saves their own — is the reference categories in `sort_order`. A saved order must list
 * every known category slug exactly once; that rule lives in `:core` and is the real boundary
 * here (the client runs it only for UX). The order is stored as a JSON array of slugs.
 */
class PreferencesService(
    private val preferencesRepository: PreferencesRepository,
    private val refDataRepository: RefDataRepository,
) {
    /** The user's saved order, or the default (categories by `sort_order`) if none is saved. */
    fun getPreferences(principal: UserPrincipal): PreferencesDto {
        val saved = preferencesRepository.find(principal.id)
        val order = saved?.let { Json.decodeFromString(SLUG_LIST_SERIALIZER, it.categoryOrder) } ?: defaultOrder()
        return PreferencesDto(categoryOrder = order)
    }

    /**
     * Replaces the user's category order. The submitted list must contain every category slug
     * exactly once (no duplicates, unknowns, or omissions) — otherwise `400`.
     */
    fun updatePreferences(
        principal: UserPrincipal,
        request: UpdatePreferencesRequest,
    ): PreferencesResponse {
        validateCategoryOrder(request.categoryOrder, allSlugs()).orThrow()
        val json = Json.encodeToString(SLUG_LIST_SERIALIZER, request.categoryOrder)
        val updatedAt = preferencesRepository.upsert(principal.id, json)
        return PreferencesResponse(categoryOrder = request.categoryOrder, updatedAt = updatedAt.toIsoString())
    }

    /** The default category order: every category slug in `ref_symptom_categories.sort_order`. */
    private fun defaultOrder(): List<String> = refDataRepository.symptomCategories().map { it.slug }

    private fun allSlugs(): Set<String> = refDataRepository.symptomCategories().map { it.slug }.toSet()

    private companion object {
        val SLUG_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
