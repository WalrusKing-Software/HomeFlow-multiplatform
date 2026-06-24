# IMPLEMENTATION-PHASES-modular-offline.md — Decoupled Deployment & Offline Sync

This is a **standalone, comprehensive build plan** for the next major capability of
HomeFlow: letting each part of the system be installed independently, and making the
clients work offline and reconcile with a self-hosted server. It is deliberately far
more detailed than `IMPLEMENTATION-PHASES.md` (phases 0–10) because this is the
"hard 20%" — local persistence, client-side encryption-at-rest, and a real sync
engine with conflict resolution. Read this whole file before starting any phase here.

> **Prerequisite reading per phase** (in addition to `CLAUDE.md`): `SHARED-MODULE.md`,
> `ARCHITECTURE-server.md`, `ARCHITECTURE-client.md`, `data-model.md` (+ the sex
> encryption addendum), `API.md`, `KEYCLOAK.md`, `threat-model.md`, `TESTING.md`.
> Phases 0–10 in `IMPLEMENTATION-PHASES.md` are **done**; this plan starts at Phase 11.

---

## 0. The product goal (what we are building and why)

Today HomeFlow is **online-only**: the clients are thin and every read/write goes to
the Ktor server over HTTP; there is deliberately *no health data at rest on a client*.
That is great for security but forces a server to exist before anyone can use anything.

The goal is **modular deployment** — a user installs only the parts they need, and can
grow into more parts later without losing data:

- **A. Local-only.** Download the desktop (or Android) app, run it with **no server**,
  data stored locally on the device. Fully functional period tracker, single user.
- **B. Server-connected.** Stand up the server on a homelab box; point one or more apps
  at it. The server is the source of truth; apps are online clients (today's model,
  plus a one-time migration that lifts any pre-existing **local** data up to the server).
- **C. Offline-capable + sync.** Every app keeps a first-class local store and works
  fully offline; changes reconcile with the server when connectivity returns, so a
  desktop and an Android app pointed at the same server converge to the same data.

The canonical user story we must satisfy: *"I have only a desktop today → I use it
local-only. Later I stand up a server and get an Android phone → I migrate my desktop
data to the server, point both apps at it, and they stay in sync — including offline."*

### Why this is feasible without a rewrite

The `:core` module already holds the entire **contract + domain math** (DTOs, cycle
phase/analytics, validation) as pure Kotlin. The clients render off a single
`HomeFlowRepository`. The expensive, divergence-prone logic is therefore already
shared. What is missing for the modes above is: a **local data store**, the **business
rules currently trapped in the server's service layer**, **client-side at-rest
encryption**, a **mode/config selection**, and a **sync engine**. This plan adds those
in dependency order, each phase leaving the app shippable.

---

## 1. Target architecture (the end state all phases build toward)

```
                       ┌──────────────────────────────────────────┐
   Compose UI  ──────► │ HomeFlowRepository  (UI-facing, unchanged)│
   (screens)           └───────────────────────┬──────────────────┘
                                                │ depends on an INTERFACE
                                   ┌────────────▼─────────────┐
                                   │   HomeFlowDataSource      │  (new seam, :app:shared/commonMain)
                                   │   (read + write surface)  │
                                   └───┬───────────────────┬───┘
                            implements │                   │ implements
                 ┌──────────────────────▼──┐   ┌───────────▼─────────────────────┐
                 │  RemoteDataSource        │   │  LocalDataSource                │
                 │  (today's HomeFlowApi-   │   │  (SQLDelight store + :core      │
                 │   backed HTTP calls)     │   │   domain-service rules +        │
                 │  → Mode B                │   │   client-side crypto)           │
                 └──────────────────────────┘   │  → Mode A / Mode C primary      │
                                                └───────────────┬─────────────────┘
                                                                │ Mode C only
                                                       ┌────────▼─────────┐
                                                       │  SyncEngine      │  pushes/pulls deltas
                                                       │  (outbox + pull  │  ⇆ server /sync/*
                                                       │   cursor + LWW)  │
                                                       └──────────────────┘
```

- The **UI never knows the mode.** It binds to `HomeFlowDataSource`; DI picks the
  implementation at startup from the user's chosen mode.
- **Pure business rules move into `:core`** as a *domain-service* layer so the server
  and `LocalDataSource` run *identical* logic (the same anti-divergence argument
  `SHARED-MODULE.md` already makes for cycle math). Only DB binding and at-rest
  encryption stay per-side.
- **Mode C = Mode A's local store + Mode B's server + a reconciler.** Nothing from A is
  thrown away to reach C; C is purely additive.

---

## 2. Cross-cutting foundational decisions (decided ONCE, applied in every phase)

These are cheap to honor up front and expensive to retrofit. **Every phase below must
respect them.** They are listed here so they are not re-litigated per phase.

### D1 — Client-generated UUIDs for every syncable entity
All entity primary keys (cycles, daily-log anchors, sub-log rows, pain logs/locations)
are **UUIDv4 generated at creation time by whoever creates the row** — client in
local/offline mode, server otherwise. Rationale: an offline-created row needs a stable
identity *before* it ever reaches a server, or references break and dedup is impossible.
The existing import path already must accept foreign IDs, so this aligns. *Action in
Phase 11:* stop relying on server-issued IDs in the write path; the create DTOs gain an
optional client-supplied `id` the server honors (and validates as a UUID).

### D2 — `updatedAt` (+ tombstone) on every syncable row, both stores
Every syncable row carries `created_at`, `updated_at`, and a soft-delete
(`deleted_at TIMESTAMPTZ NULL`, or `is_deleted BOOLEAN`). `updated_at` is the
last-write-wins comparator. **Note:** today several *child* tables
(`daily_log_emotions`, `daily_log_sleep`, etc.) have only `created_at` and are
replaced wholesale; for sync we treat the **anchor + its sub-logs as one syncable
aggregate keyed by the anchor** (see D7) so we don't need per-option timestamps.
Anchor-level (`daily_logs`, `cycles`, `pain_logs`, `daily_log_sex`,
`user_dashboard_preferences`) already have or will get `updated_at`.

### D3 — Deletes are tombstones, never hard deletes (in syncable scope)
A row deleted on device A must propagate as a delete, not silently reappear from device
B. Soft-delete + a deletion timestamp; the sync layer ships tombstones. Hard
`DELETE`/purge happens only for **account deletion** (which is global and intentional)
and for **local compaction** of long-tombstoned rows after they are confirmed synced.

### D4 — Reference data is keyed by **slug**, not UUID, at every cross-store boundary
**Critical, discovered from the seed migration:** `ref_symptom_options` /
`ref_pain_locations` IDs are `gen_random_uuid()` — *random per database*. A local store
and a server (or two servers) will assign different UUIDs to the same `mood_swings`
option. Therefore:
- Reference data is **never synced** — it is a bundled constant seeded identically on
  both sides (same slugs).
- Any payload that crosses stores (sync push/pull, the `homeflow` export/import format)
  references options/locations **by `slug`** (e.g. `"emotions/mood_swings"`), not by the
  local UUID. Each side maps `slug ↔ local UUID` on the way in/out.
- Inside a single store, the existing UUID FKs stay; the slug mapping is a boundary
  concern only. *Action in Phase 12 (export/import) and Phase 15 (sync).*

