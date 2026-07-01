# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/). A changelog
entry answers "what can I do now that I couldn't before?" — not "what files
changed." See `CLAUDE.md` for the rules.

<!-- Section header formats (used by the release pipeline awk extractor):
     Combined release:  ## [X.Y.Z] - YYYY-MM-DD
     Server only:       ## [Server X.Y.Z] - YYYY-MM-DD
     Desktop only:      ## [Desktop X.Y.Z] - YYYY-MM-DD
     Android only:      ## [Android X.Y.Z] - YYYY-MM-DD
-->

## [Unreleased] Version 0.1.0 — [Release-Date]

Initial Kotlin Multiplatform rebuild of HomeFlow (desktop + Android, self-hosted
Ktor server). Pre-implementation: documentation and specification only.

### Added
- Project documentation ported and adapted from the HomeFlow web repo for the
  Kotlin-everywhere architecture (server, client, shared-module, testing,
  phases, Docker, deployment, branching).
- **Shared `:core` contract (Phase 1).** The API contract and domain logic now
  exist once and compile into the Ktor server and both Compose clients:
  - Serializable DTOs for every API resource — users, cycles, daily logs (full
    day log, pain, the anchor, and every per-category request/response shape),
    analytics, preferences, and reference data — replacing the old OpenAPI codegen.
  - The canonical `ApiError` / `ErrorBody` / `ErrorCode` error model.
  - Deduplicated cycle/analytics domain math: cycle-day number, (inclusive) cycle
    length, phase prediction, cycle stats, period-length chart, ovulation
    prediction, and sleep-by-phase bucketing — the web app's two divergent
    ovulation windows reconciled into one definition.
  - Shared validation rules (cycle dates, daily-log-within-cycle, notes length,
    pain severity, dashboard category order) run by both client and server.
- **Database schema & reference data (Phase 2).** A fresh Postgres can now be
  migrated to the full HomeFlow schema with reference data ready to query:
  - Flyway migration `V1__initial_schema.sql` creates every table, constraint,
    and index from the data model — users, cycles, the daily-log anchor, all
    symptom sub-logs (including the single encrypted `daily_log_sex` payload),
    pain logs/locations, the reference tables, and dashboard preferences.
  - Idempotent Flyway migration `V2__seed_ref_data.sql` seeds all symptom
    categories/options and pain regions/locations (re-running is a no-op).
  - Exposed `db/Tables.kt` mirrors the schema for type-safe queries, and
    `config/Database.kt` connects the server over a HikariCP pool as the
    restricted runtime app role.
- **Server authentication & user bootstrap (Phase 3).** The Ktor server now
  authenticates every API request against Keycloak and manages the single
  account:
  - All `/api/v1` routes require a valid Keycloak JWT: the signature (RS256, via
    cached JWKS), `iss`, `aud` (`homeflow-backend`), and `exp` are validated, and
    a missing, expired, tampered, or wrong-audience token is rejected with `401`
    in the canonical `ApiError` shape.
  - First login transparently creates the app-side user record (keyed on the
    Keycloak `sub`), so repeated logins never duplicate it.
  - `GET /api/v1/users/me` returns the current user's internal record, and
    `DELETE /api/v1/users/me` permanently deletes all of the user's data (one
    cascading delete) and then the Keycloak identity.
  - `GET /health` responds without auth for container/proxy probes.
  - Fail-fast configuration (`config/Config.kt`, `config/KeycloakConfig.kt`)
    validates the environment on startup, errors are returned through a single
    typed-error handler, and a coarse global request rate limit is applied.
  - The Keycloak realm now enforces **password + WebAuthn-passkey 2FA**: the
    `browser-with-passkey` flow is bound as the browser flow and new users are
    prompted to register a passkey on first login (`webauthn-register` default
    action).
- **Server cycles & daily-log anchor (Phase 4).** The server can now record and
  read cycles and the per-day log anchor:
  - Full cycle lifecycle over `/api/v1/cycles`: list (newest first), start a new
    cycle, fetch the current open cycle or any cycle by id, and close a cycle.
    Starting a new cycle automatically closes the previous open one (its end date
    becomes the new start minus a day), and a future start date is rejected.
  - `POST /api/v1/daily-logs` creates the per-day anchor after checking the cycle
    belongs to you and the date falls within it, and returns `409` if a log
    already exists for that day; `GET /api/v1/daily-logs/:date` returns the day
    (symptom categories arrive in Phase 5) or `404` when none exists.
  - `PATCH /api/v1/daily-logs/:date/notes` stores free-text notes encrypted with
    AES-256-GCM at the application layer (ciphertext at rest) and returns them
    decrypted; passing `null` clears them.
  - Every cycle and daily-log query is scoped to the authenticated user, so
    another user's cycle or log is reported as not-found, never revealed.
