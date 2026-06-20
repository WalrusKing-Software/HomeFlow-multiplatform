package org.homeflow.lib

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.homeflow.config.KeycloakConfig

/**
 * Deletes the Keycloak identity during account deletion. Behind an interface so the
 * service layer can be tested without a live Keycloak (see `__docs/TESTING.md`).
 */
interface KeycloakAdminClient {
    /** Deletes the Keycloak user with the given `sub`. A missing user is treated as already-deleted. */
    suspend fun deleteUser(keycloakSub: String)
}

/**
 * Real Admin API client. Authenticates with the confidential backend client's
 * **service account** (client-credentials grant) over the internal Keycloak URL,
 * then `DELETE`s the user. Requires the `manage-users` realm-management role on the
 * service account (see `__docs/KEYCLOAK.md`).
 */
class HttpKeycloakAdminClient(
    private val keycloak: KeycloakConfig,
    private val httpClient: HttpClient = defaultHttpClient(),
) : KeycloakAdminClient {
    override suspend fun deleteUser(keycloakSub: String) {
        val token = fetchServiceAccountToken()
        val response: HttpResponse =
            httpClient.delete(keycloak.adminUserUrl(keycloakSub)) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        // 404 means the identity is already gone — idempotent success.
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
            throw InternalException("Keycloak account deletion failed with status ${response.status.value}.")
        }
    }

    private suspend fun fetchServiceAccountToken(): String {
        val response =
            httpClient.submitForm(
                url = keycloak.tokenUrl,
                formParameters =
                    parameters {
                        append("grant_type", "client_credentials")
                        append("client_id", keycloak.clientId)
                        append("client_secret", keycloak.clientSecret)
                    },
            )
        if (!response.status.isSuccess()) {
            throw InternalException("Keycloak service-account token request failed.")
        }
        return response.body<TokenResponse>().accessToken
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
    )

    companion object {
        private fun defaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
    }
}
