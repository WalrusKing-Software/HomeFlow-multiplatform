# SECURITY-HARDENING-PLAN.md — Security Audit Results & Hardening Work Plan

**Audit date:** 2026-07-14 (full-codebase review: server, clients, infra, CI, git history).
**Status of each task:** `TODO` until implemented; update this file as tasks land.

> **Implementation status (2026-07-14, branch `feature/security-hardening`):**
> all Phase 0–4 tasks are **DONE** except the operator-manual SEC-15 (secret
> rotation — operator action) and the deliberately deferred Phase 5 items.
> SEC-01..05, SEC-06..14 implemented, one commit per task; see `git log` on the
> branch. SEC-13's dialog verified by compile + pattern reuse (no UI test
> harness exists for PreferencesScreen); everything else has automated tests
> or config validation as specified.

This document has two jobs:

1. **Record the audit baseline** — what was checked and found *correct*, so nobody
   "fixes" a non-issue or regresses a verified invariant.
2. **Specify the hardening work** — every task is fully specified (files, code,
   acceptance criteria, tests, docs). The implementer must not make design or
   architecture choices; where a choice existed, it has already been made here.
   If an instruction contradicts what you find in the code, **stop and report** —
   do not improvise.

## Rules for the implementer

- Implement tasks **in phase order** (Phase 0 → 5). Tasks within a phase are
  independent unless a dependency is stated.
- One task = one commit (or one small PR-sized branch per phase). Never batch
  unrelated tasks into one commit.
- After each task: run the verification steps listed for that task, then the
  global verification (end of this document) before moving to the next phase.
- Every task that changes user-/developer-visible behavior lists its required
  `CHANGELOG.md` entry — write it as part of the task, per `CLAUDE.md`.
- Do **not** implement anything in the "Accepted risks / explicitly deferred"
  section, even if it looks easy.
- Line numbers below were correct at audit time; re-locate by symbol name if
  they have drifted.

---

## 1. Audit baseline — verified correct (do not change)

These invariants were verified by direct code inspection. Any task below that
touches these files must preserve them; the existing tests that pin them must
keep passing.

### Server (`server/`)
- **JWT validation** (`plugins/Authentication.kt`): RS256-only via RSA JWKS
  (algorithm-confusion attempts fail), `iss` + `aud homeflow-backend` + `exp`
  checked, 30 s leeway, JWKS cached (10 keys / 24 h) and rate-limited
  (10/min), 401 in canonical `ApiError` shape on any failure.
- **Encryption** (`lib/Encryption.kt`): AES-256-GCM, key length validated to
  exactly 32 bytes at init, fresh 12-byte `SecureRandom` IV per call, 128-bit
  tag, `base64(iv):base64(ciphertext):base64(tag)` format, hard-throws on
  tamper/wrong key/malformed payload. Called from **service layer only**
  (verified for `daily_logs.notes` and `daily_log_sex` in DailyLogs/Sync/
  ImportExport services). Covered by `EncryptionTest.kt` (7 cases).
- **Row scoping**: every repository read/write filters on `userId` from
  `UserPrincipal` (spot-verified in Cycles/DailyLogs/DailyLogSubs/ChangeLog
  repositories). Cross-user access returns `RESOURCE_NOT_FOUND` (404), never
  `FORBIDDEN` — pinned by `CyclesDailyLogsTest.kt`.
- **Error handling** (`plugins/StatusPages.kt`): unhandled `Throwable` → generic
  500 message; only `cause::class.simpleName` is logged — no stack traces,
  bodies, or messages leak to clients or logs.
- **Logging**: no CallLogging/body logging installed anywhere; logback pattern
  contains no PII fields.
- **Unauthenticated surface**: exactly `/health` and `GET /api/v1/version`
  (intentional, documented). Everything else under `authenticate(KEYCLOAK_AUTH)`.
- **Import**: authenticated, 25 MB cap enforced before deserialization,
  `SerializationException` → `VALIDATION_ERROR`, nothing logged.
- **Config**: fail-fast `requireEnv` for all required vars; no scattered
  `System.getenv`.
- **DB roles**: runtime uses the DML-only app role; migrations (Flyway) run
  separately as superuser; `infra/postgres/init/02_create_app_role.sh` grants
  are minimal and password comes from env.

### Clients (`app/`)
- Refresh token: Android `EncryptedSharedPreferences` (AES256-SIV keys /
  AES256-GCM values, Keystore master key); desktop OS keychain via
  java-keyring. Access token in in-memory `TokenHolder` only. Neither is ever
  logged (verified `Diagnostics.kt` and all `authDebugLog` call sites).
- PKCE S256: 32-byte verifier, SHA-256 challenge, 16-byte `state` validated on
  desktop before code acceptance; AppAuth handles both on Android.
