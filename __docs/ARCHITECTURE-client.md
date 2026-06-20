# ARCHITECTURE-client.md — Compose Multiplatform Clients

One Compose Multiplatform codebase (`composeApp`) produces both the **desktop**
(JVM) and **Android** apps. They are parallel clients to the Ktor server: same
Keycloak realm, same single user, same `/api/v1`. The server needs **zero**
client-specific changes — it authenticates stateless `Authorization: Bearer` JWTs.

This is the platform-neutral successor to the original native-Android `ANDROID.md`.
Read it before any work under `composeApp/`. DTOs + domain math come from `:shared`
(`SHARED-MODULE.md`); auth/realm config is in `KEYCLOAK.md`; the API shape in
`API.md` + `openapi.yaml`.

---

## Principles (carried from the Android client)

- **No health data at rest** (current phase). Online-only; in-memory caches only.
  On-device offline cache with its own client-side encryption is a later phase.
- **Tokens in platform secure storage only**, never logged. Access token in memory
  only; refresh/offline token in the OS secure store.
- **No HTTP body logging, ever** — tokens and health payloads must never reach a
  log sink on any platform.
- **PKCE S256** on the OIDC Authorization Code flow, enforced by the client and
  required on the Keycloak client.
- **Anti-screenshot:** `FLAG_SECURE` on Android; best-effort on desktop.
- Reachability is over **Tailscale** using the canonical `*.ts.net` hostname (see
  `DEPLOYMENT.md` §11), not the public internet.

---

## Source layout

```
composeApp/
  src/commonMain/kotlin/org/homeflow/client/
    App.kt                     # root composable: auth gate → signed-in shell
    di/                        # dependency wiring (Koin or manual)
    data/
      HomeFlowApi.kt           # Ktor client calls, typed via :shared DTOs
      HomeFlowRepository.kt    # read/write surface for screens + ref-data label cache
      ApiResult.kt             # Success/Failure wrapper over one backend call
    auth/
      AuthController.kt         # orchestrates login → token → gate → silent refresh
      OidcClient.kt            # expect: platform OIDC (Auth Code + PKCE)
      TokenStore.kt            # expect: platform secure storage for the refresh token
      AppLockGate.kt           # expect: biometric / credential gate on app open
    ui/
      shell/                   # top bar + navigation over the screens
      screens/                 # Dashboard, Day, Cycles, Analytics (+ later: write, settings)
      components/              # SectionCard, KeyValueRow, EmptyHint, Loadable, …
      theme/                   # Material 3 theme (color, type, shapes)
  src/androidMain/kotlin/...   # actual: AppAuth, Keystore/EncryptedSharedPreferences,
                               #         BiometricPrompt, FLAG_SECURE, custom-scheme redirect
  src/desktopMain/kotlin/...   # actual: system-browser + loopback redirect OIDC,
                               #         OS keychain (DPAPI/secret-service/Keychain), packaging
```

`HomeFlowApi` uses the **Ktor client** (multiplatform) with
`ContentNegotiation(kotlinx.serialization)` and the **`:shared` DTOs** directly —
there is no OpenAPI codegen and no hand-maintained model copy.

---

## HTTP client + the auth stack

A single configured Ktor `HttpClient` in `commonMain`:

- `DefaultRequest` sets the base URL from `AuthConfig.apiBaseUrl`.
- An **auth plugin** attaches `Authorization: Bearer <access token>` and proactively
  refreshes a near-expiry token (`Auth`/`bearer` provider with `loadTokens` /
  `refreshTokens`), and reactively refreshes once on a `401`.
- **No** `Logging` plugin at BODY level. If logging is enabled at all in debug, it
  is `LogLevel.NONE`/`INFO` (headers/line only) and redacts `Authorization`.
- Per-platform engine: OkHttp on Android, CIO/Java on desktop (an `expect` factory).

`ApiResult<T>` wraps each call so screens render Loading / Loaded / Error uniformly
(`Loadable` composable), and `ApiError` from `:shared` is pattern-matched on `code`.

---

## Auth flow (platform-specific OIDC, shared orchestration)

Realm-side config (clients, PKCE, audience mapper, `offline_access`, session
lifetimes, WebAuthn RP-ID) is in `KEYCLOAK.md`. `AuthController` in `commonMain`
owns the sequence; `OidcClient`/`TokenStore`/`AppLockGate` are `expect`/`actual`.

