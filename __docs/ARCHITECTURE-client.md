# ARCHITECTURE-client.md — Compose Multiplatform Clients

The client lives across three modules — **`:app:shared`** (shared Compose UI +
logic, with per-platform `androidMain`/`jvmMain` source sets), **`:app:androidApp`**
and **`:app:desktopApp`** (thin entry points) — producing both the **desktop**
(JVM) and **Android** apps from one codebase. They are parallel clients to the Ktor
server: same Keycloak realm, same single user, same `/api/v1`. The server needs
**zero** client-specific changes — it authenticates stateless `Authorization:
Bearer` JWTs.

This is the platform-neutral successor to the original native-Android `ANDROID.md`.
Read it before any work under `app/`. DTOs + domain math come from `:core`
(`SHARED-MODULE.md`); auth/realm config is in `KEYCLOAK.md`; the API shape in
`API.md` + `openapi.yaml`.

---

## Principles (carried from the Android client)

- **No health data at rest** (remote-only mode). Online-only; in-memory caches
  only. Phase 13 adds a local SQLDelight store with whole-DB SQLCipher encryption
  for local-only and offline-capable modes (see below).
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
app/
  shared/                              # the :app:shared module (Compose lib); pkg org.homeflow.app.shared
    src/commonMain/kotlin/org/homeflow/app/shared/
      App.kt                   # root composable: auth gate → signed-in shell
      di/                      # dependency wiring (Koin or manual)
      data/
        HomeFlowDataSource.kt  # seam interface the repository renders off
        RemoteDataSource.kt    # HTTP impl: Ktor client calls, typed via :core DTOs
        HomeFlowRepository.kt  # read/write surface for screens + ref-data label cache
        ApiResult.kt           # Success/Failure wrapper over one backend call
        local/                 # Phase 13: local persistence engine (see below)
          LocalDataSource.kt   # HomeFlowDataSource impl backed by SQLDelight
          LocalDatabaseFactory.kt  # expect: opens encrypted HomeFlowDb
          LocalBootstrap.kt    # seeds ref data + users row on first open
          LocalKeyStore.kt     # expect: DEK in platform secure store
          RefSeed.kt           # bundled ref-data constant (slugs + labels)
          LocalCyclesStore.kt  # cycle CRUD + auto-close rule
          LocalDailyLogsStore.kt  # daily-log anchor + pain
          LocalSubsStore.kt    # symptom sub-log replace (multi/single/sex payload)
          LocalPrefsStore.kt   # dashboard preference ordering
          LocalAnalytics.kt    # cycle stats + chart + ovulation + sleep (calls :core)
          LocalRefData.kt      # ref-table reader + slug→id helpers
      auth/
        AuthController.kt       # orchestrates login → token → gate → silent refresh
        OidcClient.kt          # expect: platform OIDC (Auth Code + PKCE)
        TokenStore.kt          # expect: platform secure storage for the refresh token
        LocalKeyStore.kt       # expect: platform secure storage for the local DEK
        AppLockGate.kt         # expect: biometric / credential gate on app open
      ui/
        shell/                 # top bar + navigation over the screens
        screens/               # Dashboard, Day, Cycles, Analytics (+ later: write, settings)
        components/            # SectionCard, KeyValueRow, EmptyHint, Loadable, …
        theme/                 # Material 3 theme (color, type, shapes)
    src/androidMain/kotlin/... # actual: AppAuth, Keystore/EncryptedSharedPreferences,
                               #         BiometricPrompt, FLAG_SECURE, custom-scheme redirect
                               #         AndroidSqliteDriver + SQLCipher SupportFactory
    src/jvmMain/kotlin/...     # actual: system-browser + loopback redirect OIDC,
                               #         OS keychain (DPAPI/secret-service/Keychain)
                               #         JdbcSqliteDriver + willena SQLCipher
    src/commonMain/sqldelight/org/homeflow/app/shared/db/
                               # SQLDelight schema (.sq files) — one per logical group:
                               # Users, Cycles, DailyLogs, DailyLogSubs, PainLogs,
                               # Preferences, RefData, SyncOutbox
  androidApp/                  # the :app:androidApp module — MainActivity, manifest (pkg org.homeflow)
  desktopApp/                  # the :app:desktopApp module — main() (org.homeflow.MainKt) + packaging
