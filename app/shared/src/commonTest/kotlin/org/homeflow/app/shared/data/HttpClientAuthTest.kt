package org.homeflow.app.shared.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.auth.OidcTokens
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.core.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpClientAuthTest {
    private val config = AuthConfig(host = "example.test")
    private val meBody = """{"id":"user-1","createdAt":"2024-01-15T10:00:00Z"}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun bearer_provider_refreshes_once_on_401_then_succeeds() =
        runTest {
            val holder = TokenHolder().apply { set(OidcTokens("stale-AT", "RT", null)) }
            var refreshCalls = 0
            var calls = 0
            val engine =
                MockEngine {
                    calls++
                    if (calls == 1) {
                        respond(
                            content = """{"error":{"code":"UNAUTHORIZED","message":"expired"}}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = jsonHeaders(),
                        )
                    } else {
                        respond(content = meBody, status = HttpStatusCode.OK, headers = jsonHeaders())
                    }
                }

            val client =
                buildHttpClient(
                    config = config,
                    tokenHolder = holder,
                    onRefresh = { _ ->
                        refreshCalls++
                        OidcTokens("fresh-AT", "RT2", null).also { holder.set(it) }
                    },
                    engine = engine,
                )

            val result = HomeFlowApi(client).getMe()

            assertIs<ApiResult.Success<*>>(result)
            assertEquals(1, refreshCalls)
        }

    @Test
    fun get_maps_error_body_to_typed_failure() =
        runTest {
            val holder = TokenHolder().apply { set(OidcTokens("AT", "RT", null)) }
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":"RESOURCE_NOT_FOUND","message":"User not found."}}""",
                        status = HttpStatusCode.NotFound,
                        headers = jsonHeaders(),
                    )
                }
            val client = buildHttpClient(config, holder, onRefresh = { null }, engine = engine)

            val result = HomeFlowApi(client).getMe()

            val failure = assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure.code)
            assertEquals(404, failure.httpStatus)
        }

    @Test
    fun get_returns_success_on_200() =
        runTest {
            val holder = TokenHolder().apply { set(OidcTokens("AT", "RT", null)) }
            val engine = MockEngine { respond(content = meBody, status = HttpStatusCode.OK, headers = jsonHeaders()) }
            val client = buildHttpClient(config, holder, onRefresh = { null }, engine = engine)

            val result = HomeFlowApi(client).getMe()

            val success = assertIs<ApiResult.Success<*>>(result)
            assertEquals("user-1", (success.value as org.homeflow.core.dto.UserDto).id)
        }
}
