package org.homeflow.app.shared.config

/**
 * Android public Keycloak client (`KEYCLOAK.md`). The custom-scheme redirect must match
 * `homeflow-android`'s Valid Redirect URI and the `appAuthRedirectScheme` manifest
 * placeholder. `defaultHost` is the release default (the emulator reaches a host directly
 * at `10.0.2.2`); **debug builds override this to `localhost` in `MainActivity`** for the
 * `adb reverse` emulator test. Override for a real device or the canonical Tailscale host.
 */
actual val platformOidc: PlatformOidc =
    PlatformOidc(
        clientId = "homeflow-android",
        redirectUri = "org.homeflow.mobile:/oauth2redirect",
        defaultHost = "10.0.2.2",
    )