- **Server symptom sub-logs & reference data (Phase 5).** A day can now be fully
  tracked and read back:
  - A `PUT` route for every symptom category — `emotions`, `sleep`, `energy`,
    `sex`, `discharge`, `skin`, `digestion`, `flow`, `collection`, and `mind` —
    replaces the day's selections in one call; sending an empty array (or `null`
    for the single-select categories) clears them. Submitted option IDs are
    validated against that category's reference data, so an unknown ID or one from
    the wrong category is rejected with `400`.
  - `PUT /api/v1/daily-logs/:date/pain` replaces the day's pain log — each
    selected location with its own 1–10 severity (or unrated) — and an empty list
    clears it; duplicate locations, unknown locations, and out-of-range severities
    are rejected.
  - Sex selections are stored encrypted with AES-256-GCM at the application layer
    (ciphertext at rest) and returned decrypted, the same as notes.
  - `GET /api/v1/daily-logs/:date` now returns the fully assembled day with every
    logged category resolved; a category with nothing logged reads back as null.
  - `GET /api/v1/ref-data/symptom-categories` and `GET /api/v1/ref-data/pain-regions`
    return the seeded reference data (categories with their options; regions with
    their locations) that the clients resolve labels against.
- **Server analytics & preferences (Phase 6) — the server API is now
  feature-complete.** Your history can now be summarised and your dashboard
  arranged:
  - `GET /api/v1/analytics/cycle-stats` returns your average cycle length, cycle
    length variation, and average period length; `GET /api/v1/analytics/period-length-chart`
    returns one bleeding-day data point per closed cycle (oldest first);
    `GET /api/v1/analytics/ovulation-prediction` projects the next three period
    starts and ovulation dates from your most recent cycle start; and
    `GET /api/v1/analytics/sleep-predictions` reports the most common sleep
    options for each cycle phase. Analytics always answer with `200` — when there
    isn't enough history yet (e.g. fewer than two closed cycles, or a phase with
    too few logged days) the affected fields come back `null` rather than an error.
  - `GET /api/v1/preferences` returns your dashboard category order, defaulting to
    the reference category order until you save your own; `PUT /api/v1/preferences`
    saves a new order, which must list every category exactly once (a missing,
    unknown, or duplicated slug is rejected).
- **Client auth spike — desktop & Android (Phase 7).** The Compose apps can now
  sign you in and reach the server:
  - Log in through Keycloak with Authorization Code + PKCE (S256) in the system
    browser (desktop, via a loopback redirect listener) or a Chrome Custom Tab
    (Android, via AppAuth) — reusing the existing password + passkey 2FA — then
    load your account with `GET /api/v1/users/me`.
  - The long-lived offline refresh token is stored only in OS-secure storage
    (Android Keystore-backed encrypted prefs; desktop OS keychain), the access
    token is kept in memory only, and the access token is refreshed silently
    (reactively on a `401`).
  - Re-opening the app requires a strong factor before the stored session is used:
    a biometric/device-credential prompt on Android, an app passphrase on desktop.
  - Logging out best-effort revokes the refresh token at Keycloak and clears
    secure storage; Android additionally blocks screenshots/recents with
    `FLAG_SECURE`. No HTTP request/response bodies or tokens are ever logged.
- **Client read MVP — desktop & Android (Phase 8).** Once signed in, the apps now
  show your data across four screens, sharing one codebase on both platforms:
  - A **Dashboard** with your current cycle, the cycle day and predicted phase
    (computed with the shared `:core` `predictPhase`), at-a-glance averages, and
    today's log.
  - A **Day** view with a date stepper that resolves every tracked category to its
    labels, plus pain locations (with severity) and notes.
  - A **Cycles** list showing every cycle newest-first with its derived length and
    open/closed status.
  - An **Analytics** view: cycle stats, the per-cycle period-length chart,
    ovulation predictions, and most-common sleep options by phase.
  - Reference-data labels are fetched once and cached in memory; thin/absent data
    (no open cycle, nothing logged, under two cycles) renders calm empty states
    rather than errors, and every screen surfaces a retry on failure. No health
    data is persisted on the device.