- Desktop loopback listener: binds `127.0.0.1`, ephemeral port, validates
  `state`, one-shot shutdown after code capture.
- Desktop TLS: combined trust manager (system CAs + user-selected CA file).
  **No trust-all / hostname-bypass code exists anywhere in the tree.** Android
  network security config: cleartext disabled; user-CA trust scoped to
  `homeflow.lan` only; debug-overrides confined to the debug source set.
- SQLCipher: whole-DB encryption on both platforms, DEK from
  `secureRandomBytes(32)`, stored only in platform secure storage, cleared on
  `deleteAccount()`; no unencrypted-fallback path.
- Desktop app-lock passphrase: PBKDF2-HMAC-SHA256, 120 000 iterations, 16-byte
  salt, constant-time compare, verifier stored in OS keychain.
- `FLAG_SECURE` set in `MainActivity.onCreate`.
- No Ktor `Logging` plugin on any engine; no OkHttp body interceptor.
- Logout best-effort revokes the refresh token at the end-session endpoint,
  then clears `TokenStore` + `TokenHolder`.

### Infra / CI
- Prod compose publishes **only** Caddy 80/443; postgres/keycloak/backend are
  internal-only. Dev port publishing lives only in the never-auto-merged
  `docker-compose.dev.yml`.
- Caddy exposes only `/api/*`, `/health`, `/realms/*`, `/resources/*`; the
  Keycloak admin console is not routed.
- Realm: registration & password-reset disabled, brute-force protection on
  (5 failures), PKCE S256 required on both public clients, implicit &
  direct-access grants disabled, exact redirect URIs (no wildcards), audience
  mappers present on both public clients, `browser-with-otp` bound,
  `CONFIGURE_TOTP` default action.
- CI: all third-party actions pinned by SHA, `permissions: contents: read` on
  CI workflows, no `pull_request_target`, gitleaks (full history) + Semgrep
  (`p/default,security-audit,secrets,owasp-top-ten,kotlin,java`) required via
  branch rulesets, Gradle wrapper SHA-256 pinned + URL validation on.
- Pre-commit: `scripts/check-secrets.sh` blocks `.env`, PEM private keys, AWS
  keys, hardcoded-credential patterns.
- **Git history**: verified clean — `.env` was never committed (only
  `.env.example`); no `.jks`/`.pem`/keystore files in history. The working-tree
  `.env`, `homeflow-release.jks`, `keystore.properties`, `caddy-root.crt` are
  all correctly gitignored.

---

## 2. Findings register

| ID | Severity | Area | Finding |
|---|---|---|---|
| SEC-01 | Medium | Server | Rate limiter is one global bucket, not per-client — one client can starve all; no forwarded-header handling so per-IP keying is currently impossible |
| SEC-02 | Medium | Server | Sync pull (`ChangeLogRepository.findChangesSince`) is unbounded — a large change set is read and decrypted entirely in memory |
| SEC-03 | Medium | Server | `logback.xml` root level is `trace`; `LOG_LEVEL` env is read by `Config` but never applied to logback |
| SEC-04 | Low | Server | JDBC URL has no `sslmode` — fine on the Docker network, not configurable for a remote DB |
| SEC-05 | Medium | Server/Infra | Nothing prevents the dev Keycloak client secret (`dev-only-backend-secret-change-me`, shipped in `realm-export.json`) from reaching production |
| SEC-06 | High | Infra | Caddy sends no `Strict-Transport-Security` header |
| SEC-07 | Medium | Infra | No `no-new-privileges`, `cap_drop`, or read-only rootfs on any container |
| SEC-08 | Medium | Infra | No healthcheck on keycloak/backend; backend starts on `service_started` for Keycloak, and no request-body cap at the proxy |
| SEC-09 | Medium | Infra | All images (`postgres:16-alpine`, `keycloak:26.1`, `caddy:2-alpine`, `eclipse-temurin:21-*`) pinned by tag only, not digest |
| SEC-10 | Medium | Keycloak | `offlineSessionMaxLifespanEnabled: false` — offline refresh tokens have no absolute ceiling (only 30-day idle) |
| SEC-11 | Medium | Android | `android:allowBackup="true"` with no restriction — app data (encrypted prefs + SQLCipher DB) is eligible for cloud/adb backup |
| SEC-12 | Low | Desktop | Keyring read failures are swallowed silently (`runCatching{}.getOrNull()`) — lockouts are undiagnosable |
| SEC-13 | Low | Client UX | Data export writes plaintext health-data JSON with no warning to the user |
| SEC-14 | Low | CI | gitleaks container uses the `latest` tag; no automated dependency-update process |
| SEC-15 | Info | Ops | Local `.env` secrets were read into audit tool transcripts during this review (never committed) — precautionary rotation recommended |