### D5 — Local at-rest encryption with a device-rooted key (clients never share the server key)
Local mode reverses "no health data at rest." Health data persisted locally is
encrypted at rest with a **client-side key that never leaves the device** and is
**distinct** from the server's `APP_ENCRYPTION_KEY`. Default approach: encrypt the whole
local DB (SQLCipher); the data-encryption key (DEK) is wrapped by a key rooted in the OS
secure store (Android Keystore; desktop OS keychain / passphrase-derived KEK). Fallback
if cross-platform SQLCipher proves painful: application-layer column encryption mirroring
the server (notes + sex payload at minimum, ideally all health columns). The plaintext↔
ciphertext boundary stays **inside `LocalDataSource`** — never in `:core`, the UI, or the
sync DTOs. Sync still exchanges **plaintext over TLS** (consistent with today's model:
the client never holds the server key, the server never sees the client key).

### D6 — One UI-facing repository, mode chosen by DI
Introduce `HomeFlowDataSource` (interface). `HomeFlowRepository` depends on it, not on
`HomeFlowApi`. The running mode is a persisted setting; a small `AppMode`/bootstrap
selects the implementation. No `if (mode == …)` scattered through screens — the branch
lives once, at composition root.

### D7 — Lift *pure* server-service rules into `:core`; keep DB + crypto per-side
The server's service layer holds rules that local mode also needs: cycle **auto-close**
(new cycle closes the prior open one with `end = start − 1 day`), the **daily-log
anchor + sub-log replace** semantics, **option-id validation against a category**, the
**409-on-duplicate-day** rule, and **"pain log cleared when no locations remain."** The
*pure* parts (date math, validation, "what writes does this edit imply") move to a new
`:core` `service/` (domain-service) package so server and `LocalDataSource` share one
implementation. The *impure* parts (Exposed transactions, SQLDelight transactions,
encryption) stay per-side and call the shared rules. **Reference, do not duplicate:**
`CyclesService.createCycle`, `DailyLogsService.createAnchor/updateNotes`,
`DailyLogSubsService.replaceMulti/replaceSingle/setSex/setPain`.

---

## 3. Phase overview

| Phase | Title | Mode delivered | User-visible? |
|---|---|---|---|
| **11** | Repository seam + shared domain-service extraction | — (refactor; no behavior change) | No |
| **12** | Native export/import + slug-keyed boundary format | groundwork for B-migration & C | Yes (export/import) |
| **13** | Local persistence engine (SQLDelight + at-rest crypto) | — (internal capability, tested) | No |
| **14** | Local-only app mode | **A** | **Yes** |
| **15** | Server-connected mode + "adopt a server" migration | **B** | **Yes** |
| **16** | Offline sync engine | **C** | **Yes** |

> The original framing was "3 phases (11/12/13) ≈ 3 modes." Implementation is split into
> six so each lands on a stable, testable base — Modes A/B/C are delivered by phases 14,
> 15, 16 respectively; 11–13 are the shared foundation they all stand on. Do not start a
> phase until the prior one passes its **Done when** checklist.

---

## Phase 11 — Repository seam + shared domain-service extraction

> **This phase is written as an executable spec.** Every decision is closed — there are
> no "your call" choices. Implement exactly what is written; do not add scope. If
> something here contradicts the current code, STOP and report it rather than guessing.

**Goal:** introduce a `HomeFlowDataSource` interface between `HomeFlowRepository` and the
HTTP client, rename the concrete HTTP implementation to `RemoteDataSource`, extract the
**one** server rule a future local store will re-need (cycle auto-close) into `:core`, and
add a dormant client-supplied-`id` field to the two create-DTOs (groundwork for D1).
**Net behavior change for users and the API: zero.** This phase only moves and renames.

### Why first
A `LocalDataSource` (Phase 13) cannot be added while the client's only data path is
welded to `HomeFlowApi`/Ktor and a key business rule is welded to Exposed in `server/`.
Insert the seam and lift the rule first, prove every existing test still passes, then
build the local store against the seam.

### Key architectural fact (do not violate)
`HomeFlowRepository` sits **above** the seam. All of its shaping, label cache, and
edit-diffing logic (`changedWrites`, `saveDay`, `resolveDay`, `loadDayEditor`,
`ensureRefData`, the `optional()` 404→absence mapping) **stays exactly where it is** and
is automatically reused by both modes because it calls the data source's per-route
methods. Therefore **almost nothing moves into `:core`** — only `autoCloseEndDate`. Do
**not** move repository shaping/diffing anywhere. Do **not** move option-id validation in
this phase (that is Phase 13, when `LocalDataSource` needs it).

### Decisions (closed)
- **D-11.1** The concrete HTTP class `HomeFlowApi` is **renamed to `RemoteDataSource`**
  (file `HomeFlowApi.kt` → `RemoteDataSource.kt`). Rationale: the rest of this plan
  refers to `RemoteDataSource`; the rename is a mechanical ~7-site change the compiler
  fully verifies.
- **D-11.2** The seam interface is named `HomeFlowDataSource`, lives in
  `app/shared/src/commonMain/kotlin/org/homeflow/app/shared/data/HomeFlowDataSource.kt`,
  and declares **exactly the 20 methods currently on `HomeFlowApi`, with identical
  signatures** (verb-per-route; keep the `endpoint: String` params as-is). No new methods.
- **D-11.3** The new `:core` rule lives at
  `core/src/commonMain/kotlin/org/homeflow/core/service/CycleRules.kt`, package
  `org.homeflow.core.service`, function `autoCloseEndDate(newCycleStart: LocalDate): LocalDate`.
  It must be byte-for-byte equivalent to the current inline rule (`start − 1 day`).
- **D-11.4** `CreateCycleRequest` and `CreateDailyLogRequest` gain `val id: String? = null`
  (last field, with default → wire-compatible). The **server honors it when present**
  (validating it is a UUID, 400 otherwise) and generates one when absent. The **client
  does NOT send it yet** — the `RemoteDataSource.createCycle`/`createDailyLog` signatures
  are unchanged. The field is dormant-on-client, exercised by a server test only.

### The interface to create (copy verbatim)
`HomeFlowDataSource.kt`:
```kotlin
package org.homeflow.app.shared.data

import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.UserDto

/**
 * The per-route read/write surface the repository renders off. Two implementations:
 * [RemoteDataSource] (HTTP, this phase) and LocalDataSource (SQLDelight, Phase 13).
 *
 * BEHAVIORAL CONTRACT every implementation MUST honor (today's HTTP behavior):
 * - [getCurrentCycle], [getDailyLog]: absence → ApiResult.Failure(RESOURCE_NOT_FOUND).
 * - [createDailyLog]: a log already exists for the date → ApiResult.Failure(CONFLICT).
 * - [putOptionIds]/[putOptionId]/[patchNotes]/[putPain]: no anchor for the date →
 *   ApiResult.Failure(RESOURCE_NOT_FOUND).
 * - analytics getters: always ApiResult.Success (null fields signal thin data); never NOT_FOUND.
 * - [createCycle]: auto-closes any currently open cycle (end = start − 1 day).
 */
interface HomeFlowDataSource {
    suspend fun getMe(): ApiResult<UserDto>
    suspend fun deleteAccount(): ApiResult<Unit>

    suspend fun getCycles(): ApiResult<CyclesResponse>
    suspend fun getCurrentCycle(): ApiResult<CycleDto>
    suspend fun createCycle(startDate: String): ApiResult<CycleDto>
    suspend fun closeCycle(cycleId: String, endDate: String): ApiResult<CycleDto>

    suspend fun getDailyLog(date: String): ApiResult<DailyLogDto>
    suspend fun createDailyLog(date: String, cycleId: String): ApiResult<Unit>
    suspend fun putOptionIds(date: String, endpoint: String, optionIds: List<String>): ApiResult<Unit>
    suspend fun putOptionId(date: String, endpoint: String, optionId: String?): ApiResult<Unit>
    suspend fun patchNotes(date: String, notes: String?): ApiResult<Unit>
    suspend fun putPain(date: String, locations: List<PainLocationDto>): ApiResult<Unit>

    suspend fun getCycleStats(): ApiResult<CycleStatsDto>
    suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto>
    suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto>
    suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto>

    suspend fun getPreferences(): ApiResult<PreferencesDto>
    suspend fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse>

    suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse>
    suspend fun getPainRegions(): ApiResult<PainRegionsResponse>
}
```

