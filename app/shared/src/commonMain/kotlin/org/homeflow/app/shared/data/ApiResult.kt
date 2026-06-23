package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
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
        toFailure()
    }

/** Decode a non-2xx response into the shared [ApiError] shape (or a generic 500 if unparseable). */
suspend fun HttpResponse.toFailure(): ApiResult.Failure {
    val error = runCatching { body<ApiError>() }.getOrNull()
    return ApiResult.Failure(
        code = error?.error?.code ?: ErrorCode.INTERNAL_ERROR,
        message = error?.error?.message ?: status.description,
        httpStatus = status.value,
    )
}

/**
 * Send a JSON [body] via [method] to [path] and ignore the success body — used by the write
 * routes whose echoed response the client doesn't need (it reloads the day/cycle afterwards).
 * 2xx → `Success(Unit)`; anything else maps to the typed [ApiError] failure.
 */
suspend inline fun <reified B> HttpClient.apiSend(
    method: HttpMethod,
    path: String,
    body: B,
): ApiResult<Unit> =
    runCatching {
        request(path) {
            this.method = method
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }.fold(
        onSuccess = { response -> if (response.status.isSuccess()) ApiResult.Success(Unit) else response.toFailure() },
        onFailure = { ApiResult.Failure(ErrorCode.INTERNAL_ERROR, it.message ?: "Network error", 0) },
    )

/** Like [apiSend] but decodes the success body to [R] — for writes whose response the caller needs. */
suspend inline fun <reified B, reified R> HttpClient.apiSendReceiving(
    method: HttpMethod,
    path: String,
    body: B,
): ApiResult<R> =
    runCatching {
        request(path) {
            this.method = method
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }.fold(
        onSuccess = { response -> response.toApiResult<R>() },
        onFailure = { ApiResult.Failure(ErrorCode.INTERNAL_ERROR, it.message ?: "Network error", 0) },
    )