---

## Phase 0 — Operator actions (no code; do these first)

### SEC-15 — Precautionary secret rotation (operator, manual)

**Severity:** Info. **Threat:** TS-9 (accidental secret exposure).

During this audit, automated tooling read the local `.env` and its values
appeared in local session transcripts. `.env` was never committed to git, but
as a precaution the operator should rotate, at their convenience:

1. `POSTGRES_PASSWORD`, `POSTGRES_APP_PASSWORD` (`openssl rand -base64 24`,
   update `.env`, recreate the postgres role passwords via `ALTER ROLE ... WITH PASSWORD`).
2. `KEYCLOAK_ADMIN_PASSWORD` (change in Keycloak admin console, then `.env`).
3. `APP_ENCRYPTION_KEY` — **do not rotate blindly**: existing `daily_logs.notes`
   and `daily_log_sex` ciphertexts are bound to the current key. Rotation
   requires a re-encryption pass and is deferred (see Accepted risks). Leave as is.
4. `KEYCLOAK_CLIENT_SECRET` — regenerate in Keycloak (Clients →
   `homeflow-backend` → Credentials), update `.env`, restart backend.

This is an operator runbook item, not a code task. No CHANGELOG entry.

---

## Phase 1 — Server hardening

### SEC-01 — Per-client rate limiting behind Caddy

**Severity:** Medium. **Threat:** TS-2 (unauthenticated API abuse / brute force).

The backend only ever receives traffic from Caddy (its port is not published),
so `X-Forwarded-For` set by Caddy is trustworthy. Key the existing global rate
limiter per forwarded client address.

**Files:**
- `gradle/libs.versions.toml` — add to the ktor library block:
  `ktor-server-forwarded-header = { module = "io.ktor:ktor-server-forwarded-header", version.ref = "ktor" }`
- `server/build.gradle.kts` — add `implementation(libs.ktor.server.forwarded.header)`
  next to the other ktor-server dependencies.
- `server/src/main/kotlin/org/homeflow/Application.kt` — in the module setup,
  immediately **before** `configureRateLimiting(...)`, add:

```kotlin
// The backend is reachable only from Caddy on the internal Docker network,
// so X-Forwarded-For is set by our own proxy and is safe to trust for
// rate-limit keying. Do NOT install this if the backend port is ever published.
install(XForwardedHeaders)
```

  Import: `io.ktor.server.plugins.forwardedheaders.XForwardedHeaders`.

- `server/src/main/kotlin/org/homeflow/plugins/RateLimiting.kt` — key the
  global limiter per client address:

```kotlin
fun Application.configureRateLimiting(config: RateLimitConfig) {
    install(RateLimit) {
        global {
            rateLimiter(limit = config.maxRequests, refillPeriod = config.windowMillis.milliseconds)
            // One bucket per client IP (from X-Forwarded-For via XForwardedHeaders;
            // falls back to the socket address when the header is absent, e.g. tests).
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
}
```

  Imports: `io.ktor.server.plugins.origin`, `io.ktor.server.request.*` as needed.
  Keep the existing KDoc, updated to describe per-client keying.

**Behavioral notes (fixed decisions):**
- Rate-limit rejections stay as Ktor's default `429` with an empty body. Do
  **not** add a new `ErrorCode` — the `ApiError` code table in `:core`/API.md
  is fixed.
- Env names and defaults (`RATE_LIMIT_MAX=100`, `RATE_LIMIT_TIME_WINDOW_MS=60000`)
  are unchanged.

**Tests:** add `RateLimitingTest.kt` under the server test source set,
following the `testApplication` style of `AuthUsersTest.kt`:
1. Requests with header `X-Forwarded-For: 10.0.0.1` exhausting the limit get
   `429`; a subsequent request with `X-Forwarded-For: 10.0.0.2` gets a non-429
   response. Configure a tiny limit (e.g. max 3 per minute) via the test config.
2. Requests without the header still work (socket-address fallback).

**Acceptance criteria:** both tests green; existing `AuthUsersTest`,
`CyclesDailyLogsTest`, `SyncTest`, `ImportExportTest` unchanged and green.

**Docs:** `__docs/ARCHITECTURE-server.md` — update the RateLimiting bullet in
the plugin list to say "per-client (X-Forwarded-For) buckets behind Caddy".
**CHANGELOG (Changed):** "API rate limiting is now applied per client address
instead of one global bucket, so one client can no longer exhaust the limit
for others."

### SEC-02 — Paginate the sync pull

**Severity:** Medium. **Threat:** resource exhaustion (memory/DoS) on
`GET /api/v1/sync/changes`.