```

`HomeFlowRepository` depends on the `HomeFlowDataSource` interface, not directly on HTTP.
`RemoteDataSource` is the HTTP implementation of that interface: it uses the **Ktor
client** (multiplatform) with `ContentNegotiation(kotlinx.serialization)` and the
**`:core` DTOs** directly — there is no OpenAPI codegen and no hand-maintained model
copy. `LocalDataSource` (Phase 13) implements the same interface against the SQLDelight
local store.

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
(`Loadable` composable), and `ApiError` from `:core` is pattern-matched on `code`.

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

---

## Local store (Phase 13)

Phase 13 introduces a complete local persistence engine in `:app:shared/data/local/`.
This is the foundation for Mode A (local-only) and Mode C (offline sync). Key facts:

### Database

- **Engine:** SQLDelight 2.0.x with platform-specific drivers.
  - Android: `AndroidSqliteDriver` + SQLCipher `SupportOpenHelperFactory`.
  - Desktop (JVM): `JdbcSqliteDriver` + willena SQLite+SQLCipher JDBC driver.
- **Encryption:** Whole-database SQLCipher (D-13.2). The DEK is passed to
  `LocalDatabaseFactory.create(dek)` and consumed there. No column-level
  encryption — all health data is plaintext *inside* the encrypted DB. The sex
  payload is stored as a plaintext JSON array of option-id strings in
  `daily_log_sex.payload` (server-side encryption is a server concern only).
- **Schema location:** `src/commonMain/sqldelight/org/homeflow/app/shared/db/`
- **Generated package:** `org.homeflow.app.shared.db`, database class `HomeFlowDb`.

### DEK lifecycle

`LocalKeyStore` (expect/actual, mirrors `TokenStore`) stores the 32-byte DEK
(Base64-encoded) in platform secure storage:
- Android: Keystore-backed `EncryptedSharedPreferences`.
- Desktop: OS keychain via java-keyring.

`LocalDatabaseFactory` takes the DEK as `ByteArray` and opens the encrypted DB.
Phase 14 wires `LocalKeyStore → LocalDatabaseFactory → LocalDataSource` at the
composition root. Phase 13 just provides the pieces; tests construct the DB directly.

### Seeding and bootstrap

`LocalBootstrap.seed(db)` seeds the `ref_*` tables and the single `users` row on
first open. It is idempotent (count-guard on `ref_symptom_categories`). Reference
data is bundled in `RefSeed` (slugs → labels → options/locations). Local UUIDs are
`kotlin.uuid.Uuid.random().toString()` — they differ per device, which is why slugs
are used at every cross-store boundary (D4 in the spec).

### Business rules

`LocalDataSource` re-implements the same rules as the server's service layer using
`:core` shared functions (D7):

| Rule | `:core` function |
|---|---|
| Cycle auto-close on new cycle | `autoCloseEndDate` |
| Cycle start validation | `validateCycleStart` |
| Cycle end validation | `validateCycleEnd` |
| Daily-log within-cycle date | `validateDailyLogWithinCycle` |
| Notes length | `validateNotes` |
| Pain location dedup + severity | `validatePainLocations` |
| Preference category order | `validateCategoryOrder` |
| Option-in-category validation | inlined in `LocalSubsStore` (not extracted to `:core`) |

### Timestamps

All ISO-8601 timestamps written to the DB use `kotlin.time.Clock.System.now().toString()`.
Never `kotlinx.datetime.Clock`. Dates use `kotlinx.datetime.LocalDate.toString()` (ISO `yyyy-MM-dd`).
UUIDs use `kotlin.uuid.Uuid.random().toString()` (stored as TEXT).

### Sync placeholders

`sync_outbox`, `updated_at`, and `deleted_at` columns exist on every syncable table
but are NOT exercised in Phase 13. Phase 15 activates them.

### Tests

All local store tests live in `jvmTest` (SQLCipher native libs won't load in Android
host tests). Tests use one of two helper patterns:
- **In-memory (no encryption):** `JdbcSqliteDriver(IN_MEMORY)` + `Schema.create` via
  `TestDbHelper.inMemory()` — for contract, parity, analytics, and seed tests.
- **File-based (encrypted):** willena driver with a temp file + DEK — for
  `LocalEncryptionAtRestTest` only.

---

## Mode A — local-only app (Phase 14)

Phase 14 delivers the first user-visible deployment mode: the app runs **with no
server and no Keycloak**, all data in the Phase 13 encrypted local store.

### AppMode + AppModeStore

`AppMode { LOCAL_ONLY, SERVER }` is persisted in a **non-encrypted** store
(`AppModeStore`) so it can be read before the app-lock gate is satisfied
(and before the DEK is available):

- **Desktop:** `~/.homeflow/mode.properties` (plain `java.util.Properties`)
- **Android:** plain `SharedPreferences` (key `"app_mode"`)

`AppModeStore` is `null` on first run → `AppRoot` shows the one-time chooser.

### AppRoot — composition root

`AppRoot` (in `commonMain`) replaces the old single-mode `App()` entry point in
both `main.kt` and `MainActivity`:

```
AppRoot
  ├─ mode == null   → ModeChooserScreen (first run)
  ├─ mode == LOCAL  → LocalSessionController + App(controller, onExport)
  └─ mode == SERVER → AuthController (Keycloak OIDC) + App(controller)