- **Client write MVP — desktop & Android (Phase 9).** The apps can now record and
  change data, not just read it:
  - **Log a day.** The Day view has an editor that pre-populates the day's current
    selections and lets you toggle every symptom category — multi-select chips
    (emotions, sleep, sex, discharge, skin, digestion, mind), single-select cards
    (energy, blood flow, collection method), pain locations with a per-location
    1–10 severity (or "unrated"), and free-text notes. Saving creates the day's
    anchor automatically and pushes only what changed; emptying a category clears
    it server-side.
  - **Manage cycles.** Start a new cycle from the Cycles tab (which auto-closes the
    previous open one) or close the open cycle by setting its end date — both with
    inline date validation that blocks future/out-of-order dates.
  - **Reorder tracking categories.** A new Settings tab lets you rearrange the order
    your categories appear when logging, and the saved order is applied to the day
    editor.
- **Hardening & release prep (Phase 10).** The clients are now ready to package and
  ship, with the release-facing rough edges smoothed:
  - **Delete your account from the app.** A danger zone in Settings permanently
    deletes your account and all tracked data after a type-to-confirm prompt; on
    success the app clears its local session and returns to the login screen.
  - **Friendlier errors.** Failures now show calm, actionable messages instead of
    raw error codes (e.g. an expired session or a lost connection), while never
    surfacing server internals.
  - **Signed Android release builds.** `:app:androidApp:bundleRelease` produces a
    signed AAB when a (gitignored) `keystore.properties` is present, and an unsigned
    one otherwise — signing secrets never enter the build script or VCS
    (`keystore.properties.example` shows the format).
  - **Polished desktop installers.** The desktop distribution now carries a proper
    app name, vendor, description, Windows menu group, and a stable MSI upgrade UUID.
- **Work offline; your devices sync through your server when reconnected (Phase 16 — Mode C).**
  Once you connect to your self-hosted server, the app now works offline-first: all data lives
  on the device, and changes reconcile automatically whenever your server is reachable:
  - **Offline-first data source.** Mode B (server-connected) is now Mode C by default. The
    local SQLCipher-encrypted database replaces the remote data source as the primary store;
    every read and write hits the local DB instantly without a network round-trip.
  - **Automatic background sync.** `SyncEngine.syncNow()` runs on app foreground and every
    15 minutes while open. It pushes your pending local changes to the server, then pulls
    server changes (from other devices) and reconciles them locally — all without touching the UI.
  - **Last-Write-Wins merge.** Each aggregate (cycle, day, preferences) carries an
    `updatedAt` timestamp; the newer version wins a conflict. Same-timestamp ties break by
    UUID string comparison — deterministic and consistent on both devices. The merge rule
    (`mergeDecision()` in `:core`) is pure and tested.
  - **Sync status indicator.** A small label in the top bar (Syncing / Synced / Sync error)
    shows the current sync state, visible only in Mode C.
  - **Server: soft-delete + change-log.** Every cycle and day write records a row in
    `sync_changes`. Deleting a cycle or day sets a `deleted_at` tombstone and cascades to
    sub-logs; live reads continue to filter `deleted_at IS NULL`. The server exposes
    `GET /api/v1/sync/changes` (cursor-based pull) and `POST /api/v1/sync/changes` (push).
  - **Delete cycles and days.** A "Delete cycle" button appears on each cycle card in the
    Cycles tab; a "Delete day" button appears on the Day tab when a log exists. Both require
    a tap-to-confirm dialog. Deletes are soft (tombstoned) so they propagate via sync.
  - **Client outbox.** Every local write (create, update, delete) appends an entry to a
    `sync_outbox` table. The engine drains the outbox on each push, de-duplicating by entity.