**Design (fixed):** page size **500** change rows, ordered by `server_seq`
ascending. The response `cursor` becomes "last `server_seq` included in this
page" (or the request cursor when the page is empty). A new additive field
`hasMore: Boolean = false` tells the client to pull again. This is additive
and backward compatible: an older client ignores `hasMore` (its Json uses
`ignoreUnknownKeys`), persists the page cursor, and picks up the remainder on
its next scheduled sync. No `minClientVersion` bump.

**Files & changes:**
1. `core/src/commonMain/kotlin/org/homeflow/core/dto/SyncDtos.kt` — add
   `val hasMore: Boolean = false` as the last property of `SyncPullResponse`.
2. `server/.../modules/sync/ChangeLogRepository.kt` — add a `limit: Int`
   parameter to `findChangesSince(userId, cursor)` and apply
   `.orderBy(SyncChanges.serverSeq to SortOrder.ASC).limit(limit)`
   (verify the existing function name/signature at ~line 78 and keep its
   row-scoping `userId` filter exactly as is).
3. `server/.../modules/sync/SyncService.kt` — in `pull(...)`:
   - Add `private const val SYNC_PULL_PAGE_SIZE = 500` (file-level or companion,
     matching file style).
   - Fetch `SYNC_PULL_PAGE_SIZE + 1` rows; `hasMore = rows.size > SYNC_PULL_PAGE_SIZE`;
     process only the first `SYNC_PULL_PAGE_SIZE`.
   - Response `cursor` = `serverSeq` of the last processed row, or the request
     cursor if no rows. Set `hasMore` accordingly.
   - Do not change the push path.
4. Client `app/shared/.../data/sync/SyncEngine.kt` — after a successful pull
   is applied and the cursor persisted, if `response.hasMore` is true, pull
   again from the new cursor. Bound the loop at **50 pages per sync run**
   (constant `MAX_PULL_PAGES = 50`); if still `hasMore` after 50 pages, stop —
   the next sync run continues (do not surface an error).

**Tests:**
- Server `SyncTest.kt`: add a case seeding > page size changes is impractical
  at 500 — instead make the page size injectable: give `SyncService` a
  constructor parameter `pullPageSize: Int = SYNC_PULL_PAGE_SIZE` and construct
  it with `pullPageSize = 2` in the test. Assert: first pull returns 2 changes
  + `hasMore=true` + cursor of the 2nd row; second pull from that cursor
  returns the rest + `hasMore=false`; union of pages equals the unpaginated
  expectation; no duplicates.
- Client `SyncEngineTest.kt`: fake data source returns two pages
  (`hasMore=true` then `false`); assert the engine applies both in one
  `sync()` call and persists the final cursor.

**Acceptance criteria:** all existing sync tests green; new tests green;
`SyncPullResponse` change is additive (no field removed/renamed/reordered
semantics beyond appending).

**Docs:** update the pull endpoint in `__docs/API.md` and `openapi.yaml`
(add `hasMore` to the pull response schema, describe paging).
**CHANGELOG (Changed):** "Sync pull is now paginated (500 changes per page);
clients transparently fetch all pages in one sync run."

### SEC-03 — Wire LOG_LEVEL into logback; stop logging at trace

**Severity:** Medium. **Threat:** log-based information leakage; §"What Never
Gets Logged".

**File:** `server/src/main/resources/logback.xml` — replace the root element
level with an env-substituted value (logback resolves `${...}` against system
properties, then OS environment):

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="${LOG_LEVEL:-INFO}">
        <appender-ref ref="STDOUT"/>
    </root>
    <logger name="org.eclipse.jetty" level="INFO"/>
    <logger name="io.netty" level="INFO"/>