### The `:core` rule to create (copy verbatim)
`CycleRules.kt`:
```kotlin
package org.homeflow.core.service

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * When a new cycle starts on [newCycleStart], any currently open cycle is auto-closed
 * with end_date = the day before. Shared by the server (CyclesService) and, in Phase 13,
 * LocalDataSource — so the auto-close rule has exactly one definition.
 */
fun autoCloseEndDate(newCycleStart: LocalDate): LocalDate = newCycleStart.minus(DatePeriod(days = 1))
```

### File manifest (exhaustive — create / rename / modify exactly these)

**Create (3):**
1. `core/src/commonMain/kotlin/org/homeflow/core/service/CycleRules.kt` — above.
2. `core/src/commonTest/kotlin/org/homeflow/core/service/CycleRulesTest.kt` — see Tests.
3. `app/shared/src/commonMain/kotlin/org/homeflow/app/shared/data/HomeFlowDataSource.kt` — above.

**Rename (1):**
4. `app/shared/src/commonMain/kotlin/org/homeflow/app/shared/data/HomeFlowApi.kt`
   → `RemoteDataSource.kt`. Inside: `class HomeFlowApi(` → `class RemoteDataSource(`, add
   `: HomeFlowDataSource` to the class header, and add `override` to **all 20** `suspend fun`s.
   Keep every method body byte-for-byte identical. Keep the KDoc (update the class name).

**Modify (8):**
5. `core/.../dto/CycleDtos.kt` — `CreateCycleRequest` gains `val id: String? = null` (after `startDate`).
6. `core/.../dto/DailyLogDtos.kt` — `CreateDailyLogRequest` gains `val id: String? = null` (after `cycleId`).
7. `server/.../modules/cycles/CyclesService.kt` —
   - replace `import kotlinx.datetime.DatePeriod` + `import kotlinx.datetime.minus` usage:
     change `val previousEndDate = startDate.minus(DatePeriod(days = 1))` to
     `val previousEndDate = autoCloseEndDate(startDate)` and add
     `import org.homeflow.core.service.autoCloseEndDate` (remove the now-unused
     `DatePeriod`/`minus` imports if nothing else uses them);
   - parse the optional id: `val id = request.id?.let { it.toUuidOrNull() ?: throw ValidationException("Invalid id: expected a UUID.") }`
     (add `import org.homeflow.lib.ValidationException` if absent), and pass `id` to the repo.
8. `server/.../modules/cycles/CyclesRepository.kt` — `insertClosingOpen` gains a leading-defaulted
   param `id: UUID = UUID.randomUUID()`; replace the internal `val id = UUID.randomUUID()` with use
   of the param (delete that local line; the param name `id` already matches the insert block).
9. `server/.../modules/dailylogs/DailyLogsService.kt` — in `createAnchor`, parse `request.id`
   the same way and pass it to `insertIfAbsent`.
10. `server/.../modules/dailylogs/DailyLogsRepository.kt` — `insertIfAbsent` gains
    `id: UUID = UUID.randomUUID()`; replace the internal `val id = UUID.randomUUID()` with the param.
11. `app/shared/.../data/HomeFlowRepository.kt` — constructor param type `HomeFlowApi` →
    `HomeFlowDataSource` (keep the name `api`). Update the `[HomeFlowApi]` reference in the
    `optional()` KDoc to `[HomeFlowDataSource]`.
12. `app/shared/.../auth/AuthController.kt` — change `import ...data.HomeFlowApi` to
    `import ...data.RemoteDataSource` (+ `import ...data.HomeFlowDataSource`); change
    `private val api = HomeFlowApi(http)` to `private val api: HomeFlowDataSource = RemoteDataSource(http)`.
    The `HomeFlowRepository(api)` call is unchanged.

**Modify tests (2):**
13. `app/shared/src/commonTest/.../data/HomeFlowRepositoryTest.kt` — `HomeFlowRepository(HomeFlowApi(client))`
    → `HomeFlowRepository(RemoteDataSource(client))`.
14. `app/shared/src/commonTest/.../data/HttpClientAuthTest.kt` — `HomeFlowApi(client)` →
    `RemoteDataSource(client)` (3 occurrences).

**Docs (1):**
15. `__docs/ARCHITECTURE-client.md` — update the two `HomeFlowApi.kt` mentions (source-layout
    block + the "uses the Ktor client" paragraph) to note the `HomeFlowDataSource` interface
    with `RemoteDataSource` as the HTTP implementation.

### Tests to add
- **`CycleRulesTest`** (`:core`, kotlin.test): `autoCloseEndDate(LocalDate(2024,3,10)) == LocalDate(2024,3,9)`;
  year-boundary `autoCloseEndDate(LocalDate(2024,1,1)) == LocalDate(2023,12,31)`.
- **Server create-with-id** (extend the existing cycles & daily-log integration tests, or add
  `CreateWithClientIdTest`, Testcontainers + in-process RS256 as the existing suites do):
  - `POST /api/v1/cycles` body `{"startDate":"2024-02-01","id":"<valid-uuid>"}` → response
    `CycleDto.id` equals the supplied UUID; the row in PG has that id.
  - body with `"id":"not-a-uuid"` → `400 VALIDATION_ERROR`.
  - body with no `id` → success with a server-generated UUID (just assert it parses as a UUID).
  - Same three for `POST /api/v1/daily-logs` (`id` honored / 400 / generated).

### Guardrails — do NOT
- Do NOT change any HTTP method, path, request/response JSON, status code, or error mapping.
- Do NOT move or alter `HomeFlowRepository`'s shaping/diff/cache logic — it stays above the seam.
- Do NOT refactor the server's `validateOptionIds`/`validateOptionId` (Phase 13).
- Do NOT make `RemoteDataSource.createCycle`/`createDailyLog` send the new `id` field.
- Do NOT add SQLDelight, a local store, `LocalDataSource`, or any mode concept here.
- Do NOT add a Ktor `Logging` plugin or touch encryption.
- Keep `:core` pure — `CycleRules.kt` imports only `kotlinx.datetime`.

### Done when (all automated; run from repo root)
- [ ] `./gradlew :core:allTests` green, including `CycleRulesTest`.
- [ ] `./gradlew :server:check` green — **all** existing server suites (`AuthUsersTest`,
  cycles, daily-log, subs, analytics) pass unchanged, plus the new create-with-id test.
- [ ] `./gradlew :app:shared:check` green (ktlint + detekt + JVM and Android host tests);
  `HomeFlowRepositoryTest` and `HttpClientAuthTest` updated and passing.
