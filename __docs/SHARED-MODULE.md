# SHARED-MODULE.md — the `commonMain` boundary

This document defines what lives in the shared Kotlin Multiplatform module
(`:core`) versus what stays server-only or platform-specific. **This boundary
is the entire justification for the Kotlin-everywhere architecture** — if the
shared module is thin, we gained nothing over the old OpenAPI-codegen setup; if
it leaks server concerns into clients, we create security problems. Read this
before adding anything to `:core`.

---

## Why a shared module at all

In the original HomeFlow web app, two things were duplicated across the Node
backend and the SvelteKit/Android clients, and drifted independently:

1. **The API contract.** The backend defined response shapes in TypeScript; the
   Android client regenerated Kotlin models from `openapi.yaml`; the web client
   redeclared them again. Three sources of one truth.
2. **Cycle-phase math.** The phase-prediction logic exists in *both*
   `frontend/src/lib/cycle.ts` (`predictPhase`) **and**
   `backend/src/modules/analytics/analytics.service.ts` (the sleep-prediction
   phase bucketing) — with subtly different ovulation windows (`±1 day` in one,
   `[ovu-1, ovu]` in the other). That divergence is a bug waiting to surface.

A shared module collapses both: the DTOs and the domain math are written once in
`commonMain`, compiled into the Ktor server **and** both Compose clients, so the
contract is a compile-time guarantee and the math cannot diverge.

---

## Module layout

```
HomeFlow-multiplatform/
  core/                           ← the :core module — KMP library, the contract + domain
    src/commonMain/kotlin/org/homeflow/core/
      dto/                        API request/response models (kotlinx.serialization)
      domain/                     pure cycle/analytics math (no I/O)
      validation/                 input rules shared by server + client
      ApiError.kt                 the canonical error shape + codes
  server/                         ← :server (Ktor); depends on :core
  app/
    shared/                       ← :app:shared (Compose lib); depends on :core
      src/commonMain/             shared UI + repository + view models
      src/androidMain/            Keystore, BiometricPrompt, FLAG_SECURE
      src/jvmMain/                OS keychain, loopback OIDC redirect
    androidApp/                   ← :app:androidApp — Android entry point
    desktopApp/                   ← :app:desktopApp — desktop entry + packaging
```

> Two shared tiers: **`:core`** is pure (no Compose, no OS APIs) so the server can
> depend on it; **`:app:shared`** is the Compose-aware client-shared module. The
> server must depend only on `:core`, never on `:app:shared`.

`:core` must have **zero** server-only or Android-only dependencies. It is
pure Kotlin + kotlinx.serialization + kotlinx.datetime. If something needs a
JDBC driver, the Android Keystore, or a JVM crypto provider, it does **not**
belong in `:core`.

---

## What goes in `:core`

### 1. DTOs — `dto/` (kotlinx.serialization)

Every request and response body the API exchanges, as `@Serializable` data
classes. These replace the OpenAPI-generated models entirely.

- One file per resource group, mirroring `API.md` (cycles, daily-logs, pain,
  symptoms, analytics, preferences, users, ref-data, import/export).
- Dates stay as `String` (ISO-8601 `yyyy-MM-dd`) at the wire boundary, exactly
  as the current API emits them — parse into `kotlinx.datetime.LocalDate` inside
  `domain/`, not at the DTO.
- The `daily_log_sex` payload is exchanged as a **plaintext** `List<String>` of
  option IDs in the DTO. The DTO never sees ciphertext — encryption is a
  server-only concern (see *What stays server-only*). The client and server
  agree on the same DTO; the server encrypts after deserialization and decrypts
  before serialization.

Concrete DTOs to define first (shapes are authoritative in
`analytics.service.ts` / `API.md`):
- `CycleStatsDto { averageCycleLength: Int?, cycleVariation: Double?, averagePeriodLength: Int? }`
- `OvulationPredictionDto { averageCycleLength: Int?, predictions: List<Prediction>? }`
- `PeriodLengthChartDto`, `SleepPredictionsDto`

### 2. Domain math — `domain/` (pure functions, no I/O)