</configuration>
```

Note logback level strings are case-insensitive, so the existing
`LOG_LEVEL=info|debug` values in `.env.example` / compose files work unchanged.

**Acceptance criteria:** with no `LOG_LEVEL` set, server logs at INFO (verify
by running `:server:test` and observing no TRACE/DEBUG lines from app loggers);
with `LOG_LEVEL=debug`, debug lines appear.
**CHANGELOG (Fixed):** "Server now honors LOG_LEVEL (default INFO); it
previously logged at TRACE regardless."

### SEC-04 — Configurable JDBC sslmode

**Severity:** Low. **Threat:** TS-5 (DB credential/traffic exposure if the DB
ever moves off the local Docker network).

**File:** `server/src/main/kotlin/org/homeflow/config/Database.kt`:
- Read optional env `POSTGRES_SSLMODE`, default `"disable"`.
- Validate it is one of `disable`, `require`, `verify-ca`, `verify-full` —
  otherwise throw at startup (same fail-fast style as `requireEnv`).
- Append `?sslmode=<value>` to the JDBC URL.

**Files (docs/config):** add `POSTGRES_SSLMODE=disable` with a comment to
`.env.example` ("disable is correct for the single-host Docker network; use
verify-full for a remote PostgreSQL"). Mention in `__docs/DOCKER.md` env
reference and `__docs/ARCHITECTURE-server.md` config section.

**Tests:** extend the existing config/database test (or add one) asserting an
invalid value throws and the URL contains `sslmode=disable` by default.
**CHANGELOG (Added):** "POSTGRES_SSLMODE environment variable for TLS to a
remote PostgreSQL (default disable for the on-host Docker network)."

### SEC-05 — Refuse to start with the dev Keycloak client secret

**Severity:** Medium. **Threat:** TS-7 (Keycloak misconfiguration), TA-6.

`infra/keycloak/realm-export.json` ships
`"secret": "dev-only-backend-secret-change-me"` (intentional for dev import;
docs already say to regenerate in prod). Add a fail-fast guard so production
cannot run on it.

**File:** `server/src/main/kotlin/org/homeflow/config/KeycloakConfig.kt`:

```kotlin
private const val DEV_BACKEND_SECRET = "dev-only-backend-secret-change-me"
```

After reading `KEYCLOAK_CLIENT_SECRET`, add:

```kotlin
val allowDevSecrets = System.getenv("ALLOW_DEV_SECRETS") == "true"
require(allowDevSecrets || clientSecret != DEV_BACKEND_SECRET) {
    "KEYCLOAK_CLIENT_SECRET is the dev-only default. Regenerate it in Keycloak " +
        "(Clients → homeflow-backend → Credentials) or set ALLOW_DEV_SECRETS=true (dev only)."
}
```

(Adapt to the file's existing env-reading helper style; nothing else in the
app may read `System.getenv` directly, so read it inside this config object
alongside the other vars.)

**Files (infra):** `docker-compose.dev.yml` — add `ALLOW_DEV_SECRETS: "true"`
to the backend service environment. Do **not** add it to `docker-compose.yml`
or `docker-compose.image.yml`. Add a commented-out line to `.env.example`
documenting it as dev-only.

**Tests:** unit test for the config guard: dev secret without the flag →
throws; with flag → passes; a normal secret without the flag → passes.
**Docs:** note the guard in `__docs/KEYCLOAK.md` ("After importing to a new
environment" section) and `__docs/SETUP-SERVER.md` if it lists the secret step.
**CHANGELOG (Added):** "Server refuses to start with the dev-only Keycloak
client secret unless ALLOW_DEV_SECRETS=true (dev compose only)."

---

## Phase 2 — Client hardening

### SEC-11 — Disable Android backup

**Severity:** Medium. **Threat:** TS-10 / health-data-at-rest leaving the
device via cloud/adb backup. The SQLCipher DB is safe without the DEK, but the
Keystore-backed encrypted prefs and DB file should not leave the device at all.

**File:** `app/androidApp/src/main/AndroidManifest.xml` — on the
`<application>` element change `android:allowBackup="true"` to
`android:allowBackup="false"`. Do not add `dataExtractionRules` /
`fullBackupContent` (unnecessary once backup is off).

**Verification:** `./gradlew :app:androidApp:assembleDebug` builds;
`aapt dump badging` (or manifest merger report) shows `allowBackup=false`.
**CHANGELOG (Changed):** "Android app data is excluded from device/cloud
backups (allowBackup=false); health data and key material never leave the
device via backup."

### SEC-12 — Surface desktop keyring failures in diagnostics

**Severity:** Low. **Threat:** availability/diagnosability (silent lockout),
and TA-6-style misconfiguration going unnoticed.

**Files:**
- `app/shared/src/jvmMain/kotlin/org/homeflow/app/shared/auth/TokenStore.jvm.kt`
- `app/shared/src/jvmMain/kotlin/org/homeflow/app/shared/auth/LocalKeyStore.jvm.kt`
- (and `AppLockGate.jvm.kt` if it uses the same swallow pattern — check)

Replace each silent `runCatching { ... }.getOrNull()` around keyring
read/write/delete with a try/catch that logs **only the exception chain** via
the existing diagnostics helper and returns the same fallback:

```kotlin
try {
    keyring.getPassword(SERVICE, ACCOUNT)
} catch (e: Exception) {
    authDebugLog("TokenStore: keyring read failed: ${e.describe()}")
    null
}
```

`authDebugLog` and `Throwable.describe()` already exist in
`app/shared/src/commonMain/.../auth/Diagnostics.kt` and never log secret
material — pass only `e`, never the stored value. Keep behavior otherwise
identical (same null fallbacks).

**Tests:** none required (logging-only change); compile + existing jvmTest green.
**CHANGELOG (Fixed):** "Desktop: secure-storage (keyring) failures are now
logged to auth diagnostics instead of failing silently."

### SEC-13 — Warn before plaintext export

**Severity:** Low. **Threat:** TS-1/TS-6 — the export file is deliberately
plaintext JSON of all health data; the user must knowingly accept that.

**File:** the screen that triggers export (Settings/Preferences screen in
`app/shared/src/commonMain/.../ui/screens/` — locate the existing export
button wired to `onExport`/`exportData`). Insert a Material 3 `AlertDialog`
confirmation between tap and export:

- State: `var showExportWarning by remember { mutableStateOf(false) }`; the
  export button sets it true; the dialog's confirm button runs the original
  export callback and dismisses.
- Exact copy (use verbatim):
  - Title: `Export unencrypted data?`
  - Text: `The export is a plain, unencrypted JSON file containing all of your health data. Anyone with access to the file can read it. Save it only to a location you trust, and delete it when you no longer need it.`
  - Confirm button: `Export`
  - Dismiss button: `Cancel`
- Follow the existing dialog pattern used by the account-deletion danger-zone
  dialog on the same screen (match its composable structure and styling).

**Tests:** if the screen has Compose UI tests covering the export button,
extend them: tapping Export shows the dialog; Cancel does not invoke the
callback; Export invokes it exactly once. If no UI test harness exists for
this screen, manual verification on desktop (`:app:desktopApp:run`) is the
acceptance check — state this in the commit message.
**CHANGELOG (Added):** "Export now asks for confirmation and warns that the
exported JSON file is unencrypted."

---

## Phase 3 — Infrastructure hardening

### SEC-06 — HSTS + request body cap in Caddy

**Severity:** High (HSTS), Medium (body cap). **Threat:** TS-2/TS-4 downgrade
exposure; resource exhaustion.

**File:** `infra/caddy/Caddyfile` — two edits inside the site block:

1. Add to the existing `header { ... }` block:
   `Strict-Transport-Security "max-age=31536000"`
   (no `includeSubDomains`/`preload` — the hostname is a LAN/tailnet name).
2. Add a body-size cap before the handles:

```caddyfile
request_body {
    max_size 26MB
}
```

26 MB = the server's 25 MB import cap plus multipart overhead; all other
routes carry small JSON bodies, and one global cap keeps the config simple.

**Verification:** `docker compose config` parses; after `make dev-rebuild`,
`curl -kI https://<host>/health` shows the HSTS header; a >26 MB POST to any
route is rejected by Caddy with 413.
**Docs:** update the Caddyfile snippet in `__docs/DOCKER.md` to match.
**CHANGELOG (Added):** "Reverse proxy now sends Strict-Transport-Security and
caps request bodies at 26 MB."

