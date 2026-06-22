package org.homeflow.app.shared.auth

import kotlinx.serialization.json.Json
import org.homeflow.app.shared.crypto.sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * PKCE (RFC 7636) — S256 only, as required of the public Keycloak clients
 * (`KEYCLOAK.md`). The `code_verifier` is a high-entropy random string; the
 * `code_challenge` is `BASE64URL(SHA-256(code_verifier))`.
 *
 * Used directly by the desktop OIDC actual; on Android AppAuth generates and
 * verifies the challenge itself, so this is exercised mainly by the desktop flow
 * and the shared unit tests.
 */
@OptIn(ExperimentalEncodingApi::class)
object Pkce {
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** 32 random bytes → 43-char base64url verifier (within the 43..128 spec range). */
    fun newVerifier(random: Random = Random.Default): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url.encode(bytes)
    }

    /** `code_challenge = BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))`. */
    fun challenge(verifier: String): String = base64Url.encode(sha256(verifier.encodeToByteArray()))

    fun newState(random: Random = Random.Default): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return base64Url.encode(bytes)
    }
}

/** A generated PKCE pair plus a CSRF state value for one authorization request. */
data class PkceChallenge(
    val verifier: String,
    val challenge: String,
    val state: String,
) {
    companion object {
        fun generate(random: Random = Random.Default): PkceChallenge {
            val verifier = Pkce.newVerifier(random)
            return PkceChallenge(verifier, Pkce.challenge(verifier), Pkce.newState(random))
        }
    }
}

/** Lenient JSON for OIDC/token + API payloads (servers may add fields over time). */
val appJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
