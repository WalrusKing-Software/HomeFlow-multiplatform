package org.homeflow.app.shared.auth

import org.homeflow.app.shared.config.AppModeStore
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.config.defaultAuthConfig
import org.homeflow.app.shared.data.local.LocalDatabaseFactory

/** Builds a [LocalSessionController] for Mode A using the platform actuals. */
fun localSessionController(modeStore: AppModeStore): LocalSessionController =
    LocalSessionController(
        gate = createAppLockGate(),
        keyStore = createLocalKeyStore(),
        modeStore = modeStore,
        dbFactory = LocalDatabaseFactory(),
    )

/** Wraps today's [AuthController] for Mode B (server-connected via Keycloak). */
fun keycloakSessionController(config: AuthConfig = defaultAuthConfig()): SessionController = buildAuthController(config)