### SEC-07 + SEC-08 — Compose hardening: privileges, healthchecks, startup order

**Severity:** Medium. **Threat:** TS-8 blast-radius reduction; deployment
robustness.

**File:** `docker-compose.yml` (mirror the same changes in
`docker-compose.image.yml`, which runs the prebuilt image; do **not** touch
`docker-compose.dev.yml` except where stated in SEC-05):

1. **All four services** — add:
   ```yaml
   security_opt:
     - no-new-privileges:true
   ```
   (Safe with postgres's root→postgres drop and Caddy: dropping privileges is
   allowed; only *gaining* them is blocked.)
2. **caddy** — add:
   ```yaml
   cap_drop: [ALL]
   cap_add: [NET_BIND_SERVICE]
   ```
3. **backend** — add:
   ```yaml
   read_only: true
   tmpfs:
     - /tmp
   ```
   Do not set `read_only` on postgres/keycloak/caddy (they write to their
   image filesystems/volumes at startup).
4. **keycloak** — enable health endpoints and add a healthcheck (Keycloak 26
   serves health on management port 9000; the image has bash but no curl):
   ```yaml
   environment:
     KC_HEALTH_ENABLED: "true"
     # ... existing vars unchanged
   healthcheck:
     test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.0\r\n\r\n' >&3 && grep -q '200' <&3"]
     interval: 15s
     timeout: 5s
     retries: 10
     start_period: 60s
   ```
5. **backend** — add a TCP healthcheck (temurin JRE image has bash, no curl)
   and tighten the dependency:
   ```yaml
   healthcheck:
     test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8080"]
     interval: 15s
     timeout: 5s
     retries: 5
     start_period: 30s
   depends_on:
     postgres: { condition: service_healthy }
     keycloak: { condition: service_healthy }
   ```

**Verification:** `docker compose config` validates; `make dev-rebuild` (dev
stack inherits base file) brings all containers to healthy/running;
`docker inspect` shows `NoNewPrivileges` and caddy's cap set; backend still
serves `/health` through Caddy; a full login + API round-trip works (use
`__docs/HOMELAB-TESTING.md` smoke steps if a homelab stack is available,
otherwise the dev stack).
**Docs:** update the compose sketch in `__docs/DOCKER.md`.
**CHANGELOG (Changed):** "Containers run with no-new-privileges (Caddy with
minimal capabilities, backend with read-only filesystem); Keycloak and the
backend now have healthchecks and the backend waits for Keycloak readiness."

