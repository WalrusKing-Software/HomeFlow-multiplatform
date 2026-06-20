# ARCHITECTURE-server.md — Ktor Backend

The HomeFlow server is a **Kotlin / Ktor** application that exposes the `/api/v1`
contract the desktop and Android clients consume. It is a ground-up port of the
original Fastify/Node backend; the **layering discipline and security invariants
carry over unchanged**, only the technology is different. Read this before any
work under `server/`.

> Spec sources: `data-model.md` (schema), `API.md` (routes), `KEYCLOAK.md`
> (auth), `threat-model.md` (security). DTOs and domain math come from `:shared`
> — see `SHARED-MODULE.md`. This doc covers how the server is wired.

---

## Stack

| Concern | Choice | Notes |
|---|---|---|
| HTTP framework | **Ktor** (Netty engine) | Coroutine-based; runs on the Pi's JVM |
| Serialization | **kotlinx.serialization** | `ContentNegotiation` + the `:shared` DTOs — no codegen |
| DB access | **Exposed** (JetBrains, DSL) | Type-safe SQL builder; the Kysely analogue |
| Connection pool | **HikariCP** | |
| Migrations | **Flyway** | SQL files in `server/src/main/resources/db/migration/`, run manually |
| Auth | `ktor-server-auth` + `ktor-server-auth-jwt` | RS256 JWKS validation against Keycloak |
| Crypto | `javax.crypto` (AES/GCM/NoPadding) | application-layer column encryption |
| Logging | Logback + `kotlin-logging` | PII filters (see *Logging*) |
| Config | typed object, validated on startup | no scattered `System.getenv` |

> **Why Exposed over SQLDelight for the server:** the server builds dynamic,
> user-scoped queries and never shares its data-access layer with clients (clients
> hit HTTP, not Postgres). Exposed's DSL fits that. SQLDelight was considered but
> its value (compile-checked SQL shared across KMP) doesn't apply to a JDBC-only
> server. Revisit only if a query layer ever needs to be shared.

---

## Module / package layout

```
server/
  src/main/kotlin/org/homeflow/server/
    Application.kt              # Ktor entry point — installs plugins, wires modules
    config/
      Config.kt                # env loading + validation; fails fast on misconfig
      Database.kt              # HikariCP DataSource + Exposed Database (single instance)
      KeycloakConfig.kt        # issuer/JWKS/admin URLs derived from env
    plugins/
      Authentication.kt        # JWT validation; resolves the UserPrincipal
      StatusPages.kt           # maps thrown typed errors → ApiError JSON
      RateLimiting.kt
      Serialization.kt         # ContentNegotiation(kotlinx.serialization)
    modules/                   # one package per domain
      cycles/
        CyclesRoutes.kt
        CyclesService.kt
        CyclesRepository.kt
      dailylogs/  pain/  analytics/  symptoms/  refdata/  users/  preferences/
    lib/
      Encryption.kt            # AES-256-GCM encrypt/decrypt helpers
      KeycloakAdminClient.kt   # Admin API client (account deletion)
      Errors.kt                # typed AppException hierarchy (maps to ErrorCode)
    db/
      Tables.kt                # Exposed Table objects mirroring data-model.md
  src/main/resources/
    db/migration/              # Flyway V1__initial_schema.sql, V2__seed_ref_data.sql, …
    logback.xml
```

`ApiError`, `ErrorCode`, the request/response DTOs, and the cycle/analytics math
live in `:shared` (not here). The server depends on `:shared` and reuses them
directly — there is no separate "schema" layer.

---

## Layering rules (unchanged from the web app — strict)

```
Route handler  →  Service  →  Repository  →  Exposed (DB)
```

- **Route handler** (`*Routes.kt`): deserializes + validates the request (shared
  `validation/` rules), calls a service, serializes the response. No business
  logic, no SQL.
- **Service** (`*Service.kt`): all business logic — cycle phase, predictions,
  encryption/decryption calls. Calls repositories. Never touches Exposed directly.