1. **One-time login.** Fetch the realm OIDC discovery doc (endpoints never
   hard-coded), then run Authorization Code + PKCE(S256) in the system browser. The
   existing password + passkey 2FA happens there. Scopes: `openid offline_access`.
   - **Android:** AppAuth + Chrome Custom Tab; redirect `org.homeflow.mobile:/oauth2redirect`.
   - **Desktop:** open the system browser to a **loopback** redirect
     (`http://127.0.0.1:<ephemeral-port>/oauth2redirect`) served by a tiny local
     listener that captures the code.
2. **Token exchange + storage.** Exchange the code; persist the **offline refresh
   token** in `TokenStore` (Android Keystore-backed encrypted prefs; desktop OS
   keychain). **Access token stays in memory only.**
3. **App-open gate.** `AppLockGate` requires a strong factor before the stored
   refresh token is used: BiometricPrompt (Android, device-credential fallback);
   OS credential prompt or app passphrase (desktop).
4. **Silent refresh.** The Ktor auth plugin refreshes proactively + once on `401`.
   Full re-login only when the offline session expires or is revoked.
5. **Logout.** Best-effort refresh-token revocation at the realm end-session
   endpoint, then clear `TokenStore` regardless.

---

## Configuration & hostnames

`AuthConfig` (in `commonMain`, with platform overrides as needed) is the single
place the apps key off:

| Constant | Value | Notes |
|---|---|---|
| `HOST` | `homeflow.<tailnet>.ts.net` | drives `apiBaseUrl` and `issuer` (`DEPLOYMENT.md` §11) |
| `REALM` | `homeflow` | |
| `CLIENT_ID` | `homeflow-android` / `homeflow-desktop` | public Keycloak client per platform (`KEYCLOAK.md`) |
| `REDIRECT_URI` | custom scheme (Android) / loopback (desktop) | must match the Keycloak client |
| `SCOPES` | `openid offline_access` | `offline_access` = long-lived session |

> **Android emulator** reaches the dev host at `10.0.2.2`, not `localhost`. For
> end-to-end auth the hostname + trusted cert must line up (same `iss`/RP-ID
> constraint as everywhere) — prefer reaching the full stack over Tailscale.

---

## Per-platform responsibilities (`expect`/`actual`)

| Concern | `androidMain` | `desktopMain` |
|---|---|---|
| OIDC | AppAuth + Custom Tab, custom-scheme redirect | system browser + loopback listener |
| Token storage | Keystore-backed `EncryptedSharedPreferences` | OS keychain (DPAPI / secret-service / macOS Keychain) |
| App-open gate | `BiometricPrompt` | OS credential prompt / passphrase |
| Anti-screenshot | `FLAG_SECURE` | best-effort / N/A |
| HTTP engine | OkHttp | CIO or Java engine |
| Packaging | AAB via `bundleRelease` | `packageDmg` / `packageMsi` / `packageDeb` |

Keep `commonMain` free of platform APIs — anything touching the Keystore, a
browser, biometrics, or a window flag goes behind an `expect`/`actual`.

---

## Build & run

- **Gradle root is the repo root** (KMP multi-module: `:shared`, `:server`,
  `:composeApp`). Versions managed in `gradle/libs.versions.toml`.
- Desktop run: `./gradlew :composeApp:run`. Package:
  `./gradlew :composeApp:packageDistributionForCurrentOS`.
- Android: `./gradlew :composeApp:assembleDebug`; run on an emulator (API 35/36)
  or device.

---

## Security posture (v1) — checklist

- [ ] No health data persisted on device (in-memory only)
- [ ] Refresh token only in platform secure storage; access token in memory only
- [ ] No BODY-level HTTP logging on any platform; `Authorization` never logged
- [ ] PKCE S256 enforced client-side and required on the Keycloak client
- [ ] `FLAG_SECURE` (Android) / best-effort screenshot block (desktop)
- [ ] Refresh-token use gated behind a strong factor on app open
- [ ] Logout revokes the refresh token and clears secure storage

---

## Roadmap

- **Phase 1 — Auth spike:** login → secure store → app-lock gate → silent refresh
  → `GET /api/v1/users/me`, on both platforms.
- **Phase 2 — Read MVP:** dashboard (current cycle + today + at-a-glance), day view
  (categories resolved to labels + pain + notes), cycle list, analytics.
- **Phase 3 — Write MVP:** full daily logging, cycle start/close, preferences.
- **Phase 4 — Polish & release:** settings, account deletion, error UX, signed
  desktop installers + Android AAB.
- **Future:** offline read-cache with on-device encryption → full offline + sync;
  finalize mobile/desktop 2FA (native passkey via Credential Manager vs TOTP).