### SEC-09 — Pin images by digest

**Severity:** Medium. **Threat:** TS-8 (tag-mutation supply chain).

Resolve each digest **at implementation time** (do not copy digests from this
document) with:

```
docker buildx imagetools inspect postgres:16-alpine
docker buildx imagetools inspect quay.io/keycloak/keycloak:26.1
docker buildx imagetools inspect caddy:2-alpine
docker buildx imagetools inspect eclipse-temurin:21-jdk
docker buildx imagetools inspect eclipse-temurin:21-jre
```

Use each **manifest-list digest** (the top-level digest, which stays
multi-arch — required because releases build linux/amd64 + linux/arm64).

**Files:** `docker-compose.yml`, `docker-compose.image.yml` (postgres,
keycloak, caddy), `server/Dockerfile` (jdk + jre), `server/Dockerfile.dist`
(jre). Format: keep the tag for readability, e.g.
`image: postgres:16-alpine@sha256:<digest>`.

Also pin the gitleaks container (SEC-14 overlap):
`.github/workflows/gitleaks.yml` — change `zricethezav/gitleaks:latest` to
`zricethezav/gitleaks:v8.18.4` (or the current latest release tag at
implementation time — a tag is sufficient here since it runs in CI on public
tooling; digest optional).

**Verification:** `make dev-build` and `docker compose -f docker-compose.yml config`
succeed; CI gitleaks job passes on a test branch.
**Docs:** note the digest-pinning convention in `__docs/DOCKER.md`.
**CHANGELOG (Changed):** "All container base images are pinned by digest to
prevent tag-mutation supply-chain attacks."

### SEC-10 — Cap offline session lifetime in Keycloak

**Severity:** Medium. **Threat:** TS-4/TS-7 — a stolen offline refresh token
currently lives forever if used at least monthly.

**Decision:** absolute ceiling of **90 days** (re-login roughly quarterly —
the trade-off already contemplated in `KEYCLOAK.md`).

**File:** `infra/keycloak/realm-export.json`:
- `"offlineSessionMaxLifespanEnabled": true`
- `"offlineSessionMaxLifespan": 7776000` (90 days in seconds — set/replace the key)

