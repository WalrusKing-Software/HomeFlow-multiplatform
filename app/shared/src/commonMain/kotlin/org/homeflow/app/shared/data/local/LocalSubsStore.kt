package org.homeflow.app.shared.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Endpoint slug → category slug mapping (D-13 spec).
 * "sex" is handled specially (plaintext JSON payload in daily_log_sex).
 */
private val ENDPOINT_TO_CATEGORY =
    mapOf(
        "emotions" to "emotions",
        "sleep" to "sleep_quality",
        "sex" to "sex",
        "discharge" to "discharge",
        "skin" to "skin",
        "digestion" to "digestion",
        "mind" to "mind",
        "energy" to "energy",
        "flow" to "blood_flow",
        "collection" to "collection_method",
    )

private val MULTI_SELECT_ENDPOINTS =
    setOf(
        "emotions",
        "sleep",
        "sex",
        "discharge",
        "skin",
        "digestion",
        "mind",
    )

private val SINGLE_SELECT_ENDPOINTS = setOf("energy", "flow", "collection")

@OptIn(ExperimentalUuidApi::class)
class LocalSubsStore(
    private val db: HomeFlowDb,
    private val refData: LocalRefData,
) {
    private val subsQ get() = db.dailyLogSubsQueries

    /**
     * Loads all sub-log data for a given log id.
     * Returns a map of category-slug → list of option-id strings.
     * Sex is returned from the payload JSON.
     */
    fun loadForLog(logId: String): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()

        // Multi-select categories.
        for ((_, catSlug) in ENDPOINT_TO_CATEGORY.entries.filter {
            it.key != "sex" && it.key in MULTI_SELECT_ENDPOINTS
        }) {
            val catId = refData.categoryIdBySlug(catSlug) ?: continue
            val ids = subsQ.selectMultiByLogAndCategory(logId, catId).executeAsList()
            if (ids.isNotEmpty()) result[catSlug] = ids
        }

        // Single-select categories.
        for ((_, catSlug) in ENDPOINT_TO_CATEGORY.entries.filter { it.key in SINGLE_SELECT_ENDPOINTS }) {
            val catId = refData.categoryIdBySlug(catSlug) ?: continue
            val id = subsQ.selectSingleByLogAndCategory(logId, catId).executeAsOneOrNull()
            if (id != null) result[catSlug] = listOf(id)
        }

        // Sex payload.
        val sexPayload = subsQ.selectSexByLogId(logId).executeAsOneOrNull()
        if (sexPayload != null) {
            val ids =
                runCatching {
                    Json.parseToJsonElement(sexPayload).jsonArray.map { it.jsonPrimitive.content }
                }.getOrElse { emptyList() }
            if (ids.isNotEmpty()) result["sex"] = ids
        }

        return result
    }

    fun putOptionIds(
        logId: String,
        endpoint: String,
        optionIds: List<String>,
    ): ApiResult<Unit> {
        val catSlug =
            ENDPOINT_TO_CATEGORY[endpoint]
                ?: return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Unknown endpoint: $endpoint", 400)

        // Sex is handled via payload.
        if (endpoint == "sex") {
            return putSexPayload(logId, optionIds)
        }

        if (endpoint !in MULTI_SELECT_ENDPOINTS) {
            return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Use putOptionId for single-select: $endpoint", 400)
        }

        val catId =
            refData.categoryIdBySlug(catSlug)
                ?: return ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "Category not seeded: $catSlug", 500)

        // Validate all option ids belong to this category.
        val validIds = refData.optionIdsForCategory(catId)
        for (id in optionIds) {
            if (id !in validIds) {
                return ApiResult.Failure(
                    ErrorCode.VALIDATION_ERROR,
                    "Option $id does not belong to category $catSlug",
                    400,
                )
            }
        }

        val now = Clock.System.now().toString()
        subsQ.deleteMultiByLogAndCategory(logId, catId)
        for (optId in optionIds) {
            val rowId = Uuid.random().toString()
            subsQ.insertMulti(rowId, logId, catId, optId, now, now, null)
        }
        return ApiResult.Success(Unit)
    }

    fun putOptionId(
        logId: String,
        endpoint: String,
        optionId: String?,
    ): ApiResult<Unit> {
        val catSlug =
            ENDPOINT_TO_CATEGORY[endpoint]
                ?: return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Unknown endpoint: $endpoint", 400)

        if (endpoint !in SINGLE_SELECT_ENDPOINTS) {
            return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "Use putOptionIds for multi-select: $endpoint", 400)
        }

        val catId =
            refData.categoryIdBySlug(catSlug)
                ?: return ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "Category not seeded: $catSlug", 500)

        // Validate BEFORE mutating: an invalid id must reject without clearing the
        // existing selection (mirrors the server's validate-then-replace order).
        if (optionId != null) {
            val validIds = refData.optionIdsForCategory(catId)
            if (optionId !in validIds) {
                return ApiResult.Failure(
                    ErrorCode.VALIDATION_ERROR,
                    "Option $optionId does not belong to category $catSlug",
                    400,
                )
            }
        }

        val now = Clock.System.now().toString()
        subsQ.deleteSingleByLogAndCategory(logId, catId)
        if (optionId != null) {
            val rowId = Uuid.random().toString()
            subsQ.insertSingle(rowId, logId, catId, optionId, now, now, null)
        }
        return ApiResult.Success(Unit)
    }

    private fun putSexPayload(
        logId: String,
        optionIds: List<String>,
    ): ApiResult<Unit> {
        // Validate option ids belong to the sex category.
        val catId =
            refData.categoryIdBySlug("sex")
                ?: return ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "Sex category not seeded.", 500)
        val validIds = refData.optionIdsForCategory(catId)
        for (id in optionIds) {
            if (id !in validIds) {
                return ApiResult.Failure(
                    ErrorCode.VALIDATION_ERROR,
                    "Option $id does not belong to category sex",
                    400,
                )
            }
        }

        val now = Clock.System.now().toString()
        if (optionIds.isEmpty()) {
            subsQ.deleteSexByLogId(logId)
            return ApiResult.Success(Unit)
        }

        val payload =
            buildJsonArray {
                for (id in optionIds) add(JsonPrimitive(id))
            }.toString()

        val rowId = Uuid.random().toString()
        subsQ.upsertSex(rowId, logId, payload, now, now, null)
        return ApiResult.Success(Unit)
    }
}
