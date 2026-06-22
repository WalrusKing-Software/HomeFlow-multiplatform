# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/). A changelog
entry answers "what can I do now that I couldn't before?" — not "what files
changed." See `CLAUDE.md` for the rules.

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
