package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode

/**
 * The result of one backend call: either the decoded body or a typed failure carrying
 * the `:core` [ErrorCode]. Screens pattern-match on [Failure.code] (e.g.
 * `UNAUTHORIZED` → re-login) and render Loading/Loaded/Error uniformly.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val code: ErrorCode,
        val message: String,
        val httpStatus: Int,
    ) : ApiResult<Nothing>
}

/** GET [path] and decode to [T], mapping non-2xx bodies to the shared [ApiError] shape. */
suspend inline fun <reified T> HttpClient.apiGet(path: String): ApiResult<T> =
    runCatching { get(path) }
        .fold(
            onSuccess = { response -> response.toApiResult<T>() },
            onFailure = { ApiResult.Failure(ErrorCode.INTERNAL_ERROR, it.message ?: "Network error", 0) },
        )

suspend inline fun <reified T> HttpResponse.toApiResult(): ApiResult<T> =
    if (status.isSuccess()) {
        ApiResult.Success(body<T>())
    } else {
        val error = runCatching { body<ApiError>() }.getOrNull()
        ApiResult.Failure(
            code = error?.error?.code ?: ErrorCode.INTERNAL_ERROR,
            message = error?.error?.message ?: status.description,
            httpStatus = status.value,
        )
    }