```

After `deleteAccount()` in Mode A, the controller reaches `AuthState.LoggedOut`
and `AppModeStore.clear()` is called; `AppRoot`'s `LaunchedEffect` detects the
cleared mode and re-shows the chooser.

### SessionController — shared interface

`SessionController` is the interface both `AuthController` (Mode B) and
`LocalSessionController` (Mode A) implement, so `AppRoot` and `App` remain
mode-agnostic:

```kotlin
interface SessionController {
    val state: StateFlow<AuthState>
    val usesPassphraseGate: Boolean
    fun start()
    suspend fun login()
    suspend fun enroll(secret: String)
    suspend fun unlock(secret: String? = null)
    suspend fun logout()
    suspend fun deleteAccount(): ApiResult<Unit>
}
```

`AuthState.Authenticated` now carries `val repository: HomeFlowRepository` so
the UI never needs a separate "repository only valid after unlock" reference.

### LocalSessionController — Mode A controller

`LocalSessionController` constructs a `HomeFlowDb` from the DEK (via
`LocalDatabaseFactory`), seeds it on first open, and transitions the state
machine through `Locked → Authenticating → Authenticated`. It has **no
`HttpClient`** — network-free by construction.

State machine:
- `start()` → `Locked(needsEnrollment = gate.needsEnrollment())`
- `enroll(secret)` → `gate.enroll` then `unlock(secret)`
- `unlock(secret)` → `Authenticating` → gate pass → DEK → DB → seed →
  `Authenticated(localUser, repository)`
- `logout()` → drop DB handle → `Locked`
- `deleteAccount()` → `ds.deleteAccount()` → clear DEK + enrollment + mode →
  `LoggedOut`

### at-rest encryption (Mode A exception)

> The "No health data at rest on clients" principle applies only to **remote-only
> mode (Mode B)**. Mode A explicitly stores all health data locally — **in a
> whole-database SQLCipher-encrypted file** with the DEK held in the OS secure
> store (identical to the Phase 13 infrastructure). This is the intended trade-off,
> documented here so it doesn't look like a gap.

### Export (Mode A)

`LocalSessionController.exportData()` serializes a `HomeFlowExport` from the
local store using `LocalExporter` (which maps option/location UUIDs → slugs via
`LocalRefData`), serializes it to JSON, and invokes the platform save-file dialog
(`writeExportFile` — `expect`/`actual`):
- **Desktop:** AWT `FileDialog` (save mode)
- **Android:** SAF `ACTION_CREATE_DOCUMENT` via `AndroidAppContext.exportLauncher`

### SecureRandom

`secureRandomBytes(size)` (`expect`/`actual`) generates cryptographically secure
random bytes. Both platforms use `java.security.SecureRandom`. The DEK is generated
once via `LocalKeyStore.loadOrCreateDek()` = `loadDek() ?: secureRandomBytes(32).also { saveDek(it) }`.

---

## UI structure

```
HomeFlowRepository
   └─ HomeFlowDataSource (interface)
        ├─ RemoteDataSource  — HTTP/Ktor (Mode B: server-connected)
        └─ LocalDataSource   — SQLDelight/SQLCipher (Mode A: local-only)
