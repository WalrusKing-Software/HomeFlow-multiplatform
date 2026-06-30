package org.homeflow.app.shared.config

/** The deployment mode the user selected on first run. */
enum class AppMode {
    /** All data stored locally; no server or Keycloak required. */
    LOCAL_ONLY,

    /** Data on a self-hosted server; OIDC login via Keycloak. */
    SERVER,
}
