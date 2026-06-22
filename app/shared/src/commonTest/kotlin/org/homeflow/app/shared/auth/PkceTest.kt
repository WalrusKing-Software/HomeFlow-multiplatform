package org.homeflow.app.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun challenge_matches_rfc7636_appendix_b_vector() {
        // RFC 7636 Appendix B worked example.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, Pkce.challenge(verifier))
    }

    @Test
    fun verifier_is_url_safe_and_within_spec_length() {
        val verifier = Pkce.newVerifier()
        // 43..128 chars and only the unreserved base64url alphabet (no padding).
        assertTrue(verifier.length in 43..128, "length was ${verifier.length}")
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "non-url-safe char in $verifier")
    }

    @Test
    fun generated_challenge_is_derived_from_its_verifier() {
        val challenge = PkceChallenge.generate()
        assertEquals(Pkce.challenge(challenge.verifier), challenge.challenge)
        assertTrue(challenge.state.isNotBlank())
    }
}
