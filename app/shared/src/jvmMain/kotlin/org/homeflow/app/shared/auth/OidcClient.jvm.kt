package org.homeflow.app.shared.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.config.platformOidc
import org.homeflow.app.shared.data.DesktopTls
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop OIDC: open the system browser to the authorization URL (Authorization Code +
 * PKCE S256, handled by [KeycloakOidcApi]) and capture the redirect on a short-lived
 * loopback HTTP listener bound to an ephemeral `127.0.0.1` port. The token exchange,
 * refresh, and revoke all go through the same shared Keycloak API.
 */
class DesktopOidcClient(
    private val config: AuthConfig,
) : OidcClient {
    private val clientId = platformOidc.clientId

    override suspend fun login(): OidcTokens {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectUri = "http://127.0.0.1:${server.address.port}/oauth2redirect"
        val challenge = PkceChallenge.generate()
        val codeDeferred = CompletableDeferred<String>()

        server.createContext("/oauth2redirect") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val code = params["code"]
            val stateOk = params["state"] == challenge.state
            respond(
                exchange,
                if (code != null &&
                    stateOk
                ) {
                    "Login complete — you can return to HomeFlow."
                } else {
                    "Login failed."
                },
            )
            when {
                code != null && stateOk -> codeDeferred.complete(code)
                else ->
                    codeDeferred.completeExceptionally(
                        IllegalStateException("Authorization failed or state mismatch"),
                    )
            }
        }
        server.start()

        val http = oidcHttpClient()
        return try {
            val api = KeycloakOidcApi(config, http)
            openBrowser(api.buildAuthorizationUrl(clientId, redirectUri, challenge))
            val code = withTimeout(AUTH_TIMEOUT) { codeDeferred.await() }
            api.exchangeCode(clientId, redirectUri, code, challenge.verifier)
        } finally {
            http.close()
            server.stop(0)
        }
    }

    override suspend fun refresh(refreshToken: String): OidcTokens {
        val http = oidcHttpClient()
        return try {
            KeycloakOidcApi(config, http).refresh(clientId, refreshToken)
        } finally {
            http.close()
        }
    }

    override suspend fun logout(refreshToken: String) {
        val http = oidcHttpClient()
        try {
            KeycloakOidcApi(config, http).logout(clientId, refreshToken)
        } finally {
            http.close()
        }
    }

    private fun oidcHttpClient(): HttpClient =
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) { json(appJson) }
            // Trust Caddy's internal CA in dev so the token exchange over https://localhost
            // doesn't fail with "unable to find valid certification path" (see DesktopTls).
            engine { DesktopTls.applyTo(this) }
        }

    private fun openBrowser(url: String) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        require(desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            "No system browser available to start login"
        }
        desktop.browse(URI(url))
    }

    private fun respond(
        exchange: HttpExchange,
        message: String,
    ) {
        val body = "<html><body><p>$message</p></body></html>".encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw
            ?.split("&")
            ?.mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) {
                    null
                } else {
                    URLDecoder.decode(part.substring(0, idx), "UTF-8") to
                        URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }
            }?.toMap()
            .orEmpty()

    private companion object {
        val AUTH_TIMEOUT = 5.minutes
    }
}

actual fun createOidcClient(config: AuthConfig): OidcClient = DesktopOidcClient(config)
