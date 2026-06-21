package org.homeflow.plugins

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import org.homeflow.config.KeycloakConfig
import org.homeflow.core.ErrorCode
import org.homeflow.lib.respondError
import org.homeflow.modules.users.UserPrincipal
import org.homeflow.modules.users.UsersService
import java.net.URI
import java.util.concurrent.TimeUnit

/** The name of the JWT auth provider; routes opt in with `authenticate(KEYCLOAK_AUTH)`. */
const val KEYCLOAK_AUTH = "keycloak"

private const val LEEWAY_SECONDS = 30L
private const val JWK_CACHE_SIZE = 10L
private const val JWK_CACHE_HOURS = 24L
private const val JWK_RATE_BUCKET = 10L
private const val JWK_RATE_MINUTES = 1L

/**
 * Production [JwkProvider] — fetches Keycloak's signing keys over the **internal**
 * URL, caches them, and refreshes on a `kid` it doesn't recognize (key rotation).
 */
fun buildJwkProvider(keycloak: KeycloakConfig): JwkProvider =
    JwkProviderBuilder(URI(keycloak.jwksUrl).toURL())
        .cached(JWK_CACHE_SIZE, JWK_CACHE_HOURS, TimeUnit.HOURS)
        .rateLimited(JWK_RATE_BUCKET, JWK_RATE_MINUTES, TimeUnit.MINUTES)
        .build()

/**
 * Validates the `Authorization: Bearer <JWT>` on protected routes against Keycloak:
 * RS256 signature (via [jwkProvider]), `iss`, `aud` (must contain
 * [KeycloakConfig.audience]), and `exp` (30s leeway). On success the token's `sub`
 * is upserted into `users` and a [UserPrincipal] is attached for downstream
 * row-scoping. Any failure returns `401 UNAUTHORIZED` in the canonical error shape.
 *
 * RS256 is enforced implicitly: the verifier is built from Keycloak's RSA JWKS, so
 * a token presenting any other `alg` (e.g. an HS256 algorithm-confusion attempt)
 * fails the algorithm check.
 */
fun Application.configureAuthentication(
    keycloak: KeycloakConfig,
    jwkProvider: JwkProvider,
    usersService: UsersService,
) {
    install(Authentication) {
        jwt(KEYCLOAK_AUTH) {
            verifier(jwkProvider, keycloak.issuer) {
                acceptLeeway(LEEWAY_SECONDS)
                withAudience(keycloak.audience)
            }
            validate { credential -> usersService.principalFromClaims(credential.payload) }
            challenge { _, _ ->
                call.respondError(ErrorCode.UNAUTHORIZED, "Authentication required.")
            }
        }
    }
}
