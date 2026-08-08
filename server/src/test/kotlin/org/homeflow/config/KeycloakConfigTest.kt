package org.homeflow.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SEC-05: the server must refuse to start on the committed dev-only Keycloak client secret. */
class KeycloakConfigTest {
    @Test
    fun `the dev-only secret is rejected without the dev flag`() {
        assertFailsWith<IllegalArgumentException> {
            KeycloakConfig.validateClientSecret(KeycloakConfig.DEV_BACKEND_SECRET, allowDevSecrets = false)
        }
    }

    @Test
    fun `the dev-only secret is allowed when ALLOW_DEV_SECRETS is set`() {
        assertEquals(
            KeycloakConfig.DEV_BACKEND_SECRET,
            KeycloakConfig.validateClientSecret(KeycloakConfig.DEV_BACKEND_SECRET, allowDevSecrets = true),
        )
    }

    @Test
    fun `a real secret passes without the dev flag`() {
        assertEquals(
            "a-real-generated-secret",
            KeycloakConfig.validateClientSecret("a-real-generated-secret", allowDevSecrets = false),
        )
    }
}