**Operator note (include in the task's commit message and SETUP-SERVER.md):**
the realm export only applies on first import. On an existing deployment, set
the same values in Admin Console → Realm settings → Sessions → Offline
settings.

**Docs:** update the Session & Token Lifetimes table in `__docs/KEYCLOAK.md`
("Offline session max: 90 days") and the same table in `CLAUDE.md`'s Key
Numbers if present (refresh token row: "long-lived `offline_access`, 90-day max").
**CHANGELOG (Changed):** "Offline sessions now expire after at most 90 days;
apps re-login quarterly at most."

### SEC-14 — Dependabot for Gradle, Actions, and Docker

**Severity:** Low. **Threat:** TA-4 (stale vulnerable dependencies).

**File (new):** `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
  - package-ecosystem: docker
    directory: /server
    schedule:
      interval: weekly
  - package-ecosystem: docker
    directory: /
    schedule:
      interval: weekly
```

Dependabot PRs land on the default branch and flow through the existing
branch-rules CI (they target `main`; if `branch-rules.yml` blocks
`dependabot/*` sources, extend its allowed-source list for `dependabot/**` →
`main` — check the workflow and adjust only if needed).

**Verification:** file passes `dependabot.yml` schema (GitHub validates on
push); Dependabot appears under the repo's Insights → Dependency graph.
No CHANGELOG entry (tooling only).

---

## Phase 4 — Documentation & spec sync

### SEC-DOC-1 — Threat model revision

**File:** `__docs/threat-model.md` — append to the Revision History table:
`| 1.2 | 2026-07-14 | Full-stack security audit; hardening plan (SECURITY-HARDENING-PLAN.md): per-client rate limiting, sync pull pagination, HSTS, container privilege reduction, image digest pinning, 90-day offline-session cap, Android backup disabled |`
Also update TS-3's mitigations line "Automated tests asserting cross-user
access is rejected (403, not 404)" → "(404, not 403)" — the current text
contradicts the implemented (and correct) invariant.

### SEC-DOC-2 — Documentation index

**File:** `CLAUDE.md` — add a row to the Documentation Index table:
`| __docs/SECURITY-HARDENING-PLAN.md | Security audit baseline + hardening tasks and their status | Any security-sensitive change |`

Keep this plan's task statuses updated (`TODO` → `DONE (commit <sha>)`) as
tasks land.

---

## Phase 5 — Deferred, optional, high-risk-of-regression (implement last, only if asked)

### SEC-OPT-1 — Android R8/minification (deferred by default)

`isMinifyEnabled = false` on release keeps full symbol names in the APK. The
benefit is modest (no secrets ship in the APK; obfuscation is not a security
boundary) and the regression risk is high (kotlinx-serialization, AppAuth,
SQLCipher, Compose reflection). **Do not implement unless the user explicitly
asks.** If asked: enable `isMinifyEnabled = true` + `isShrinkResources = true`
on the release build type, add keep rules for kotlinx-serialization
(`-keepattributes *Annotation*` + serializer keeps per the kotlinx docs),
AppAuth (`-keep class net.openid.appauth.** { *; }`), and SQLCipher
(`-keep class net.zetetic.** { *; }`), then verify a signed release build
on-device end-to-end (login, unlock, log a day, sync, export) using the
runbook in the `reference_android_ondevice_testing` notes before merging.

---

## Accepted risks / explicitly deferred — DO NOT "FIX"

| Item | Rationale |
|---|---|
| `APP_ENCRYPTION_KEY` rotation / re-encryption tooling | Requires a migration pass over ciphertexts; separate feature, not hardening |
| Encryption key zeroization in JVM memory | JVM gives no reliable guarantee; OS-compromise is out of scope (threat model §7) |
| PostgreSQL Row Level Security | Multi-user is now supported, so RLS is no longer tautological — but app-layer row scoping remains the **primary** control and is covered by cross-user integration tests. RLS is worth adding as **defense-in-depth** (a DB-level backstop if an app query ever forgets its `user_id` filter); consciously re-deferred as hardening, not a fix, and tracked in issue #78 rather than done inline here |
| Keycloak admin token caching | Account deletion is a rare, once-ever operation; caching adds token-lifetime handling for no measurable gain |
| CORS plugin on the server | There are no browser clients; adding CORS would only widen the surface |
| Import content-type validation | Auth + 25 MB cap + strict deserialization already bound the risk; content-type is attacker-controlled anyway |
| Desktop OS-level screen-capture exclusion | No portable JVM API; documented as best-effort in ARCHITECTURE-client.md |
| `androidx-security-crypto` alpha version | No stable release exists; the alpha is the ecosystem-standard choice |
| Gradle dependency verification metadata (`verification-metadata.xml`) | High maintenance for a solo project; wrapper checksum + Dependabot + pinned catalogs are the chosen mitigations |
| Rate-limit 429 body in `ApiError` shape | The error-code table in `:core`/API.md is fixed; a plain 429 is acceptable |
| Offline unlock can't see server-side revocation until reconnect | Inherent to offline-first Mode C; app-lock gate still applies; documented in ARCHITECTURE-client.md |

---

## Global verification (run after each phase, and fully at the end)

1. `./gradlew ktlintCheck detekt` — clean.
2. `./gradlew :core:allTests :app:shared:jvmTest` — green.
3. `./gradlew :server:test` — green. (Local Windows quirks: Docker
   `api.version` system property + kotlinx-datetime pin — see the project's
   server-test notes; Testcontainers needs Docker running.)
4. `./gradlew :app:androidApp:assembleDebug :app:desktopApp:packageDistributionForCurrentOS` — build.
5. `docker compose -f docker-compose.yml config` and
   `docker compose -f docker-compose.yml -f docker-compose.dev.yml config` — valid.
6. Dev-stack smoke (if Docker available): `make dev-rebuild`; all containers
   healthy; `curl -k https://localhost/health` → 200 with HSTS header;
   login flow from the desktop app against the dev stack still completes.
7. `CHANGELOG.md` contains every entry listed in the tasks implemented.
8. CI on the PR branch: build-checks, semgrep, gitleaks, validate-branch-flow
   all green.

## Security regression checklist (assert still true at the end)

- [ ] Only `/health` and `GET /api/v1/version` respond without a JWT.
- [ ] Cross-user reads return `RESOURCE_NOT_FOUND` (404), never `FORBIDDEN`.
- [ ] `daily_logs.notes` and `daily_log_sex.encrypted_payload` are ciphertext
      in the DB (`iv:ct:tag` base64 triplet) — check via a psql query in the
      dev stack or the existing integration tests.
- [ ] No request/response bodies, tokens, user_ids, or health data in server
      or client logs (grep test output for a known token/notes string).
- [ ] `git ls-files` contains no `.env`, `*.jks`, keystore.properties, or certs.
- [ ] Prod compose publishes only 80/443 on caddy.
- [ ] Keycloak admin console unreachable through Caddy
      (`curl -k https://<host>/admin/` → 404/403 from Caddy, not Keycloak).