- [ ] `./gradlew :app:androidApp:assembleDebug` produces an APK (both entry points still compile).
- [ ] `git grep -n "class HomeFlowApi"` → **no results** (rename complete).
- [ ] `git grep -n "fun autoCloseEndDate"` → **exactly one** definition, in `core/.../service/CycleRules.kt`.
- [ ] `git grep -n "DatePeriod(days = 1)"` → no longer present in `CyclesService.kt` (rule now via `:core`).
- [ ] Manual diff review confirms `RemoteDataSource`'s 20 method bodies are byte-identical to the
  former `HomeFlowApi` (rename + `override` only).

### Risks / stop-conditions
- If adding `: HomeFlowDataSource` surfaces a signature mismatch, the interface block above is
  the source of truth — fix the interface to match the real method, do not change a method body.
- If `toUuidOrNull`/`ValidationException` are not importable in a server service, check
  `server/.../lib/` for the actual names before inventing one; STOP and report if absent.

---

## Phase 12 — Native export/import (`homeflow` format)

> **Executable spec.** Every decision is closed. Implement exactly this; do not add the
> other import sources or new error codes. If the code contradicts the spec, STOP and report.

**Goal:** implement the `homeflow` native export/import on the server — `GET /api/v1/export`
(format=json) and `POST /api/v1/import?source=homeflow` — using the **slug-and-date-keyed,
UUID-free** interchange shape already specified in `API.md` §Import & Export. This is the
migration vehicle for Mode B (Phase 15: *local export → server import*) and the
serialization basis for Mode C. The DTOs land in `:core` so the future local store reuses them.

### Key fact (authoritative shape)
`API.md` already defines the native export as **natural-key + slug** with **zero UUIDs**:
cycles keyed by `startDate`, each day references its cycle by `cycleStartDate`, options are
slug arrays, pain is `{location, severity}`. The hardened format below matches `API.md`
exactly (it supersedes the earlier envelope sketch that carried UUIDs/preferences).

### Decisions (closed)
- **D-12.1 — Top-level shape:** `{ "homeflow_export": 1, "cycles": [...], "days": [...] }`.
  `homeflow_export` is an **integer version marker** (1). camelCase fields elsewhere (the
  server JSON is `encodeDefaults=true, explicitNulls=true, ignoreUnknownKeys=true`).
- **D-12.2 — Scope:** implement **only** `source=homeflow` and `format=json`. `clue`,
  `apple_health`, `csv` and the non-json export formats are a **separate later feature** —
  for any other `source`/`format` return **`400 VALIDATION_ERROR`** ("source not supported").
- **D-12.3 — Errors:** use the existing 6-code `ErrorCode` enum only. Bad/missing source,
  unknown format, AND a structurally invalid file all → **`400 VALIDATION_ERROR`**. Do
  **NOT** introduce `IMPORT_ERROR`/422 (that would expand the canonical contract in
  `CLAUDE.md`/`ApiError.kt`). Reconcile `API.md` to say 400.
