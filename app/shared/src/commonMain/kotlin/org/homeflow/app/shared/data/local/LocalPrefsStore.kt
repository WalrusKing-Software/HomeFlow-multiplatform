package org.homeflow.app.shared.data.local

import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.validation.ValidationResult
import org.homeflow.core.validation.validateCategoryOrder
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LocalPrefsStore(
    private val db: HomeFlowDb,
    private val userId: String,
    private val refData: LocalRefData,
) {
    // File Preferences.sq → db.preferencesQueries
    private val q get() = db.preferencesQueries

    fun getPreferences(): ApiResult<PreferencesDto> {
        val row = q.selectByUserId(userId).executeAsOneOrNull()
        val order = if (row != null) {
            row.category_order.split(",").filter { it.isNotBlank() }
        } else {
            refData.defaultCategoryOrder()
        }
        return ApiResult.Success(PreferencesDto(categoryOrder = order))
    }

    fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse> {
        val allSlugs = refData.allCategorySlugs()
        val validation = validateCategoryOrder(categoryOrder, allSlugs)
        if (!validation.isValid) {
            return ApiResult.Failure(
                ErrorCode.VALIDATION_ERROR,
                (validation as ValidationResult.Invalid).message,
                400,
            )
        }

        val now = Clock.System.now().toString()
        val encoded = categoryOrder.joinToString(",")
        val rowId = q.selectByUserId(userId).executeAsOneOrNull()?.id ?: Uuid.random().toString()
        q.upsert(rowId, userId, encoded, now, null)
        return ApiResult.Success(PreferencesResponse(categoryOrder = categoryOrder, updatedAt = now))
    }
}