- **Repository** (`*Repository.kt`): all DB access via Exposed. Returns plain
  typed objects. No business logic. **Always scopes to the authenticated user.**

**Never:** SQL in a route handler; business logic in a repository; a route
calling another module's repository; skipping the service layer (analytics/ref-data
may have a thin service, but it still exists).

---

## Database layer — Exposed

Use Exposed for all DB access. No raw JDBC string-building, no ORM-style lazy
graphs. Table definitions live in `db/Tables.kt`, one `object : Table(...)` (or
`UUIDTable`) per table in `data-model.md`, with column types, constraints, and
indexes matching the schema exactly.

**Row ownership rule (non-negotiable):** every repository function that reads or
writes health data filters on `userId`, taken from the validated JWT principal —
**never** from the request body. A cross-user read returns `RESOURCE_NOT_FOUND`,
never `FORBIDDEN` (don't reveal another user's resource exists).

```kotlin
fun findDailyLog(userId: UUID, date: LocalDate): DailyLogRow? = transaction {
    DailyLogs
        .selectAll()
        .where { (DailyLogs.userId eq userId) and (DailyLogs.logDate eq date) }
        .map(::toRow)
        .singleOrNull()
}
```

The single `Database` instance is created in `config/Database.kt` from a HikariCP
pool. The pool connects as the **restricted app role** (`POSTGRES_APP_USER`),
which has DML only — no DDL. Migrations run separately as the superuser.

---

## Migrations — Flyway

- Versioned SQL in `server/src/main/resources/db/migration/`
  (`V1__initial_schema.sql`, `V2__seed_ref_data.sql`, …).
- **Manual, never automatic on startup** — run via a Gradle task or the one-off
  migration container (see `DEPLOYMENT.md`). Auto-migrate-on-boot is intentionally
  disabled.
- Seed reference data (symptom categories/options, pain regions/locations) with
  idempotent `INSERT ... ON CONFLICT DO NOTHING` so re-runs are safe.
- Because Exposed `Table` objects are hand-written (not generated), keep them in
  lockstep with the migrations — a mismatch surfaces at query time, so update both
  in the same change and cover the table with an integration test.

---

## Config — fail fast

`config/Config.kt` reads every environment variable once at startup, validates it,
and throws (exiting the process) if anything is missing or malformed — the server
never starts misconfigured. Nothing else in the app reads `System.getenv`
directly.

Variables (mirrors the web app; see `DOCKER.md` for the full table): `API_PORT`,
`POSTGRES_*` (app role for runtime), `KEYCLOAK_INTERNAL_URL`,
`PUBLIC_KEYCLOAK_URL`, `PUBLIC_KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID/SECRET`,
`APP_ENCRYPTION_KEY` (32 bytes base64), `LOG_LEVEL`.

`config/KeycloakConfig.kt` derives two base URLs: the **internal** URL
(`http://keycloak:8080`) for JWKS fetching + token/admin calls on the hot path
(stays in the Docker network), and the **public** URL for anything the client is
redirected to. Pin algorithms to **RS256 only** — rejecting other algs prevents
JWT algorithm-confusion attacks.

---

## Authentication

Keycloak is the identity provider (unchanged). The Ktor `Authentication` plugin
validates the `Authorization: Bearer <JWT>` on every `/api/v1` request:

1. Fetch JWKS from `KEYCLOAK_INTERNAL_URL` (cached, refreshed on key rotation) via
   a `JwkProvider`.
2. Validate signature + `iss`, `aud` (must contain `homeflow-backend`), `exp`;
   reject non-RS256.
3. On success, upsert the `users` row by the Keycloak `sub` and expose a
   `UserPrincipal(id: UUID, keycloakSub: String)` on the call. `id` is the
   internal app UUID; all downstream service/repository calls scope on it.
4. On failure → `401 UNAUTHORIZED` in the `ApiError` shape.

