package org.homeflow.app.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.config.AuthConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeycloakOidcApiTest {
    private val config = AuthConfig(host = "example.test")

    private fun jsonClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json() }
        }

    @Test
    fun authorization_url_carries_required_pkce_params() {
        val api = KeycloakOidcApi(config, jsonClient("{}"))
        val challenge = PkceChallenge(verifier = "v", challenge = "chal-123", state = "state-xyz")

        val url = api.buildAuthorizationUrl("homeflow-desktop", "http://127.0.0.1:1234/oauth2redirect", challenge)

        assertTrue(url.startsWith(config.authorizationEndpoint), "wrong endpoint: $url")
        assertTrue("response_type=code" in url, url)
        assertTrue("code_challenge_method=S256" in url, url)
        assertTrue("code_challenge=chal-123" in url, url)
        assertTrue("state=state-xyz" in url, url)
        assertTrue("client_id=homeflow-desktop" in url, url)
        // scope=openid offline_access, percent- or plus-encoded
        assertTrue("scope=openid" in url, url)
    }

    @Test
    fun exchange_code_parses_token_response() =
        runTest {
            val api =
                KeycloakOidcApi(
                    config,
                    jsonClient("""{"access_token":"AT","refresh_token":"RT","expires_in":900,"token_type":"Bearer"}"""),
                )

            val tokens = api.exchangeCode("homeflow-desktop", "http://127.0.0.1:1/oauth2redirect", "code", "verifier")

            assertEquals("AT", tokens.accessToken)
            assertEquals("RT", tokens.refreshToken)
            assertNotNull(tokens.accessTokenExpiresAt)
        }

    @Test
    fun refresh_parses_token_response() =
        runTest {
            val api =
                KeycloakOidcApi(config, jsonClient("""{"access_token":"AT2","refresh_token":"RT2","expires_in":900}"""))

            val tokens = api.refresh("homeflow-desktop", "old-refresh")

            assertEquals("AT2", tokens.accessToken)
            assertEquals("RT2", tokens.refreshToken)
        }

    @Test
    fun refresh_throws_oidc_exception_on_rejected_grant() =
        runTest {
            val api =
                KeycloakOidcApi(
                    config,
                    jsonClient(
                        body = """{"error":"invalid_grant","error_description":"Token is not active"}""",
                        status = HttpStatusCode.BadRequest,
                    ),
                )

            val ex = assertFailsWith<OidcException> { api.refresh("homeflow-desktop", "dead-refresh") }

            assertEquals(HttpStatusCode.BadRequest.value, ex.statusCode)
            assertEquals("invalid_grant", ex.oauthError)
            assertTrue(ex.isGrantRejected, "invalid_grant must be treated as a rejected grant")
        }
}