- **Connect the app to your self-hosted server and upload your local data to it (Phase 15 — Mode B).**
  You can now point the app at your self-hosted server at runtime, log in once, and your
  data becomes available across all your devices:
  - **Runtime server configuration.** Tap "Connect to a server" (in the first-run chooser
    or Settings) and enter your server hostname (e.g. `myhost.ts.net`). The host is
    remembered; subsequent launches go straight to the Keycloak login.
  - **"Adopt a server" migration.** If you have existing local data (Mode A), the app
    detects it after login and prompts you to upload it. One tap sends your entire history
    to the server via `POST /api/v1/import?source=homeflow` (same endpoint as the server
    import feature). Re-running the upload is safe — existing data is never duplicated.
  - **Second device.** A fresh Android or desktop install that connects to the same server
    immediately sees all the uploaded data.
  - The migration can also be triggered at any time from Settings ("Upload local data to
    server") while it has not been confirmed. The local database is never deleted, serving
    as a backup and as the future Mode C offline cache.
- **Use HomeFlow entirely on one device — no server required (Phase 14 — Mode A).**
  Both the desktop and Android apps now support a **Local-only mode**: all data
  stays on your device, encrypted at rest, with no server or Keycloak needed:
  - On first launch a **mode chooser** appears: "Use this device only" or
    "Connect to a server". The choice is remembered; clearing it (via account
    deletion) resets back to the chooser.
  - In Local mode the full app — dashboard, day logging, cycle management,
    analytics, preferences — works offline forever. The data source is the
    Phase 13 SQLCipher-encrypted local store.
  - An **Export my data** button in Settings serializes your entire history into
    a portable `homeflow-export.json` (slug-keyed, UUID-free, same format as the
    server's `GET /api/v1/export`) and opens a system save dialog. Desktop uses
    AWT's FileDialog; Android uses the Storage Access Framework.
  - **Deleting your account** in Local mode wipes the database DEK from the OS
    secure store (making the encrypted database unreadable), clears the
    app-lock passphrase/biometric enrollment, and returns to the first-run
    chooser.
- **Local persistence engine (Phase 13).**
  The apps ship a complete on-device storage layer backed by SQLDelight 2.0 with
  whole-database SQLCipher encryption — the foundation for Mode A and future
  offline sync (Mode C):
  - An encrypted local database (`homeflow_local.db`) stores cycles, daily logs
    (including all symptom selections, pain, notes, and the sex tracking payload),
    and dashboard preferences — the full data model, not a cache.
  - The 32-byte data-encryption key (DEK) is stored exclusively in OS-level
    secure storage (Android Keystore / desktop OS keychain) and never written to
    disk in plaintext.
  - All domain rules from the server (cycle auto-close, daily-log-within-cycle
    date validation, option-in-category validation, etc.) are re-implemented using
    shared `:core` functions so Mode A behaves identically to the server mode.
- **Export and import your data (Phase 12).** `GET /api/v1/export` downloads all of
  your cycles and daily logs as a portable `homeflow` backup file (decrypted notes
  and sex data, every option/location identified by a stable slug instead of a
  database-specific id); `POST /api/v1/import?source=homeflow` restores or merges
  one back in. Re-importing the same file is a no-op — existing days are never
  duplicated or overwritten, and unrecognized values are skipped and reported back
  in a `warnings` list rather than failing the whole import.

- **Release pipeline.** Pushing a `vX.Y.Z` tag (or running manually via
  `workflow_dispatch`) now builds and publishes all three deliverables to a GitHub
  Release in one automated pipeline: the server distribution (`.zip` + `.tar.gz`) and
  a multi-arch (`amd64` + `arm64`) Docker image pushed to GHCR, desktop installers
  for all three platforms (Windows `.msi`, macOS `.dmg`, Linux `.deb`), and a signed
  Android APK + AAB. Pre-release tags (`-alpha.N`, `-rc.N`) produce a GitHub
  pre-release and do not move the Docker `:latest` tag.
- **Setup guides attached to every release.** Each GitHub Release now includes
  Markdown setup guides alongside the downloadable artifacts: `SETUP-SERVER.md`
  (step-by-step server deployment on a Raspberry Pi with Tailscale or LAN TLS),
  `SETUP-DESKTOP.md` (install and connect the desktop app on Windows/macOS/Linux),
  and `SETUP-ANDROID.md` (sideload and connect the Android APK). Component-only
  releases (server/desktop/android tags) include only the relevant guide;
  lockstep `vX.Y.Z` releases include all three.

### Fixed
- **Android unlock button.** Tapping "Unlock" on the lock screen silently returned
  to the same screen with no feedback when biometrics/device credential weren't
  enrolled, or when the biometric prompt errored — the gate still fails closed,
  but the failure now surfaces as a visible error message instead of looking like
  a dead button.

### Changed
- The client security checklist in `ARCHITECTURE-client.md` is verified and ticked
  (no health data at rest, tokens in secure storage, no body logging, PKCE S256,
  `FLAG_SECURE`, gated refresh, revoke-on-logout, account deletion).
