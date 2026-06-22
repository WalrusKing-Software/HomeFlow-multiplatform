package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import org.homeflow.core.dto.UserDto

/**
 * Typed calls to the Ktor backend `/api/v1`, using the `:core` DTOs directly (no
 * codegen, no hand-copied models). Phase 7 needs only `GET /users/me` to confirm the
 * signed-in user; the read surface grows in Phase 8.
 */
class HomeFlowApi(
    private val client: HttpClient,
) {
    suspend fun getMe(): ApiResult<UserDto> = client.apiGet("users/me")
}