The cycle/analytics logic, ported once from `cycle.ts` + `analytics.service.ts`
and **deduplicated**. Pure functions over already-fetched data — no DB, no HTTP.

| Function | Ported from | Notes |
|---|---|---|
| `cycleDayNumber(start, on): Int` | `cycle.ts` | 1-based; start date = day 1 |
| `cycleLength(cycle): Int?` | `cycle.ts` | inclusive; `null` for the open cycle |
| `predictPhase(cycleDay, avgCycle?, avgPeriod?): CyclePhase` | `cycle.ts` | **reconcile the ovulation window with the server's `analytics.service.ts` version — pick one definition here and delete the other.** |
| `cycleStats(closedCycles): CycleStatsDto` | `analytics.service.ts` | mean cycle length, 6-month population stddev, mean period length; `null` under 2 closed cycles |
| `ovulationPredictions(mostRecentStart, avgCycleLength): List<Prediction>` | `analytics.service.ts` | next 3 cycles; ovulation = period start − 14 days |
| `bucketSleepByPhase(...)` | `analytics.service.ts` | phase bucketing + top-3 most-common, min sample size 5 |

Constants live here too: `DEFAULT_CYCLE_LENGTH = 28`, `DEFAULT_PERIOD_LENGTH =
5`, `PHASE_LABELS`, and the `CyclePhase` enum.

> The server still owns the **queries** that feed these functions (it reads the
> DB); the clients call the same functions over data fetched from the API. The
> math is shared; the data access is not.

### 3. Validation — `validation/`

Input rules that both sides enforce: a cycle's `endDate` must not precede
`startDate`, dates must not be in the future, a daily-log date must fall within
its cycle, severity is 1–10. The client validates for fast UX feedback; the
server **re-validates** as the security boundary (never trust the client). Same
code, two call sites.

### 4. The error contract — `ApiError.kt`

The canonical error shape and code enum, ported from the web app's error
handler:

```kotlin
@Serializable data class ApiError(val error: ErrorBody)
@Serializable data class ErrorBody(val code: ErrorCode, val message: String)
enum class ErrorCode { UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND, VALIDATION_ERROR, CONFLICT, INTERNAL_ERROR }
```

The Ktor server serializes these; the client deserializes and pattern-matches on
`code`. HTTP status mapping (see `CLAUDE.md`) is a server concern, but the codes
are shared.

---

## What stays server-only (NOT in `:core`)

These touch secrets, the database, or the IdP. They must never compile into a
client binary.

- **Encryption.** AES-256-GCM of `daily_logs.notes` and
  `daily_log_sex.encrypted_payload` happens in **server service functions only**,
  using `APP_ENCRYPTION_KEY`. Clients send/receive plaintext over TLS; they never
  hold the key, never see ciphertext. (Future on-device offline cache encryption
  is a *separate* client-side key — not this one.)
- **Database access.** Exposed/SQLDelight, JDBC, migrations (Flyway), connection
  pooling, the two-role Postgres setup, and `WHERE user_id = ?` scoping all live
  in `server/`. Row scoping is a server-side security invariant — clients cannot
  enforce it.
- **Keycloak Admin API** calls and the confidential client secret.
- **JWT signature validation** (the server verifies; clients only carry tokens).

---

## What is platform-specific (in `:app:shared`, not `:core`)

Same interface in `commonMain`, different `actual` implementation per platform:

| Concern | `androidMain` | `jvmMain` |
|---|---|---|
| Token storage | Keystore-backed `EncryptedSharedPreferences` | OS keychain (e.g. via a secret-service / DPAPI binding) |
| App-open gate | `BiometricPrompt` (strong, device-credential fallback) | OS credential prompt or app passphrase |
| Anti-screenshot | `FLAG_SECURE` on the window | best-effort / N/A |
| OIDC redirect | `org.homeflow.mobile:/oauth2redirect` custom scheme | loopback `http://127.0.0.1:<port>` redirect |

Declare these as `expect`/`actual` so `commonMain` repository code stays
platform-agnostic.

---

## The one rule

> If a piece of code needs a secret, a database handle, or an OS API, it does not
> go in `:core`. Everything else that the server and a client would otherwise
> write twice — DTOs, cycle math, validation, error codes — does.