```kotlin
install(Authentication) {
    jwt("keycloak") {
        verifier(jwkProvider, keycloak.issuer) { acceptLeeway(30); withAudience("homeflow-backend") }
        validate { cred -> userService.principalFromClaims(cred.payload) }
        challenge { _, _ -> call.respondError(ErrorCode.UNAUTHORIZED) }
    }
}
```

> The clients obtain tokens via OIDC Authorization Code + PKCE against Keycloak
> directly (see `ARCHITECTURE-client.md` / `KEYCLOAK.md`). The server only ever
> *validates* tokens — it never runs a browser flow.

---

## Encryption

AES-256-GCM at the application layer for `daily_logs.notes` and
`daily_log_sex.encrypted_payload`, in **service functions only** — never in
repositories, routes, `:shared`, or any client.

- Key: 32 bytes from `APP_ENCRYPTION_KEY` (base64), never hardcoded.
- Stored as `base64(iv):base64(ciphertext):base64(tag)`, fresh 12-byte IV per call.
- Decryption failure throws hard — never silently swallowed.
- The repository reads/writes the raw string column; the service calls
  `Encryption.encrypt(...)` before passing down and `decrypt(...)` after reading.

```kotlin
// lib/Encryption.kt — AES/GCM/NoPadding, 12-byte IV, 128-bit tag
object Encryption {
    fun encrypt(plaintext: String): String { /* iv:ciphertext:tag, all base64 */ }
    fun decrypt(stored: String): String     { /* throws on tamper/wrong key */ }
}
```

---

## Error handling

All errors return the shape defined once in `:shared` (`ApiError`):

```json
{ "error": { "code": "RESOURCE_NOT_FOUND", "message": "Daily log not found for the given date." } }
```

Services throw a typed `AppException(code, message)`; the `StatusPages` plugin maps
each `ErrorCode` to its HTTP status and serializes the body. Handlers never format
errors manually.

| Code | HTTP | When |
|---|---|---|
| `UNAUTHORIZED` | 401 | no/invalid JWT |
| `FORBIDDEN` | 403 | valid JWT, disallowed action |
| `RESOURCE_NOT_FOUND` | 404 | resource absent for this user (incl. cross-user) |
| `VALIDATION_ERROR` | 400 | request fails validation |
| `CONFLICT` | 409 | unique-constraint violation (e.g. duplicate daily log) |
| `INTERNAL_ERROR` | 500 | unexpected |

---

## Logging

Logback + `kotlin-logging`. **Never log:** request/response bodies, health data of
any kind, `user_id` in plaintext (prod), tokens/secrets. **Safe:** method, path,
status, response time, error codes. Level via `LOG_LEVEL` (`info` prod, `debug`
dev). No HTTP body logging is ever installed.

---

## Naming conventions

- Files: `{Domain}{Layer}.kt` — `CyclesService.kt`, `CyclesRepository.kt`.
- Repository functions: verb + noun — `findDailyLog`, `insertEmotions`, `deleteAllUserData`.
- Service functions: intent — `logEmotions`, `closeCycle`, `predictOvulation` (these often delegate to `:shared` domain math).
- DB tables/columns: `snake_case` (plural entity tables, singular junction tables), exactly as `data-model.md`.
- DTOs: `*Dto` / `*Request` / `*Response` in `:shared`.

---

## Key constraints summary (fixed)

| Decision | Choice | Reason |
|---|---|---|
| DB layer | Exposed | type-safe, explicit, plays well with app-layer encryption |
| Migrations | Flyway, manual | controlled, re-runnable |
| Auth | Keycloak OIDC; server validates JWT only | `KEYCLOAK.md`, threat model |
| Encryption | AES-256-GCM, service layer | threat model |
| Row scoping | `userId` from JWT on every health query | threat model |
| Logging | no PII, no bodies | threat model |
| Shared contract | DTOs + domain math in `:shared` | single source of truth |