- **D-12.4 — Export excludes preferences** (the dashboard order). `API.md`'s enumerated
  shape is health data only; preferences are not health data and are re-orderable. (Drop
  "preference order" from Phase 15's round-trip done-when accordingly.)
- **D-12.5 — Mapping + crypto live in the server `ImportExportService`.** Defer the pure
  `:core` mapper until Phase 14 (local export) gives it a second consumer — do not build the
  shared abstraction now (same "don't over-extract" lesson as Phase 11).
- **D-12.6 — Reuse the existing services for import writes** (`DailyLogsService.createAnchor`/
  `updateNotes`, `DailyLogSubsService.setEmotions/…/setSex/setPain`) so validation +
  encryption are identical to normal writes. Resolve slugs→option-id strings first, then
  call them. Do **not** write health rows or ciphertext directly.
- **D-12.7 — Idempotency / additive:** cycles keyed by `(userId, startDate)` (exact match →
  reuse, else insert); days keyed by `(userId, logDate)` (anchor exists → skip the whole day,
  `dailyLogsSkipped++`). Never overwrite existing data.
- **D-12.8 — Lenient resolution:** unknown option/location **slugs are dropped and counted**
  (a `warnings` entry), not fatal. A day whose `cycleStartDate` matches no cycle, or whose
  date falls outside that cycle (`createAnchor` throws `VALIDATION_ERROR`), is **skipped**
  (`dailyLogsSkipped++` + warning). Notes failing `validateNotes` → notes dropped + warning.
- **D-12.9 — Upload guard:** cap the import file at **25 MB**; larger → `400 VALIDATION_ERROR`.

### Export ⇄ category mapping (use exactly)
| Export day field | Category slug | Kind |
|---|---|---|
| `emotions` | `emotions` | multi |
| `sleep` | `sleep_quality` | multi |
| `discharge` | `discharge` | multi |
| `skin` | `skin` | multi |
| `digestion` | `digestion` | multi |
| `mind` | `mind` | multi |
| `sex` | `sex` | multi (encrypted at rest) |
| `energy` | `energy` | single |
| `flow` | `blood_flow` | single |
| `collectionMethod` | `collection_method` | single |
| `pain[].location` | (pain locations, global) | per-location severity |

> Option slugs are **not** globally unique (`fine` is in both `emotions` and `skin`), so
> import resolves each value within **its category** via a `(categorySlug, optionSlug)→id`
> map. Pain **location** slugs are globally unique in the seed → a flat `locationSlug→id`
> map is correct (see stop-conditions).

### DTOs to add to `:core/dto/ImportExportDtos.kt` (copy verbatim; keep `ImportResultDto`)
```kotlin
import kotlinx.serialization.SerialName

@Serializable
data class HomeFlowExport(
    @SerialName("homeflow_export") val version: Int = 1,
    val cycles: List<ExportCycle>,
    val days: List<ExportDay>,
)

@Serializable
data class ExportCycle(
    val startDate: String,
    val endDate: String? = null,
)

@Serializable
data class ExportDay(
    val date: String,
    val cycleStartDate: String,
    val flow: String? = null,
    val collectionMethod: String? = null,
    val energy: String? = null,
    val emotions: List<String> = emptyList(),
    val sleep: List<String> = emptyList(),
    val discharge: List<String> = emptyList(),
    val skin: List<String> = emptyList(),
    val digestion: List<String> = emptyList(),
    val mind: List<String> = emptyList(),
    val sex: List<String> = emptyList(),
    val pain: List<ExportPain> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class ExportPain(
    val location: String,
    val severity: Int? = null,
)
```

### Server service shape (`ImportExportService`)
Constructor deps (all already in `AppDependencies`): `CyclesRepository`, `DailyLogsRepository`,
`DailyLogsService`, `DailyLogSubsService`, `RefDataRepository`, `Encryption`.

- `fun exportAll(principal): HomeFlowExport`
  - `cycles = cyclesRepository.findAllByUser(id)` → sort by `startDate` → `ExportCycle`.
    Build `cycleId→startDate` from these rows.
  - Build `optionId→slug` from `refDataRepository.symptomOptions()` and
    `locationId→slug` from `refDataRepository.painLocations()`.
  - For each anchor from **new** `dailyLogsRepository.findAllByUser(id)`: `assembleDay(id, date)`,
    map each category's `List<UUID>`→slugs via the table above; decrypt `notes`
    (`encryption.decrypt`) and `sex` (`Json.decodeFromString(ListSerializer(String.serializer()),
    encryption.decrypt(payload))` → option-id strings → slugs); map pain locations→slugs +
    severity; `cycleStartDate = cycleId→startDate[anchor.cycleId]`.
- `fun importHomeflow(principal, json: String): ImportResultDto`
  - Parse `HomeFlowExport`; on `SerializationException` → `ValidationException` (→400).
  - Build resolvers: `(categorySlug, optionSlug)→optionId` (join categories+options) and
    `locationSlug→locationId`.
  - Cycles: load `cyclesRepository.findAllByUser(id)` into `startDate→CycleRow`. For each
    `ExportCycle`: present → reuse; absent → `cyclesRepository.insertExplicit(...)`
    (`cyclesCreated++`); update the map.
  - Days: for each `ExportDay`: if `dailyLogsRepository.findByDate(id, date) != null` →
    `dailyLogsSkipped++`. Else resolve its cycle by `cycleStartDate` (missing → warn + skip);
    `try createAnchor` (out-of-range → warn + skip); then for each category resolve
    slugs→id-strings (unknown → warn + drop) and call the matching `subsService.setX(...)`
    /`setSex`; `updateNotes` if present (validation fail → warn + drop notes); `setPain` with
    resolved locations; `dailyLogsCreated++`.
  - Return `ImportResultDto(cyclesCreated, dailyLogsCreated, dailyLogsSkipped, warnings)`.

> Import is **not** one big transaction (each reused service call manages its own); that is
> fine because import is additive + idempotent — a re-run recovers from any partial failure.

### File manifest (exhaustive)
**Create (3):**
1. `server/.../modules/importexport/ImportExportService.kt` — above.
2. `server/.../modules/importexport/ImportExportRoutes.kt` — `fun Route.importExportRoutes(service)`,
   `authenticate(KEYCLOAK_AUTH) { route("/api/v1") { get("/export"){…}; post("/import"){…} } }`.
   Export sets `ContentDisposition: attachment; filename="homeflow-export.json"`. Import reads
   one multipart file part named `file` (`call.receiveMultipart()` → `PartData.FileItem`),
   enforces the 25 MB cap, passes text to the service. `format`/`source` validated per D-12.2/3.
3. `server/src/test/.../integration/ImportExportTest.kt` — mirror `CyclesDailyLogsTest`'s
   Testcontainers + in-process RS256 harness.

**Modify (5):**
4. `core/.../dto/ImportExportDtos.kt` — add the four DTOs above (keep `ImportResultDto`).
5. `server/.../modules/cycles/CyclesRepository.kt` — add
   `fun insertExplicit(userId: UUID, startDate: LocalDate, endDate: LocalDate?, id: UUID = UUID.randomUUID()): CycleRow`
   — a plain insert that sets both dates and **does NOT auto-close** any open cycle.
6. `server/.../modules/dailylogs/DailyLogsRepository.kt` — add
   `fun findAllByUser(userId: UUID): List<DailyLogRow>` (all anchors for the user, any order).
7. `server/.../Application.kt` — in `AppDependencies` construct
   `val importExportService = ImportExportService(cyclesRepository, dailyLogsRepository,
   dailyLogsService, dailyLogSubsService, refDataRepository, encryption)` and pass it into
   `configureRouting(...)`.
8. `server/.../plugins/Routing.kt` — add the `importExportService` parameter and call
   `importExportRoutes(importExportService)` inside `routing {}`.

**Docs (2):**
9. `__docs/API.md` — reconcile the Import & Export section: top-level marker is
   `"homeflow_export": 1`; invalid files → `400 VALIDATION_ERROR` (remove the `422 IMPORT_ERROR`
   line); note `clue`/`apple_health`/`csv` and non-json formats are not yet implemented (return 400).
10. `CHANGELOG.md` — Added: "Export all your data as a portable HomeFlow file and re-import it."

### Tests to add (`ImportExportTest`)
1. **Export is slug-keyed + decrypted:** seed an account via the API (cycle + anchor + emotions
   incl. `mood_swings`, sex incl. a slug, notes, pain w/ severity), `GET /export?format=json`
   → assert body has `"homeflow_export":1`; the day's `emotions` contains `"mood_swings"`;
   assert **no UUID** appears in any selection array (regex `[0-9a-f]{8}-[0-9a-f]{4}-`); `notes`
   and `sex` are plaintext slugs.
2. **Lossless round-trip into a fresh account:** import the exact export under a different `SUB`
   → `ImportResultDto.cyclesCreated`/`dailyLogsCreated` > 0; then `GET /export` on the fresh
   account and deep-compare `cycles` + `days` sets to the original.
3. **Idempotency:** re-import the same file → `cyclesCreated==0 && dailyLogsCreated==0 &&
   dailyLogsSkipped == days.size`; no duplicate days in PG.
4. **Unknown slug:** import a file with an `emotions` value `"not_a_real_slug"` → succeeds,
   `warnings` non-empty, that value absent, the day still created with valid values.
5. **Bad source:** `POST /import?source=clue` → `400 VALIDATION_ERROR`.
6. **Malformed file:** `POST /import?source=homeflow` with body `"{ not json"` → `400`.
7. **Unknown format:** `GET /export?format=csv` → `400 VALIDATION_ERROR`.

### Guardrails — do NOT
- Do NOT implement `clue`/`apple_health`/`csv` or non-json export — return `400` for them.
- Do NOT add a new `ErrorCode` (no `IMPORT_ERROR`/422). Use `VALIDATION_ERROR`.
- Do NOT export preferences; match `API.md`'s health-data-only shape.
- Do NOT emit option/location **UUIDs** in the wire format — slugs only.
- Do NOT write health rows or ciphertext directly; reuse the existing services (D-12.6) so the
  sex/notes encryption path is identical and unchanged.
- Do NOT log export/import request or response bodies (they are health data) — no body logging.
- Keep `:core` DTOs server-concern-free (serialization only).

### Done when (all automated; from repo root)
- [ ] `./gradlew :core:allTests` green (new DTOs compile + serialize).
- [ ] `./gradlew :server:check` green incl. `ImportExportTest` (all 7 cases above).
- [ ] Export selections assert as slugs (no-UUID regex passes); notes/sex plaintext.
- [ ] Round-trip into a fresh account is lossless (deep compare of `cycles`+`days`).
- [ ] Re-import is idempotent (zeros + skipped count); no duplicate days in PG.
- [ ] Unknown slug → `warnings` non-empty, remainder imported.
- [ ] `source=clue`, malformed file, `format=csv` each → `400 VALIDATION_ERROR`.
- [ ] `git grep -n "IMPORT_ERROR"` → no results (no new code added); `API.md` + `CHANGELOG.md` updated.

### Risks / stop-conditions
- **Ktor 3.5 multipart:** if the `PartData.FileItem` read-to-text API differs from expectation,
  adapt the read but keep the approach (`receiveMultipart()` → file part → text). If multipart
  needs a new dependency, STOP and report (it should be in `ktor-server-core`).
- **Reused service transactions:** if calling a sub-log service from the import causes a
  nested-transaction/connection error, STOP and report — do not bypass the service to write rows.
- **Pain location uniqueness:** before relying on a flat `locationSlug→id` map, confirm location
  slugs are globally unique in `V2__seed_ref_data.sql`. If a duplicate exists, STOP (pain needs
  region qualification, which the `{location}` shape can't express).
- `daily_log_sex` has a UNIQUE `(daily_log_id)` constraint — because import reuses `setSex`
  (which deletes-then-inserts the single row), duplicates can't occur; do not insert it manually.

---

## Phase 13 — Local persistence engine (SQLDelight + at-rest encryption)

**Goal:** a fully-working **local store** in `:app:shared` — schema, a `LocalDataSource`
implementing `HomeFlowDataSource` (Phase 11 seam) backed by SQLDelight, ref-data seeded
locally, and at-rest encryption (**D5**). Built and **unit/integration-tested in
isolation**; not yet surfaced as a user mode (that is Phase 14). This is the single
biggest net-new piece.

### Background / why
Mode A and the offline half of Mode C both stand on this. Build and prove it behind the
seam before wiring any UI, so failures are caught in tests, not first-run.

### Design decisions
- **Library:** SQLDelight (KMP) — SQLite on Android, JDBC-SQLite on desktop. Add to
  `libs.versions.toml` (`sqldelight` version + the `gradle-plugin`, `runtime`,
  `coroutines-extensions`, `android-driver`, `sqlite-driver` for JVM). Mirrors the
  server's Exposed tables 1:1 (same columns from `data-model.md`), plus **D2** columns
  (`updated_at`, soft-delete) on the syncable aggregates.
- **Ref data is bundled, not synced (D4):** ship the same seed as
  `V2__seed_ref_data.sql` as a SQLDelight seed (or a bundled JSON in `:core`
  `commonMain/resources`) and insert on first DB open. Identity inside the local DB is a
  local UUID; the **slug** is the stable cross-store key.
- **Domain rules come from `:core`'s `service/` (Phase 11)** — `LocalDataSource` runs the
  *same* auto-close, option-validation, anchor/sub-log-replace, 409-on-duplicate, and
  pain-clear rules as the server, against SQLDelight transactions instead of Exposed.
- **Encryption (D5):** default SQLCipher for whole-DB encryption.
  - Android: SQLDelight `AndroidSqliteDriver` + SQLCipher (`net.zetetic` / androidx
    SQLCipher), key bytes from a Keystore-wrapped DEK.
  - Desktop: a SQLCipher-capable JDBC driver. **Verify driver availability early** — if
    cross-platform SQLCipher is painful, fall back to **app-layer column encryption**
    (reuse the same approach as the server: AES-256-GCM on notes + sex + all health
    option payloads) inside `LocalDataSource`, key from the OS keychain. Decide and
    record which path was taken in this phase's notes.
  - The DEK lifecycle reuses the existing secure-storage seam: extend `TokenStore` (or add
    a sibling `LocalKeyStore` `expect`/`actual`) to wrap/unwrap the DEK with a
    Keystore-backed key (Android) / OS keychain or passphrase-derived KEK (desktop,
    reusing `AppLockGate.enroll`).

### SQLDelight schema (mirror of `data-model.md` + D2/D3)
- Tables: `cycles`, `daily_logs`, `daily_log_{emotions,sleep,energy,sex,discharge,skin,
  digestion,flow,collection,mind}`, `pain_logs`, `pain_log_locations`,
  `user_dashboard_preferences`, and the **ref** tables.
- Syncable aggregates (`cycles`, `daily_logs`, `pain_logs`, `daily_log_sex`,
  `user_dashboard_preferences`) get `updated_at TEXT NOT NULL` and
  `deleted_at TEXT NULL` (ISO-8601). PKs are TEXT UUIDs (**D1**).
- A `sync_outbox` table is **created here but exercised in Phase 16** (entity_type,
  entity_id, op, updated_at, synced flag) — cheap to add now so the store records intent
  from day one even before a server exists (offline-first from the start).

### Tasks
**Build**
- [ ] Add SQLDelight plugin + deps to `libs.versions.toml` and `:app:shared` build; define
  the database module + drivers per platform (`expect`/`actual` driver factory).

**`:app:shared` commonMain**
- [ ] `.sq` schema files (above). Generated queries for all read/write paths.
- [ ] `LocalDataSource : HomeFlowDataSource` — every method implemented against SQLDelight
  using `:core` `service/` rules. Mirror `RemoteDataSource` semantics exactly
  (404→absence becomes "row absent → null/`RESOURCE_NOT_FOUND`"; 409-on-duplicate-day;
  emptied selection clears; pain cleared when empty).
- [ ] First-open bootstrap: create schema, seed ref data (idempotent), generate the local
  user row.
- [ ] At-rest encryption integration (DEK wrap/unwrap via secure storage).

**platform actuals**
- [ ] `androidMain`/`jvmMain` SQLDelight driver factories (+ SQLCipher wiring or the
  column-encryption fallback), and `LocalKeyStore` actuals.

### Done when
- [ ] **Parity suite:** a single shared test suite runs the *same* read/write scenarios
  against `RemoteDataSource` (MockEngine) **and** `LocalDataSource` (in-memory/temp
  SQLDelight) and asserts identical screen-shaped results — dashboard phase/labels,
  day resolve, cycle list, analytics, day-editor load, `saveDay` diff behavior, cycle
  start auto-closes prior, preference reorder. Both pass.
- [ ] Duplicate-day create → `CONFLICT`; cross-date day fetch absent → null; emptied
  category clears; pain cleared when no locations — all verified against `LocalDataSource`.
- [ ] Analytics from `LocalDataSource` equal the server's `AnalyticsTest` fixture values
  (same `:core` math over locally-stored rows).
- [ ] **At-rest encryption proven:** a test (or documented manual check) shows the raw DB
  file bytes do **not** contain a known plaintext note string; opening the DB without the
  DEK fails. The DEK is never written to plaintext storage/logs.
- [ ] `./gradlew :app:shared:check` green on JVM + Android host tests; both apps still
  compile.
- [ ] This phase's chosen encryption path (SQLCipher vs column-layer) is recorded in the
  doc + `ARCHITECTURE-client.md`.

### Risks
- **Cross-platform SQLCipher** is the top risk — spike the desktop JDBC SQLCipher driver
  in the first day; fall back to column encryption if it fights the build.
- SQLDelight schema drift vs the server schema — keep a single source-of-truth table list
  and cover with the parity suite.

---

## Phase 14 — Local-only app mode (Mode A)

**Goal:** ship the first new user-visible capability — the desktop and Android apps run
with **no server**, data in the local store, behind an app-lock (no Keycloak). This is
Mode A.

### Background / why
With the seam (11), the format (12), and the local engine (13) done, Mode A is mostly
**composition + a no-server auth path + packaging**.

### Design decisions
- **Mode selection & bootstrap.** Add a persisted `AppMode { LOCAL_ONLY, SERVER }`
  setting (small `expect`/`actual` prefs or a row in the local DB). A first-run chooser
  ("Use this device only" vs "Connect to a server") sets it. Composition root reads it and
  injects `LocalDataSource` (Mode A) or the remote stack (Mode B, Phase 15).
- **Auth in Mode A = app-lock only.** No Keycloak, no OIDC, no tokens. Reuse
  `AppLockGate` (biometric on Android; passphrase/credential on desktop) as the *only*
  gate, and gate it to the DEK unlock (D5). `AuthController` gains a local variant — or,
  cleaner, factor a small `SessionController` interface with `KeycloakSessionController`
  (today) and `LocalSessionController` (app-lock + DEK only). The UI's auth-gate states
  (`LoggedOut`/`Locked`/`Authenticated`) map onto local equivalents (first-run enroll →
  locked → unlocked).
- **No network, ever, in Mode A.** No Ktor client is constructed; `FLAG_SECURE` and "no
  body logging" still apply (no bodies exist, but keep the posture).
- **Settings parity.** Local mode still offers preference reorder, account/data deletion
  (wipes the local DB + DEK), and **export** (writes a `homeflow_export` file via Phase
  12's `:core` mapper, locally) — export is the user's backup *and* their future
  migration file.

### Tasks
**`:app:shared`**
- [ ] `AppMode` persisted setting + `expect`/`actual` store; first-run mode chooser screen.
- [ ] `SessionController` abstraction; `LocalSessionController` (enroll → unlock DEK →
  open `LocalDataSource`). Wire the existing shell/screens to render off it.
- [ ] Local **export to file** (reuse Phase 12 `:core` mapper) and **local data wipe**.
- [ ] Composition root: choose data source + session controller by `AppMode`.

**platform**
- [ ] Desktop file-save dialog for export; Android Storage Access Framework for export.
- [ ] Packaging: a **local-only** desktop installer and Android build that excludes the
  OIDC/server config requirement (the app must run with nothing else installed).

**docs**
- [ ] `ARCHITECTURE-client.md`: document Mode A, the `SessionController` seam, and that
  "no health data at rest" now has a Mode-A exception (encrypted local store).
- [ ] `threat-model.md`: add the Mode-A at-rest threat + mitigation (D5).

### Done when
- [ ] On a machine with **only the desktop app installed** (no server, no Keycloak,
  offline), a fresh user: picks "Use this device only" → sets an app passphrase → logs a
  full day (every category, pain w/ severities, notes, sex) → starts/closes cycles →
  reorders preferences → reopens the app, unlocks, and sees all data. Same on Android with
  biometrics.
- [ ] Killing all network (airplane mode / no server reachable) has **no effect** on any
  Mode-A operation.
- [ ] Analytics/phase indicator render correctly from local data (same `:core` math).
- [ ] **Local export** produces a `homeflow_export` file that Phase 12's server import
  accepts losslessly (proven by importing it into a dev server and deep-comparing).
- [ ] **Local data wipe** removes the DB + DEK; relaunch shows the first-run chooser.
- [ ] Security: raw local DB file shows no plaintext health data; access token / server
  concepts are entirely absent in Mode A (no Ktor client constructed — assert in a test).
- [ ] Signed local-only desktop installer + Android build produced and launch clean.
- [ ] `CHANGELOG.md`: "Use HomeFlow entirely on one device, no server required."

### Risks
- Leaking a server assumption into shared screens (e.g., a hard `AuthConfig` access).
  The mode chooser + `SessionController` must fully decouple this.

---

## Phase 15 — Server-connected mode + "adopt a server" migration (Mode B)

**Goal:** let a user point the app at their self-hosted server (today's online model, now
*configurable at runtime*), and provide the **one-time migration** that lifts existing
Mode-A local data up to the server so a second device can see it. This is Mode B and the
canonical "I got a server + a phone" story — **without** offline sync yet (that is 16).

### Background / why
Your usage story is *sequential*: local first, then adopt a server. Because adoption makes
the server the single source of truth at that moment, there is no two-way merge here —
this phase deliberately avoids the hard sync problem (deferred to 16).

### Design decisions
- **Runtime server config.** Replace the compile-time `AuthConfig` host with a
  user-entered server host (the Tailscale `*.ts.net` canonical name) persisted in
  settings. `AuthConfig`/`platformOidc` become seeded-but-overridable. A "Connect to a
  server" flow collects the host, runs the existing Keycloak OIDC + PKCE login, and
  switches `AppMode = SERVER`.
- **Mode B data source = `RemoteDataSource`** (today's online behavior, unchanged) behind
  the seam. After adoption the app is an online client; the local DB is **retired to a
  cache or left dormant** (we do *not* keep editing it independently — that would create
  drift requiring 16). Pin this: in Mode B, writes go to the server; reads come from the
  server; the local store is not the source of truth.
- **Adopt-a-server migration = local export → server import (Phase 12).** On first
  connect from a device that has Mode-A data: prompt "Upload this device's data to the
  server?" → generate `homeflow_export` locally → `POST /import?source=homeflow`. Additive
  + idempotent means re-running is safe and a second device with no local data simply skips
  it. After a successful upload, mark local data as migrated.
- **Second device** (Android) installs, picks "Connect to a server," logs in, and reads the
  same server data — no migration needed there.

### Tasks
**`:app:shared`**
- [ ] Runtime server-host config (persisted) + "Connect to a server" screen; thread it into
  `AuthConfig`/OIDC.
- [ ] Mode switch LOCAL_ONLY → SERVER; composition root selects `RemoteDataSource` +
  `KeycloakSessionController`.
- [ ] Adoption flow: detect local data, offer upload, call local-export → `POST /import`,
  show `ImportResultDto` summary, mark migrated.
- [ ] Settings: show current mode + server; allow connecting a server from a Mode-A install.

**docs**
- [ ] `ARCHITECTURE-client.md` / `DEPLOYMENT.md`: runtime host config replaces the
  hardcoded constant; the adoption runbook.

### Done when
- [ ] A Mode-A desktop with logged data can: enter a server host → complete Keycloak login
  (password + passkey) → upload its data → and thereafter read/write that data **on the
  server** (verified by querying the server DB / a second client).
- [ ] A **fresh Android** app picks "Connect to a server," logs into the same server, and
  sees the desktop-originated data — desktop and Android now show identical data via the
  server.
- [ ] Re-running the upload is a no-op (`cyclesCreated=0, dailyLogsCreated=0`) — idempotent
  (relies on Phase 12).
- [ ] Writes from either device while online appear on the other after refresh (single
  source of truth; no merge logic involved).
- [ ] Switching a device to Mode B does not lose its pre-existing local data (it was
  uploaded first); the local DB is no longer treated as authoritative in Mode B.
- [ ] Token storage/security posture unchanged from Phase 7/10 (refresh token in secure
  store, access token in memory, no body logging) — re-verify the client security
  checklist.
- [ ] `CHANGELOG.md`: "Connect the app to your self-hosted server and migrate your local
  data to it; use the same data across devices."

### Risks
- Users editing offline in Mode B before 16 exists → silent drift. **Guard:** in Mode B
  pre-16, surface a clear "offline — changes not saved" state rather than writing to a
  local cache that never syncs. (16 removes this limitation.)

---

## Phase 16 — Offline sync engine (Mode C)

**Goal:** make every app **offline-first with a server**: the local store (Phase 13) is
the primary, edits work offline, and a sync engine reconciles bidirectionally with the
server so all devices converge. This is the hard 20% and the final mode.

### Background / why last
Sync needs everything before it: the local store (13), the slug-keyed interchange format
(12/D4), client UUIDs + `updated_at` + tombstones (D1–D3, seeded into the schema in 13),
and the shared rules (11). Only the reconciler + server delta endpoints are genuinely new.

### Design decisions
- **Model:** local store is authoritative for the device; the server is a **sync peer**.
  In Mode C the data source is `LocalDataSource`; a background `SyncEngine` pushes/pulls.
- **Change tracking (client):** every local mutation appends to `sync_outbox` (created in
  13) — `(entity_type, entity_id, op{upsert|delete}, updated_at)`. The store keeps current
  state; the outbox records intent. (We do **not** need a full per-field oplog — LWW over
  whole syncable aggregates is sufficient for this data; see below.)
- **Change tracking (server):** add a server-side **change log / monotonic sequence**. On
  every accepted write the service appends `(user_id, entity_type, entity_id, server_seq,
  updated_at, deleted)` to a `sync_changes` table (server-assigned, gap-free per user via a
  sequence). Clients keep a `last_pulled_seq` cursor. This avoids clock-skew in the *pull*
  direction (cursor is a server sequence, not a timestamp).
- **Protocol (new server routes under `/api/v1/sync`):**
  - `GET /sync/changes?since=<cursor>` → `{ changes: [...slug-keyed aggregates with
    server_seq, updated_at, deleted...], cursor: <newCursor> }`. Returns every aggregate
    changed since the cursor, **including tombstones**, option refs as **slugs (D4)**,
    notes/sex as **plaintext over TLS** (server decrypts to send; re-encrypts on store).
  - `POST /sync/changes` → body = a batch of local outbox aggregates (client UUIDs,
    `updated_at`, tombstones, slug-keyed). Server applies **LWW per aggregate** and returns
    each aggregate's resulting `server_seq` + the authoritative post-merge state.
- **Conflict resolution = Last-Write-Wins per syncable aggregate**, comparator =
  `updated_at`; ties broken deterministically (e.g., higher `entity_id` wins, or
  server-wins). The data is naturally low-conflict (keyed by user+date / date-range), so
  per-aggregate LWW is adequate. **Tombstones participate in LWW** (a delete is just a
  versioned state; a later edit on another device with a newer `updated_at` resurrects, an
  older one stays deleted). Two narrow domain-aware cases:
  - **Cycle boundaries / auto-close:** reconcile via the shared `:core` rule, not raw LWW,
    when two devices start overlapping cycles — define the rule explicitly (e.g., the
    later-started cycle wins and re-derives the prior cycle's `end_date`).
  - **Preference order:** whole-value LWW (it is a single JSON aggregate).
- **Sync triggers:** on app foreground, after each local write (debounced), on
  connectivity regained, and a periodic timer. All best-effort; failures retry.
- **Encryption boundaries unchanged:** local store encrypts at rest with the device DEK
  (D5); server encrypts notes/sex at rest with `APP_ENCRYPTION_KEY`; the wire is plaintext
  over TLS. Neither key crosses the network.
- **Account deletion** remains a hard global purge (server cascade + Keycloak + local wipe)
  — it is not a tombstone-sync operation.

### Schema changes
**Server (Flyway `V…__sync.sql`):** add `updated_at`/`deleted_at` to syncable aggregates
that lack them; create `sync_changes` (per-user monotonic `server_seq`); ensure every
write path stamps `updated_at` and appends a change row (in the service layer, single
transaction). **Client (SQLDelight):** `sync_outbox` already exists (13); add a
`sync_state` row for the `last_pulled_seq` cursor.

### Tasks
**`:core`**
- [ ] Sync DTOs: `SyncAggregate` (slug-keyed), `SyncPushRequest`, `SyncPullResponse`,
  cursor type. Pure LWW-merge decision function (`mergeAggregate(local, remote)`),
  unit-tested, shared by client reconciler and server.
- [ ] The explicit cycle-boundary reconciliation rule in `:core` `service/`.

**`:server`**
- [ ] `sync` module: `GET/POST /sync/changes`, `sync_changes` table + per-user sequence,
  service-layer stamping on **every** existing write path (cycles, anchor, sub-logs, pain,
  prefs, deletes). Migration. Row-scoping invariants apply unchanged.

**`:app:shared`**
- [ ] `SyncEngine`: outbox push, cursor pull, apply remote changes into `LocalDataSource`
  via the shared merge, conflict resolution, retry/backoff, triggers. A `SyncStatus`
  (`Idle/Syncing/Offline/Error/lastSyncedAt`) surfaced in the UI.
- [ ] Mode C composition: `LocalDataSource` primary + `SyncEngine` against the configured
  server; the "adopt a server" upload (Phase 15) becomes the *initial* push.

### Done when
- [ ] **Two-device convergence (online):** edit on desktop → appears on Android after a
  sync, and vice-versa; both converge to identical state (deep DB compare).
- [ ] **Offline edits reconcile:** put device A offline, edit several days/cycles; edit
  *different* days on device B; bring A online → both devices converge with all edits
  present (no loss).
- [ ] **Conflict (same day, both offline):** both devices edit the **same** day offline;
  after sync, the **later `updated_at` wins** deterministically on both devices (no
  duplicate rows, no partial merge); covered by an automated test of `mergeAggregate`.
- [ ] **Tombstone propagation:** delete a cycle/day on A offline; B (which had it) reflects
  the delete after sync and does not resurrect it on its next push.
- [ ] **Cycle-boundary conflict** resolves via the documented `:core` rule, not raw LWW —
  unit-tested with a constructed overlapping-cycle scenario.
- [ ] **Idempotent/resumable:** interrupting a sync mid-batch and re-running produces the
  same converged state (server seq cursor + idempotent upserts); no dupes.
- [ ] **Slug boundary (D4):** a sync between a freshly-seeded local store and the server
  (whose ref-data UUIDs differ) correctly maps every selection by slug — assert a logged
  `mood_swings` round-trips despite differing option UUIDs on each side.
- [ ] Security: wire payloads are plaintext over TLS only; server at-rest stays encrypted
  (notes/sex ciphertext in PG); local at-rest stays encrypted (DEK); no key crosses the
  network; no body logging; row-scoping holds (a sync request cannot pull another user's
  changes) — Testcontainers + client integration tests.
- [ ] `./gradlew check` green across modules; `ARCHITECTURE-server.md`,
  `ARCHITECTURE-client.md`, `threat-model.md`, `API.md` updated with the sync design;
  `CHANGELOG.md`: "Work offline; your devices sync through your server when reconnected."

### Risks
- **Clock skew** corrupting LWW — mitigate by using the server sequence for the pull
  cursor and treating `updated_at` only as the merge comparator (and consider stamping a
  server-side `updated_at` on accept for server-origin truth).
- **Partial-aggregate writes** — always sync the **whole** daily-log aggregate (anchor +
  all sub-logs) as one unit so a half-applied day cannot occur.
- Scope creep toward CRDTs — explicitly out of scope; LWW-per-aggregate + the two domain
  rules is the contract.

---

## 4. Global "done" for the whole initiative

- [ ] All three modes shippable: **A** (local-only, no server), **B** (server-connected +
  migration), **C** (offline-first + sync).
- [ ] The canonical story works end-to-end: desktop local-only → adopt a server → add
  Android → both stay in sync, including offline.
- [ ] `:core` holds one copy of every shared rule (validation, cycle/analytics math,
  domain-service decisions, LWW merge); no duplication between server and `LocalDataSource`
  (spot-check with `git grep`).
- [ ] Security invariants intact in every mode: server row-scoping + at-rest encryption;
  client at-rest encryption (Mode A/C); no plaintext keys; no body/PII logging;
  `FLAG_SECURE`.
- [ ] Docs reconciled: `CLAUDE.md` doc index links this plan; `ARCHITECTURE-*`,
  `threat-model.md`, `API.md`, `DEPLOYMENT.md`, `CHANGELOG.md` reflect the new modes.

## 5. Sequencing & branch guidance

- One feature branch per phase, stacked, matching the existing `feature/phase-N-…`
  convention (`BRANCHING.md`). Do not start a phase until the prior phase's **Done when**
  is fully green.
- Phases 11–13 are safe to land with **no user-visible change** (refactor + dormant
  capability) — ship them first to de-risk. Phases 14/15/16 each flip on a mode.
- Keep the server backward-compatible: 11/12/16's server additions are **additive**
  (new optional `id`, new routes, new columns) so an older client keeps working during
  rollout.
```
