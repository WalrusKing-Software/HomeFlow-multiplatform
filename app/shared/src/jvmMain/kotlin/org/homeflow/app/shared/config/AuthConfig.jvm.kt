package org.homeflow.app.shared.config

/**
 * Desktop public Keycloak client (`KEYCLOAK.md`). The redirect is a loopback URI; the
 * actual ephemeral port is appended by the desktop OIDC actual at login time. The realm
 * trusts `http://127.0.0.1/oauth2redirect` (Keycloak ignores the loopback port per
 * RFC 8252). Defaults to the local Caddy host; override for the Tailscale hostname.
 */
actual val platformOidc: PlatformOidc =
    PlatformOidc(
        clientId = "homeflow-desktop",
        redirectUri = "http://127.0.0.1/oauth2redirect",
        defaultHost = "localhost",
    )
