# TESTING.md — Test Strategy (Kotlin)

The layered philosophy and the **security-critical tests** carry over from the web
app; the tooling is Kotlin. Tests are run manually (not gated by hooks); lint/type
checks are part of the Gradle build.

| Level | Tool | What it catches |
|---|---|---|
| Unit | kotlin.test / JUnit5 | domain math (`:core`), encryption round-trips, service logic |
| Integration | JUnit5 + **Testcontainers (Postgres)** | repository correctness, constraints, **user-scoping**, encryption persistence |
| Client UI | **Compose UI test** (`runComposeUiTest`) | screen rendering, loading/error/loaded states |
| End-to-end | manual / Ktor `testApplication` | full auth + data round-trip |

> Why a real Postgres (Testcontainers) and not a mock: the app relies on
> `gen_random_uuid()`, `UNIQUE (user_id, log_date)`, `ON CONFLICT`, `CHECK
> (severity BETWEEN 1 AND 10)`, and row-scoping correctness — a mock can't
> reproduce these, and a bug in scoping is a critical security failure.

---

## Where tests live

```
core/src/commonTest/kotlin/          # domain math + validation (pure, multiplatform)
server/src/test/kotlin/
  unit/                              # services, Encryption
  integration/                       # Testcontainers-backed repository/service tests
    Fixtures.kt                      # deterministic data factories
    UserScopingTest.kt               # cross-user access rejection (SECURITY)
    AnalyticsTest.kt                 # exact-value assertions on known data
app/shared/src/commonTest/kotlin/    # Compose UI tests
app/shared/src/jvmTest/ , src/androidHostTest/   # platform-specific client tests
```

---

## What gets tested at each layer

### Unit (no DB)

- **`:core` domain math** — the highest-value unit tests, because this code is
  shared by server and clients:
  - `predictPhase` assigns the correct phase across the cycle (and the reconciled
    ovulation window — see `SHARED-MODULE.md`).
  - `cycleStats`: mean cycle length, period length, 6-month population stddev;
    returns nulls under 2 closed cycles.
  - `ovulationPredictions`: `predictedOvulationDate = predictedPeriodStart − 14d`.
  - `cycleDayNumber` / `cycleLength` edge cases (start = day 1; open cycle = null).
- **`Encryption`** (server): encrypt→decrypt round-trips to the original; distinct
  IVs ⇒ distinct ciphertext; decrypt with a wrong key/tampered tag **throws**.

### Integration (Testcontainers Postgres)

Spin up `postgres:16-alpine` via Testcontainers, run Flyway migrations against it
once, then call services/repositories directly (no HTTP). Reset health-data tables
between tests; treat seeded reference tables as read-only.

- **cycles**: create inserts the row; starting a new cycle auto-closes the prior
  open one (`end_date = newStart − 1d`); `getCurrent` 404s when none open; closing
  with `endDate < startDate` → `VALIDATION_ERROR`.
- **daily-logs**: date outside cycle → `VALIDATION_ERROR`; duplicate date →
  `CONFLICT`; `PUT` replaces the selection set; empty array clears; unknown /
  wrong-category option → `VALIDATION_ERROR`; **notes stored as ciphertext** (raw
  column ≠ plaintext) and decrypt correctly; **sex payload** stored encrypted,
  decrypts correctly.
- **pain**: severity + locations saved; `null` severity valid; unknown location →
  `VALIDATION_ERROR`; severity outside 1–10 rejected.
- **`AnalyticsTest`** — seed known cycles and assert exact outputs:
  ```
  Cycle 1: 2024-01-01 → 2024-01-28, flow Jan 1–5  (5 bleeding days)
  Cycle 2: 2024-01-29 → 2024-02-25, flow Jan 29–Feb 3 (6)
  Cycle 3: 2024-02-26 → 2024-03-24, flow Feb 26–Mar 1 (4)
  ⇒ averageCycleLength 28, averagePeriodLength 5, cycleVariation 0.0
  ⇒ ovulation = periodStart − 14d
  ```
- **`UserScopingTest`** (SECURITY — must all pass): user A cannot read or write
  user B's cycles, daily logs, pain, or analytics; access by another user's
  resource ID returns **`RESOURCE_NOT_FOUND`**, not `FORBIDDEN`.

### Client UI (Compose)

`runComposeUiTest` over `commonTest`: each screen renders its Loading / Error /
Loaded states from a fake repository; the day view resolves option IDs to labels;
empty states render (not crashes) when analytics fields are null. Keep these
narrow — exhaustive UI testing isn't worth it for a single-user app.

### End-to-end

Ktor `testApplication` exercises a route through real auth validation against a
test token, and a representative data round-trip. The full Keycloak password + TOTP
flow is verified manually on first login; for automated E2E use a password-only test
user (no OTP configured) in the **dev realm only**, never production.

---

## What is explicitly NOT tested

- Repositories in isolation (covered via integration through the service).
- Reference-data content (verified once by row counts in a setup check).
- Keycloak internals (a dependency; the OIDC flow is exercised against the real
  container, not unit-mocked).
- Exhaustive UI coverage of every screen.

---

## Running

```bash
./gradlew :core:allTests                  # shared domain/validation (all targets)
./gradlew :server:test                    # server unit + integration (Testcontainers needs Docker)
./gradlew :app:shared:testAndroidHostTest # Android-side unit/UI
./gradlew :app:shared:jvmTest             # desktop-side
./gradlew check                           # everything + lint
```

Testcontainers requires a running Docker daemon on the machine executing the
server tests.
