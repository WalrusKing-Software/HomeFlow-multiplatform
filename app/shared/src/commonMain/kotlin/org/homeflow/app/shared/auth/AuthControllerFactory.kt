package org.homeflow.app.shared.auth

import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.config.defaultAuthConfig

/** Assembles an [AuthController] from the platform actuals. The UI calls this once. */
fun buildAuthController(
    config: AuthConfig = defaultAuthConfig(),
    clientVersion: String = "unknown",
): AuthController =
    AuthController(
        config = config,
        oidc = createOidcClient(config),
        tokenStore = createTokenStore(),
        gate = createAppLockGate(),
        clientVersion = clientVersion,
    )
