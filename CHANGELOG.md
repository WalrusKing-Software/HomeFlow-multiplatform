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