```

`AppRoot` wires the mode-appropriate `SessionController` and passes
`repository` from `AuthState.Authenticated` into the screens. No screen
needs to know which mode it is in.

---

## Building + running locally

- `./gradlew :app:shared:compileCommonMainKotlinMetadata` — compile shared sources
  (SQLDelight codegen runs first).
- `./gradlew :app:shared:jvmTest` — run local-store unit tests (in-memory SQLite,
  no SQLCipher native needed for most tests).
- `./gradlew build` — build all modules (`:core`, `:server`, `:app:shared`,
  `:app:androidApp`, `:app:desktopApp`). Versions managed in
  `gradle/libs.versions.toml`.
- Desktop run: `./gradlew :app:desktopApp:run` (hot reload:
  `./gradlew :app:desktopApp:hotRun --auto`). Package:
  `./gradlew :app:desktopApp:packageDistributionForCurrentOS`.
- Android: `./gradlew :app:androidApp:assembleDebug`; run on an emulator (API 35/36)
  or device.

---

## Security posture (v1) — checklist

Verified at Phase 10 (hardening). Evidence in parentheses.

- [x] No health data persisted on device (in-memory only, remote-only mode) —
  `HomeFlowRepository` caches only ref-data labels in memory; no DB/file persistence
  of health data in remote-only mode. **Phase 13 adds local persistence with
  whole-DB SQLCipher encryption; the DEK is held in platform secure store only.**
- [x] Refresh token only in platform secure storage; access token in memory only —
  `TokenStore` (Keystore-backed prefs / OS keychain) holds the refresh token; the access
  token lives in the in-memory `TokenHolder` only.
- [x] No BODY-level HTTP logging on any platform; `Authorization` never logged — no Ktor
  `Logging` plugin is installed on either engine (`HttpClientFactory`).
- [x] PKCE S256 enforced client-side and required on the Keycloak client — `Pkce.challenge`
  is `BASE64URL(SHA-256(verifier))`; clients send `code_challenge_method=S256`.
- [x] `FLAG_SECURE` (Android) / best-effort screenshot block (desktop) — `MainActivity` sets
  `FLAG_SECURE`. Desktop has no OS-portable capture exclusion; treated as best-effort/N/A.
- [x] Refresh-token use gated behind a strong factor on app open — `AppLockGate`
  (BiometricPrompt on Android; passphrase/credential prompt on desktop) runs before the
  stored refresh token is used.
- [x] Logout revokes the refresh token and clears secure storage — `AuthController.logout`
  best-effort revokes at the end-session endpoint then clears `TokenStore`/`TokenHolder`.
- [x] Account deletion removes server data + the Keycloak identity, then clears local
  storage — `DELETE /users/me` via the Settings danger zone; `AuthController.deleteAccount`
  drops to the login screen on success.
- [x] **Phase 14:** Local DEK cleared on `deleteAccount()` — `LocalSessionController`
  calls `LocalKeyStore.clearDek()` + `resetAppLockGateEnrollment()` + `AppModeStore.clear()`
  on success, then transitions to `AuthState.LoggedOut`.

---

## Roadmap

- **Phase 1 — Auth spike:** login → secure store → app-lock gate → silent refresh
  → `GET /api/v1/users/me`, on both platforms.
- **Phase 2 — Read MVP:** dashboard (current cycle + today + at-a-glance), day view
  (categories resolved to labels + pain + notes), cycle list, analytics.
- **Phase 3 — Write MVP:** full daily logging, cycle start/close, preferences.
- **Phase 4 — Polish & release:** settings, account deletion, error UX, signed
  desktop installers + Android AAB.
- **Phase 13 — Local persistence engine:** SQLDelight + SQLCipher whole-DB encryption;
  `LocalDataSource` implements `HomeFlowDataSource`; jvmTest contract + analytics tests.
- **Phase 14 — Local-only app mode (Mode A):** DI wiring, `LocalKeyStore` → DEK generation,
  mode picker, `deleteAccount` DEK clearing.
- **Phase 15 — Server-connected + migration (Mode B):** adopt-a-server flow, slug-keyed
  export/import, `RemoteDataSource` remains the default for server-connected users.
- **Phase 16 — Offline sync (Mode C):** `SyncEngine`, outbox drain, pull cursor, LWW.
