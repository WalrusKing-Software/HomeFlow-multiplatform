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
| **16a** | Offline sync — server foundation (change-log, soft-delete, `/sync` + delete routes) | — (server-only, additive) | No |
| **16b** | Offline sync — `:core` merge + client reconciler (outbox, `SyncEngine`) | — (internal, tested) | No |
| **16c** | Offline sync — triggers, status UI, delete UI, Mode-C activation | **C** | **Yes** |

> The original framing was "3 phases (11/12/13) ≈ 3 modes." Implementation is split so each
> lands on a stable, testable base — Modes A/B/C are delivered by phases 14, 15, and 16c
> respectively; 11–13 are the shared foundation, and the sync engine (16) is itself split into
> three stacked sub-phases (16a server → 16b client reconciler → 16c activation) because it
> spans every layer. Do not start a phase until the prior one passes its **Done when** checklist.

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

> **Executable spec.** Every decision below is **closed** — there are no "your call"
> choices and no "spike then decide" gates. Implement exactly what is written; do not add
> scope (no mode selection, no UI, no DI changes, no sync). If the code or a dependency
> contradicts the spec, **STOP and report** rather than guessing or silently switching
> approaches. This is the single biggest net-new piece — it is large but fully specified.

**Goal:** a fully-working **local store** in `:app:shared` — an encrypted SQLDelight
database, a `LocalDataSource` that implements the Phase 11 `HomeFlowDataSource` seam
against it, ref-data seeded locally by slug, and whole-database at-rest encryption (**D5**).
Built and **tested in isolation on the JVM target**; **not** surfaced as a user-selectable
mode (that is Phase 14 — do not add `AppMode`, a chooser, or any composition-root change
here). **Net user-visible change: zero** (no `CHANGELOG.md` entry — Phase 14 ships Mode A).

### Why now / why behind the seam
Mode A (Phase 14) and the offline half of Mode C (Phase 16) both stand on this store.
Building and proving it behind the `HomeFlowDataSource` interface means a second, fully
self-contained implementation of the *exact* contract `RemoteDataSource` already satisfies
— so failures are caught by the parity tests here, not at first-run in Phase 14.

### Key architectural facts (do not violate)
- `LocalDataSource` sits **below** the seam, peer to `RemoteDataSource`. Everything in
  `HomeFlowRepository` (shaping, the label cache, `changedWrites`/`saveDay`, the
  `optional()` 404→absence mapping) is **reused unchanged** — it calls the data source's
  per-route methods and never knows which implementation it has. Therefore the entire
  correctness target for this phase is: *`LocalDataSource` returns the same `ApiResult`s,
  with the same `ErrorCode`s and DTO shapes, that `RemoteDataSource` returns today.* The
  interface KDoc in `HomeFlowDataSource.kt` is the **behavioral contract**; honor it exactly.
- **Both `:app:shared` targets are JVM** (`jvm()` desktop + `androidLibrary`). There is no
  native/JS target, so JVM/JDK APIs (`javax.crypto`, JDBC) are available in the platform
  source sets. Crypto/driver code lives in `androidMain`/`jvmMain` behind `expect`/`actual`,
  never in `commonMain`.
- **Local DB files never cross devices.** Sync (Phase 16) exchanges plaintext over TLS, not
  database files. So Android and desktop do **not** need a common on-disk cipher format —
  each device encrypts its own DB with its own device-rooted key. This removes the only hard
  cross-platform SQLCipher constraint.

### Decisions (closed)

- **D-13.1 — Library: SQLDelight 2.x.** Add `sqldelight = "2.0.2"` to `libs.versions.toml`
  (if 2.0.2 is unresolvable, use the latest `2.0.x`; do **not** move to a 2.1 milestone
  without reporting). Add the Gradle plugin `app.cash.sqldelight` and the artifacts
  `runtime`, `coroutines-extensions`, `android-driver` (androidMain), `sqlite-driver`
  (the JDBC driver, jvmMain test/runtime). The generated database is configured in
  `:app:shared`'s `commonMain` (`.sq` files), drivers are per-platform.
- **D-13.2 — At-rest encryption: whole-DB SQLCipher; the DEK comes from platform secure
  storage; NO client-side column crypto.** The entire local database is encrypted at rest
  (this is the client analogue of the server's secured Postgres host). Notes and the sex
  payload are stored as **plaintext columns *inside* the encrypted DB** — the encryption
  boundary is the database file, not individual columns (do **not** add AES-GCM column
  crypto on the client; that is a server-only concern). Drivers:
  - **Android (`androidMain`):** SQLCipher community edition
    `net.zetetic:sqlcipher-android` + `androidx.sqlite:sqlite` `SupportFactory`, passed to
    SQLDelight's `AndroidSqliteDriver(schema, context, name, factory = SupportFactory(dek))`.
  - **Desktop (`jvmMain`):** the encryption-capable JDBC driver
    `io.github.willena:sqlite-jdbc` (bundles SQLite3MultipleCiphers; SQLCipher-compatible);
    open a `JdbcSqliteDriver(url)` and immediately execute `PRAGMA key = "x'<hex-dek>'";`
    before any other statement. **If `io.github.willena:sqlite-jdbc` cannot be resolved or
    its `PRAGMA key` is rejected at runtime, STOP and report** — do not fall back to an
    unencrypted desktop DB or to column crypto without an explicit decision from the user.
- **D-13.3 — DEK lifecycle via a new `LocalKeyStore` `expect`/`actual`.** Add
  `LocalKeyStore` (sibling of `TokenStore`, package `org.homeflow.app.shared.auth`) with
  `fun loadOrCreateDek(): ByteArray` (32 random bytes on first call; same bytes thereafter)
  and `fun clearDek()`. Actuals: **Android** stores the DEK (Base64) in a Keystore-backed
  `EncryptedSharedPreferences` (mirror `AndroidTokenStore`; a separate prefs file is fine);
  **desktop** stores it (Base64) in the OS keychain via `java-keyring` (mirror
  `DesktopTokenStore`, new account key). The DEK is **never** written to a plaintext file or
  a log. Phase 13 does **not** gate the DEK behind `AppLockGate` — that wiring is Phase 14.
- **D-13.4 — Ref data is bundled and seeded locally by slug (D4), never synced.** The seed
  is a single Kotlin constant `RefSeed` in `:app:shared/commonMain`
  (`data/local/RefSeed.kt`) holding the categories/options/regions/locations from the
  "Seed Data Summary" of `data-model.md` (same slugs, labels, sort orders, selection types,
  phases). On first DB open, insert it idempotently; each row gets a **locally-generated
  UUID** — the **slug** is the stable identity, the UUID is store-local. A test asserts the
  seed's slug sets + counts equal `data-model.md` exactly (see Tests) so it cannot drift
  from the server's `V2__seed_ref_data.sql`.
- **D-13.5 — Reuse the EXISTING `:core` rules; do NOT extract more into `:core`.**
  `LocalDataSource` reuses `org.homeflow.core.service.autoCloseEndDate` (Phase 11) and the
  existing `org.homeflow.core.validation.*` functions (`validateCycleStart`,
  `validateCycleEnd`, `validateDailyLogWithinCycle`, `validateNotes`,
  `validatePainLocations`, `validateCategoryOrder`). The one rule not already shared —
  *"a submitted option id must belong to its category"* — is a trivial set-membership check
  and is **inlined** in `LocalDataSource`; do **not** create new `:core/service/` files for
  it (same "don't over-extract until forced" lesson as Phases 11/12; there is still only one
  natural home for each rule). The local store's auto-close, anchor/sub-log-replace,
  409-on-duplicate, and pain-clear behaviors are **storage operations** expressed in
  SQLDelight that *call* those shared pure rules — they are not themselves moved to `:core`.
- **D-13.6 — Exactly one local user (CLAUDE.md: one account).** On first open, insert a
  single `users` row with a fixed local id. All `LocalDataSource` reads/writes scope to it
  implicitly. `getMe()` returns a synthetic `UserDto` for that row. `deleteAccount()`
  **hard-wipes** the database (drop/recreate all data) and calls `LocalKeyStore.clearDek()`
  — there is no Keycloak in local mode. (Hard wipe, not tombstones: account deletion is
  global and intentional, per **D3**.)
- **D-13.7 — D2/D3 columns exist now, are not exercised by the interface yet.** Syncable
  aggregates carry `updated_at`/`deleted_at`; a `sync_outbox` table exists. The
  `HomeFlowDataSource` interface has **no** delete-day/delete-cycle method, so tombstones
  and the outbox are **written but never read** in Phase 13 (Phase 16 reads them). Clearing
  a sub-log (empty selection) or pain is a **row delete inside the aggregate**, not an
  aggregate tombstone. Stamp `updated_at` on every aggregate write so Phase 16 inherits it;
  appending to `sync_outbox` on writes is **optional** in this phase (add the table; wiring
  the append is allowed but untested — do not block on it).
- **D-13.8 — Composition.** `LocalDataSource` is assembled from small internal stores that
  mirror the server's module split (`LocalCyclesStore`, `LocalDailyLogsStore`,
  `LocalSubsStore`, `LocalPrefsStore`, `LocalRefData`, `LocalAnalytics`) so each method stays
  small and maps 1:1 to a server repository/service. A `LocalDatabaseFactory`
  (`expect`/`actual`) builds the driver from the DEK and returns the SQLDelight `Database`.

### Timestamps, dates, and UUIDs (use exactly)
- **UUIDs:** `kotlin.uuid.Uuid.random().toString()` (stable in Kotlin 2.4.0 — no opt-in).
  Stored as `TEXT`. This is the client-generated id path of **D1**.
- **Timestamps (`created_at`/`updated_at`):** ISO-8601 instants via
  `kotlin.time.Clock.System.now().toString()`, stored as `TEXT`. **Do not** import
  `kotlinx.datetime.Clock` — `:app:shared` forces kotlinx-datetime 0.7.1 where `Clock`/
  `Instant` moved to `kotlin.time`; using the old type throws `NoClassDefFoundError` at
  runtime. (See the project memory note on this.)
- **Dates (`log_date`, cycle `start_date`/`end_date`):** `kotlinx.datetime.LocalDate`
  serialized with `.toString()` → ISO `yyyy-MM-dd`, stored as `TEXT`; parse with
  `LocalDate.parse(...)`.

### SQLDelight schema (mirror `data-model.md` 1:1 + D2/D3)
Create `.sq` files under
`app/shared/src/commonMain/sqldelight/org/homeflow/app/shared/db/` (package
`org.homeflow.app.shared.db`). Tables mirror `data-model.md` column-for-column, with these
type/representation rules: every PK and FK is `TEXT` (UUID string, **D1**); every date is
`TEXT` (ISO yyyy-MM-dd); every timestamp is `TEXT` (ISO instant); `severity` is `INTEGER`;
`category_order` is `TEXT` (JSON array of slugs, as the server stores it).

- `Users.sq` — `users(id, created_at, updated_at)` (no `keycloak_sub` locally; one row).
- `Cycles.sq` — `cycles(id, user_id, start_date, end_date, created_at, updated_at,
  deleted_at)`.
- `DailyLogs.sq` — `daily_logs(id, user_id, cycle_id, log_date, notes, created_at,
  updated_at, deleted_at)`, `UNIQUE(user_id, log_date)`.
- `DailyLogSubs.sq` — the ten symptom tables exactly as in `data-model.md`:
  multi-select `daily_log_{emotions,sleep,discharge,skin,digestion,mind}(id, daily_log_id,
  user_id, option_id, created_at)` each `UNIQUE(daily_log_id, option_id)`; single-select
  `daily_log_{energy,flow,collection}(id, daily_log_id UNIQUE, user_id, option_id,
  created_at)`; and `daily_log_sex(id, daily_log_id UNIQUE, user_id, payload, created_at,
  updated_at, deleted_at)` — **`payload` is a plaintext JSON array of option-id strings**
  (the DB file is the encryption boundary; no column crypto, per D-13.2). Keep the single
  `daily_log_sex` row shape (one row per day) from the sex addendum.
- `PainLogs.sq` — `pain_logs(id, daily_log_id UNIQUE, user_id, created_at, updated_at,
  deleted_at)` and `pain_log_locations(id, pain_log_id, user_id, location_id, severity,
  created_at)`, `UNIQUE(pain_log_id, location_id)`.
- `Preferences.sq` — `user_dashboard_preferences(id, user_id UNIQUE, category_order,
  updated_at, deleted_at)`.
- `RefData.sq` — `ref_symptom_categories(id, slug UNIQUE, label, selection_type, phase,
  sort_order)`, `ref_symptom_options(id, category_id, slug, label, sort_order,
  UNIQUE(category_id, slug))`, `ref_pain_regions(id, slug UNIQUE, label, sort_order)`,
  `ref_pain_locations(id, region_id, slug, label, sort_order, UNIQUE(region_id, slug))`.
- `SyncOutbox.sq` — `sync_outbox(id, entity_type, entity_id, op, updated_at, synced
  INTEGER)` — **created, not read** in Phase 13 (D-13.7).

Each `.sq` defines the labeled queries its store needs (insert/select/delete per the
behaviors below). Generate the database under `databases { create("HomeFlowDb") { … } }` in
the Gradle config.

### The contract `LocalDataSource` must satisfy (mirror `RemoteDataSource` exactly)
`class LocalDataSource(private val db: HomeFlowDb) : HomeFlowDataSource`. Every method
returns an `ApiResult`, mapping outcomes to the same `ErrorCode`s the HTTP path produces:

- `getMe()` → `Success(UserDto)` for the local user.
- `deleteAccount()` → hard-wipe all data + `clearDek()`; `Success(Unit)`.
- `getCycles()` → `Success(CyclesResponse(cycles newest-first by start_date))`.
- `getCurrentCycle()` → the open cycle (`end_date IS NULL`); none → `Failure(RESOURCE_NOT_FOUND)`.
- `createCycle(startDate)` → validate with `validateCycleStart(start, today)`
  (`Failure(VALIDATION_ERROR)` on future date); **auto-close** any open cycle by setting its
  `end_date = autoCloseEndDate(start)`; insert the new cycle (new UUID); `Success(CycleDto)`.
- `closeCycle(cycleId, endDate)` → cycle absent → `Failure(RESOURCE_NOT_FOUND)`; validate
  with `validateCycleEnd(start, end, today)`; set `end_date`; `Success(CycleDto)`.
- `getDailyLog(date)` → assemble the day (anchor + all sub-logs + pain, with `sex` decoded
  from its JSON payload to option-id strings); no anchor → `Failure(RESOURCE_NOT_FOUND)`.
  A category with no rows is `null` (not `[]`), exactly as `DailyLogsService.getDailyLog`.
- `createDailyLog(date, cycleId)` → cycle must be the local user's
  (`Failure(VALIDATION_ERROR)` if unknown) and the date in range
  (`validateDailyLogWithinCycle`, `Failure(VALIDATION_ERROR)` out of range); anchor already
  exists for the date → `Failure(CONFLICT)`; else insert anchor (new UUID); `Success(Unit)`.
- `putOptionIds(date, endpoint, optionIds)` → no anchor → `Failure(RESOURCE_NOT_FOUND)`;
  validate each id ∈ the endpoint's category (inlined membership check,
  `Failure(VALIDATION_ERROR)` on unknown), dedupe; **replace** the category's rows (delete
  all, insert the set — empty clears); for the `sex` endpoint write/clear the single
  `daily_log_sex.payload` row; bump anchor `updated_at`; `Success(Unit)`.
- `putOptionId(date, endpoint, optionId)` → as above for a single-select table; `null`
  clears; `Success(Unit)`.
- `patchNotes(date, notes)` → no anchor → `Failure(RESOURCE_NOT_FOUND)`; `validateNotes`
  (`Failure(VALIDATION_ERROR)` if too long); set/clear `daily_logs.notes`; `Success(Unit)`.
- `putPain(date, locations)` → no anchor → `Failure(RESOURCE_NOT_FOUND)`;
  `validatePainLocations` (dupe/severity → `Failure(VALIDATION_ERROR)`); each `locationId`
  must be a known pain location (`Failure(VALIDATION_ERROR)` otherwise); **replace** the
  pain log (delete then, if non-empty, insert anchor + locations; empty clears the
  `pain_logs` row); `Success(Unit)`.
- `getCycleStats()` / `getPeriodLengthChart()` / `getOvulationPrediction()` /
  `getSleepPredictions()` → **always `Success`** (never NOT_FOUND); computed by the **same
  `:core` math** the server's `AnalyticsService` uses (`cycleStats`, `periodLengthChart`,
  `ovulationPredictions`, `bucketSleepByPhase`, with `predictPhase`/`cycleDayNumber` and the
  `DEFAULT_*` fallbacks). `LocalAnalytics` builds `ClosedCycleInput`/`SleepDayInput` from
  local rows exactly as `AnalyticsRepository` builds them from Postgres (bleeding-day =
  distinct dates with a `daily_log_flow` row in the cycle; sleep bucketed by phase).
- `getPreferences()` → saved `category_order`, or the default (categories by `sort_order`);
  `Success(PreferencesDto)`.
- `putPreferences(categoryOrder)` → `validateCategoryOrder(order, allSlugs)`
  (`Failure(VALIDATION_ERROR)` on dup/unknown/missing); upsert; `Success(PreferencesResponse)`.
- `getSymptomCategories()` / `getPainRegions()` → built from the seeded ref tables, grouped
  and ordered identically to `RefDataService`.

### File manifest (exhaustive — create / modify exactly these)

**Modify build (2):**
1. `gradle/libs.versions.toml` — add the `sqldelight` version, the `sqldelight` plugin, and
   library aliases for `runtime`, `coroutines-extensions`, `android-driver`,
   `sqlite-driver`; add `sqlcipher-android` (`net.zetetic:sqlcipher-android`) +
   `androidx-sqlite` (`androidx.sqlite:sqlite`); add `willena-sqlite-jdbc`
   (`io.github.willena:sqlite-jdbc`).
2. `app/shared/build.gradle.kts` — apply the `app.cash.sqldelight` plugin; add the
   `sqldelight { databases { create("HomeFlowDb") { packageName.set("org.homeflow.app.shared.db") } } }`
   block; add `runtime` + `coroutines-extensions` to `commonMain`, `android-driver` +
   `sqlcipher-android` + `androidx-sqlite` to `androidMain`, `sqlite-driver` +
   `willena-sqlite-jdbc` to `jvmMain` (and to `jvmTest` so the encrypted JDBC driver is on
   the test classpath).

**Create — schema (8 `.sq` files):**
3–10. `Users.sq`, `Cycles.sq`, `DailyLogs.sq`, `DailyLogSubs.sq`, `PainLogs.sq`,
   `Preferences.sq`, `RefData.sq`, `SyncOutbox.sq` (schemas + queries, per above).

**Create — commonMain Kotlin (`org/homeflow/app/shared/data/local/`):**
11. `LocalDatabaseFactory.kt` — `expect class LocalDatabaseFactory` with
    `fun create(dek: ByteArray): HomeFlowDb` (builds the encrypted driver, runs
    `HomeFlowDb.Schema.create` on first open).
12. `RefSeed.kt` — the bundled seed constant (D-13.4).
13. `LocalBootstrap.kt` — first-open setup: create schema (handled by the factory), seed ref
    data idempotently (only if `ref_symptom_categories` is empty), ensure the single `users`
    row. Safe to call on every open.
14. `LocalRefData.kt`, `LocalCyclesStore.kt`, `LocalDailyLogsStore.kt`, `LocalSubsStore.kt`,
    `LocalPrefsStore.kt`, `LocalAnalytics.kt` — the internal stores (D-13.8).
15. `LocalDataSource.kt` — `class LocalDataSource(db) : HomeFlowDataSource`, composing the
    stores and mapping outcomes to `ApiResult` per the contract above.

**Create — secure-storage seam:**
16. `app/shared/.../auth/LocalKeyStore.kt` — `interface LocalKeyStore` +
    `expect fun createLocalKeyStore(): LocalKeyStore` (D-13.3).

**Create — platform actuals (4):**
17. `androidMain/.../data/local/LocalDatabaseFactory.android.kt` — `AndroidSqliteDriver` +
    SQLCipher `SupportFactory(dek)`.
18. `jvmMain/.../data/local/LocalDatabaseFactory.jvm.kt` — `JdbcSqliteDriver` +
    `PRAGMA key` over the willena driver; DB file under the desktop app-data dir.
19. `androidMain/.../auth/LocalKeyStore.android.kt` — Keystore-backed `EncryptedSharedPreferences`.
20. `jvmMain/.../auth/LocalKeyStore.jvm.kt` — `java-keyring`.

**Create — tests (jvmTest; the Android host-test JVM cannot load SQLCipher native libs):**
21. `jvmTest/.../data/local/LocalDataSourceParityTest.kt`
22. `jvmTest/.../data/local/LocalDataSourceContractTest.kt`
23. `jvmTest/.../data/local/LocalAnalyticsTest.kt`
24. `jvmTest/.../data/local/RefSeedTest.kt`
25. `jvmTest/.../data/local/LocalEncryptionAtRestTest.kt`

**Docs (1):**
26. `__docs/ARCHITECTURE-client.md` — add a "Local store (Phase 13)" subsection: the
    `LocalDataSource` peer to `RemoteDataSource`, the SQLDelight schema, **whole-DB SQLCipher
    as the chosen at-rest path** (and that notes/sex are plaintext *inside* the encrypted DB,
    unlike the server which additionally column-encrypts them), the `LocalKeyStore`/DEK seam,
    and the "no health data at rest" exception this introduces (encrypted local store).

### Tests to add (concrete)
- **`RefSeedTest`** — assert the seed's category slugs/labels/selection-types/phases, option
  slugs per category, region slugs, and location slugs (and their counts) **exactly equal**
  the "Seed Data Summary" in `data-model.md`. This is the anti-drift guard for D4.
- **`LocalDataSourceParityTest`** — drive `HomeFlowRepository(LocalDataSource(...))` through
  the same scenarios `HomeFlowRepositoryTest` drives over `RemoteDataSource`, asserting the
  same **screen-shaped** results: `loadDashboard` (current cycle + cycle day + phase + today
  resolved to labels), `loadDay`, `loadCycles` (newest first), `loadDayEditor` (categories in
  saved preference order, initial selections pre-populated), `saveDay` (diff → only changed
  categories written; emptied selection clears; re-`loadDay` reflects it), `startCycle`
  auto-closes the prior open cycle, and `savePreferences` round-trips. Because the repository
  resolves option/location ids to **labels** via each source's own ref-data, the two backends
  yield identical labelled output despite different local UUIDs.
- **`LocalDataSourceContractTest`** — the error/edge contract directly on `LocalDataSource`:
  duplicate-day `createDailyLog` → `CONFLICT`; `getDailyLog` for an unlogged date →
  `Failure(RESOURCE_NOT_FOUND)`; `getCurrentCycle` with no open cycle →
  `Failure(RESOURCE_NOT_FOUND)`; `putOptionIds`/`patchNotes`/`putPain` before the anchor
  exists → `Failure(RESOURCE_NOT_FOUND)`; an option id from the wrong category →
  `Failure(VALIDATION_ERROR)`; an emptied multi-select clears the category; pain cleared when
  `locations` is empty; `createDailyLog` with an out-of-range date → `Failure(VALIDATION_ERROR)`.
- **`LocalAnalyticsTest`** — seed a fixture of cycles + flow/sleep days matching the inputs of
  the server's `AnalyticsPreferencesTest`, and assert `LocalDataSource`'s `getCycleStats`,
  `getPeriodLengthChart`, `getOvulationPrediction`, and `getSleepPredictions` DTOs equal the
  same expected values (same `:core` math over locally-stored rows).
- **`LocalEncryptionAtRestTest`** — write a day with a known notes string (e.g.
  `"PLAINTEXT_CANARY_NOTE"`), close the DB, then: (1) read the raw DB file bytes and assert
  the canary string does **not** appear; (2) attempt to open the same file with a **wrong**
  DEK and assert it fails; (3) open with the correct DEK and assert the note round-trips.
  Assert the DEK is never written to a plaintext file or logged.

### Guardrails — do NOT
- Do NOT add any mode concept, `AppMode`, first-run chooser, `SessionController`, DI/
  composition-root change, file-export, or UI — those are Phases 14–16. Phase 13 only
  constructs `LocalDataSource` in tests (and the factory/bootstrap it needs).
- Do NOT touch `RemoteDataSource`, `HomeFlowRepository`, `AuthController`, or the
  `HomeFlowDataSource` interface signatures — `LocalDataSource` conforms to the seam as-is.
- Do NOT add AES-GCM (or any) column encryption on the client; the DB file is the boundary
  (D-13.2). Do NOT fall back to an unencrypted desktop DB — STOP and report instead.
- Do NOT extract new rules into `:core` — reuse the existing `:core` validation +
  `autoCloseEndDate` and inline the one-line option-in-category check (D-13.5).
- Do NOT seed ref data with hardcoded UUIDs or sync ref data — slugs are the cross-store key;
  local UUIDs are random per store (D-13.4/D4).
- Do NOT import `kotlinx.datetime.Clock`/`Instant` — use `kotlin.time.Clock` (see Timestamps).
- Do NOT read/act on `sync_outbox` or tombstones (Phase 16); just create the columns/table.
- Do NOT log health data, notes, the sex payload, the DEK, or DB rows — no body/row logging.
- Do NOT put SQLDelight data-source tests in `commonTest` — they need a real driver and the
  Android host-test JVM can't load SQLCipher native libs; keep them in `jvmTest`.

### Done when (all automated unless noted; run from repo root)
- [ ] `./gradlew :app:shared:check` green — ktlint + detekt + the JVM test suite (all five new
  test files) pass; the Android host-test target still compiles and passes.
- [ ] `./gradlew :app:androidApp:assembleDebug` and `:app:desktopApp:compileKotlinJvm` (or
  `:app:desktopApp:run` smoke) succeed — both apps still build with the new deps and SQLCipher
  driver on the classpath. (No app wires `LocalDataSource` yet; this only proves the deps and
  generated DB compile into both entry points.)
- [ ] **Parity:** `LocalDataSourceParityTest` passes — `HomeFlowRepository` over
  `LocalDataSource` produces the same screen-shaped results (dashboard phase/labels, day
  resolve, cycle list, editor order + initial selections, `saveDay` diff/clear, cycle-start
  auto-close, preference reorder) as over `RemoteDataSource`.
- [ ] **Contract:** `LocalDataSourceContractTest` passes — duplicate-day → `CONFLICT`;
  unlogged date/open-cycle/missing-anchor → `RESOURCE_NOT_FOUND`; wrong-category /
  out-of-range / bad-preference-order → `VALIDATION_ERROR`; emptied category clears; pain
  cleared when empty.
- [ ] **Analytics parity:** `LocalAnalyticsTest` equals the `AnalyticsPreferencesTest` fixture
  values for all four analytics DTOs.
- [ ] **Seed integrity:** `RefSeedTest` proves the bundled seed's slug sets + counts equal
  `data-model.md` (no drift from `V2__seed_ref_data.sql`).
- [ ] **Encryption proven:** `LocalEncryptionAtRestTest` passes — raw DB bytes contain no
  plaintext canary; wrong-DEK open fails; correct-DEK round-trips; DEK never in plaintext/logs.
- [ ] `git grep -n "kotlinx.datetime.Clock" app/shared/src` → no results in the new local code.
- [ ] `ARCHITECTURE-client.md` records the chosen path (whole-DB SQLCipher) and the local-store
  design. (No `CHANGELOG.md` entry — Phase 13 has no user-visible behavior.)

### Risks / stop-conditions
- **Desktop SQLCipher driver (top risk).** If `io.github.willena:sqlite-jdbc` does not resolve
  on this toolchain, or `PRAGMA key` is rejected/ignored at runtime (the file opens
  unencrypted), **STOP and report** with the exact error — do not ship an unencrypted desktop
  DB and do not silently switch to column crypto. (Per D-13.2 the user decides any deviation.)
- **SQLDelight 2.x ↔ AGP 9.2 `androidLibrary` plugin.** The `android-driver` is a normal AAR
  dependency on `androidMain`; the Gradle plugin generates source for `commonMain`. If the
  SQLDelight Gradle plugin conflicts with the new `com.android.kotlin.multiplatform.library`
  plugin, STOP and report (do not downgrade AGP or restructure modules without asking).
- **Schema drift.** The `.sq` tables must match `data-model.md` column-for-column (plus D2/D3).
  `RefSeedTest` guards the seed; a one-line "table list" comment at the top of each `.sq` keeps
  the columns auditable against the server's Exposed tables.
- **`kotlin.uuid.Uuid` availability.** It is stable in Kotlin 2.4.0; if the compiler still
  demands `@OptIn(ExperimentalUuidApi::class)`, add it locally rather than changing the
  approach.
- **Pain location slug uniqueness** (already relied on in Phase 12): the flat
  `locationSlug→id` map is only valid because location slugs are globally unique in the seed —
  the same `RefSeed` must preserve that. `RefSeedTest` asserts it.

---

## Phase 14 — Local-only app mode (Mode A)

> **Executable spec.** Decisions are **closed**. Compose screen *layout* has latitude
> (match the existing screens' style), but every seam, type, state transition, and file
> below is fixed. Do not add Mode B's runtime-host config or any sync — those are Phases
> 15/16. If the code contradicts the spec, **STOP and report**.

**Goal:** ship the first new **user-visible** capability — the desktop and Android apps run
with **no server and no Keycloak**, all data in the Phase 13 encrypted local store, behind
the existing app-lock. A first-run chooser picks "Use this device only" (Mode A) or "Connect
to a server" (today's online behavior, unchanged). This is **Mode A**.

### Why now / what's actually left
The seam (11), the interchange format (12), and the local engine (13, reviewed + green) are
done. Mode A is therefore **composition + a no-server session path + local export +
packaging** — almost no new domain logic. The local store already satisfies the entire
`HomeFlowDataSource` contract; this phase wires it to the UI through a session abstraction
and a mode chooser.

### Key facts (verified against the current client — do not violate)
- The root `App.kt` already renders off `AuthController.state: StateFlow<AuthState>` with
  states `LoggedOut / Authenticating / Locked(needsEnrollment) / Authenticated(user) /
  Error`, and reads `controller.usesPassphraseGate` + `controller.repository`. `AppShell`
  takes a `HomeFlowRepository` + `onLogout` + `onDeleteAccount`. **Mode A reuses all of
  this** — same states, same shell, same screens — with a different controller behind it.
- `AppLockGate` already implements `needsEnrollment()/enroll(secret)/authenticate(secret?)`
  (desktop = PBKDF2 passphrase in the OS keychain; Android = `BiometricPrompt`). Mode A
  reuses it **unchanged** as the only gate.
- Phase 13 shipped `LocalKeyStore` (`loadDek/saveDek/clearDek`), `LocalDatabaseFactory`
  (`create(dek): HomeFlowDb`), `LocalBootstrap.seed(db)`, and `LocalDataSource(db)`. Mode A
  composes these; it does **not** modify them.
- **There is no `:core` export mapper.** Phase 12's `D-12.5` deferred it; export lives only
  in the server's `ImportExportService`. So Mode A builds its **own** local exporter that
  emits the Phase 12 `:core` DTO `HomeFlowExport` (serialized identically) — it does **not**
  call server code and does **not** extract a shared mapper (over-extraction; the server
  builds its `ExportDay`s directly and keeps doing so).

### Decisions (closed)

- **D-14.1 — `AppMode` lives in a NEW non-sensitive store, never the encrypted DB.** Add
  `enum class AppMode { LOCAL_ONLY, SERVER }` and an `AppModeStore` (`expect`/`actual`,
  `package org.homeflow.app.shared.config`) with `fun load(): AppMode?`, `fun save(mode:
  AppMode)`, `fun clear()`. It must be readable **before** any unlock, so it cannot live in
  the SQLCipher DB (which needs the DEK, which needs the chosen mode — chicken/egg). The
  value is a non-sensitive enum: **Android** = plain `SharedPreferences`; **desktop** = a
  small properties file at `~/.homeflow/mode.properties`. `null` (unset) ⇒ first run ⇒ show
  the chooser.
- **D-14.2 — `SessionController` interface; `AuthController` implements it; `repository`
  moves into `AuthState.Authenticated`.** Define `interface SessionController` (package
  `org.homeflow.app.shared.auth`) exposing exactly what `App.kt` consumes:
  `val state: StateFlow<AuthState>`, `val usesPassphraseGate: Boolean`, `fun start()`,
  `suspend fun login()`, `suspend fun enroll(secret: String)`,
  `suspend fun unlock(secret: String?)`, `suspend fun logout()`,
  `suspend fun deleteAccount(): ApiResult<Unit>`. To remove the "repository only valid after
  unlock" sharp edge for the local controller, **move the repository into the state**:
  `AuthState.Authenticated(val user: UserDto, val repository: HomeFlowRepository)`. Update
  `App.kt`'s `Authenticated` branch to pass `current.repository` to `AppShell` (AppShell's
  signature is unchanged). `AuthController` already has every method + `state` +
  `usesPassphraseGate`; make it `: SessionController`, delete its top-level `repository` val,
  and have `loadUser()` put the repository into `Authenticated`. Its behavior is otherwise
  **byte-for-byte unchanged** (Mode B = today).
- **D-14.3 — Mode A auth = app-lock + DEK only; zero network.** New
  `LocalSessionController(gate: AppLockGate, keyStore: LocalKeyStore, modeStore:
  AppModeStore, dbFactory: LocalDatabaseFactory) : SessionController`:
  - `start()` → `Locked(needsEnrollment = gate.needsEnrollment())` (Mode A never enters
    `LoggedOut`; there is no remote login).
  - `login()` → no-op (unused in Mode A; the chooser, not a login screen, drives mode).
  - `enroll(secret)` → `gate.enroll(secret)` then immediately `unlock(secret)`.
  - `unlock(secret?)` → `Authenticating`; if `!gate.authenticate(secret)` → back to
    `Locked`; else **load-or-create the DEK** (`keyStore.loadOrCreateDek()`), open the DB
    (`dbFactory.create(dek)`), `LocalBootstrap.seed(db)`, build `LocalDataSource(db)` +
    `HomeFlowRepository(...)`, then `Authenticated(localUser, repository)`. The synthetic
    user is `LocalDataSource.getMe()`.
  - `logout()` → drop the in-memory DB/repository handle and return to
    `Locked(needsEnrollment = false)` (re-lock; the DEK stays in the key store).
  - `deleteAccount()` → `localDataSource.deleteAccount()` (wipes user rows) **and**
    `keyStore.clearDek()` **and** reset the gate enrollment (desktop: clear the keychain
    passphrase record; Android: nothing to clear) **and** `modeStore.clear()`, then surface
    a terminal state so `AppRoot` re-shows the chooser. Construct **no `HttpClient`** and
    import no Ktor client anywhere in this class (the no-network invariant; asserted by a
    test).
- **D-14.4 — DEK generation.** Add `expect fun secureRandomBytes(size: Int): ByteArray`
  (`package org.homeflow.app.shared.crypto`; both actuals use `java.security.SecureRandom`
  — both targets are JVM). Add a `commonMain` extension
  `fun LocalKeyStore.loadOrCreateDek(): ByteArray = loadDek() ?: secureRandomBytes(32).also
  { saveDek(it) }`. The DEK is OS-secure-store-rooted (same posture as the refresh token);
  the app-lock is the **use-gate**, not a passphrase-derived KEK (consistent with how the
  refresh token is gated today — see `ARCHITECTURE-client.md`).
- **D-14.5 — Composition root = new `AppRoot`; entry points call it.** Add
  `@Composable fun AppRoot(serverConfig: AuthConfig = defaultAuthConfig())`: read
  `AppModeStore.load()`; `null` → `ModeChooserScreen(onLocal = { save(LOCAL_ONLY); … },
  onServer = { save(SERVER); … })`; `LOCAL_ONLY` → `App(localSessionController())`;
  `SERVER` → `App(keycloakSessionController(serverConfig))`. `App(controller:
  SessionController)` replaces today's `App(controller: AuthController)`. Desktop
  `main.kt` → `AppRoot()`; Android `MainActivity` → `AppRoot(config)` (keeps its debuggable
  host override for Mode B). After `deleteAccount` clears the mode, `AppRoot` falls back to
  the chooser on recomposition.
- **D-14.6 — Mode B in Phase 14 is today's behavior, UNCHANGED.** "Connect to a server"
  selects the existing `AuthController` over the **compile-time** `AuthConfig` host. Runtime
  server-host entry is **Phase 15** — do not add it here. One binary serves both modes; mode
  is a runtime choice. There is **no** separate "local-only build flavor."
- **D-14.7 — Local export built in `:app:shared`, emitting the Phase 12 `HomeFlowExport`
  DTO.** Add `LocalExporter(db: HomeFlowDb, refData: LocalRefData, logsStore:
  LocalDailyLogsStore)` with `fun export(): HomeFlowExport`: iterate cycles (sorted by
  `start_date`) → `ExportCycle(start, end)`; for each cycle's logs
  (`logsStore.getLogsByCycleId`) build `ExportDay` from `logsStore.assembleDto(row)`, mapping
  each option/location **UUID → slug** (add `optionSlugById()` + `locationSlugById()` reverse
  maps to `LocalRefData`), `cycleStartDate = ` the cycle's start. Notes/sex are already
  plaintext in the local DB → copied straight through (sex as slug array). The result
  serializes (kotlinx-serialization) to the **exact** `homeflow_export` JSON the server's
  `POST /import?source=homeflow` accepts. **Export only** — no local *import* in Phase 14.
- **D-14.8 — Export-to-file is a platform seam.** Add
  `expect suspend fun writeExportFile(suggestedName: String, json: String): Boolean`
  (`package org.homeflow.app.shared.platform`): **desktop** = AWT `FileDialog`/Swing
  `JFileChooser` save dialog, write UTF-8, return false if cancelled; **Android** = Storage
  Access Framework `ACTION_CREATE_DOCUMENT` via the `AndroidAppContext` launcher pattern
  already used for AppAuth. Wire an "Export my data" action into `PreferencesScreen`
  (Settings tab), available in Mode A (a `onExport: (suspend () -> Unit)?` passed through
  `AppShell`; null/hidden in Mode B for this phase).

### File manifest (exhaustive — create / modify exactly these)

**Create — commonMain:**
1. `config/AppMode.kt` — the enum.
2. `config/AppModeStore.kt` — interface + `expect fun createAppModeStore(): AppModeStore`.
3. `auth/SessionController.kt` — the interface (D-14.2).
4. `auth/LocalSessionController.kt` — Mode A controller (D-14.3).
5. `auth/SessionControllerFactory.kt` — `localSessionController()` /
   `keycloakSessionController(config)` builders (the latter wraps today's `buildAuthController`).
6. `crypto/SecureRandom.kt` — `expect fun secureRandomBytes(size: Int): ByteArray` + the
   `LocalKeyStore.loadOrCreateDek()` extension.
7. `data/local/LocalExporter.kt` — D-14.7.
8. `ui/AppRoot.kt` — composition root + mode dispatch (D-14.5).
9. `ui/ModeChooserScreen.kt` — first-run chooser (two large buttons; style like `LoginScreen`).
10. `platform/ExportFile.kt` — `expect suspend fun writeExportFile(...)` (D-14.8).

**Modify — commonMain:**
11. `auth/AuthController.kt` — `: SessionController`; remove the top-level `repository` val;
    `loadUser()` constructs the repository into `AuthState.Authenticated`.
12. `auth/AuthController.kt` (same file) — `AuthState.Authenticated` gains
    `val repository: HomeFlowRepository`.
13. `ui/App.kt` — `App(controller: SessionController)`; `Authenticated` branch passes
    `current.repository` to `AppShell`.
14. `ui/shell/AppShell.kt` — add `onExport: (suspend () -> Unit)? = null`, thread it to
    `PreferencesScreen`.
15. `ui/screens/PreferencesScreen.kt` — add an "Export my data" button when `onExport != null`.
16. `data/local/LocalRefData.kt` — add `optionSlugById()` + `locationSlugById()` reverse maps.

**Create — platform actuals (6):**
17. `androidMain/config/AppModeStore.android.kt` (SharedPreferences) +
    `jvmMain/config/AppModeStore.jvm.kt` (`~/.homeflow/mode.properties`).
18. `androidMain/crypto/SecureRandom.android.kt` + `jvmMain/crypto/SecureRandom.jvm.kt`
    (`java.security.SecureRandom`).
19. `androidMain/platform/ExportFile.android.kt` (SAF `ACTION_CREATE_DOCUMENT`) +
    `jvmMain/platform/ExportFile.jvm.kt` (Swing/AWT save dialog).

**Modify — entry points (2):**
20. `app/desktopApp/src/main/kotlin/org/homeflow/main.kt` — `App()` → `AppRoot()`.
21. `app/androidApp/src/main/kotlin/org/homeflow/MainActivity.kt` — `App(controller)` →
    `AppRoot(config)`; register the SAF create-document launcher into `AndroidAppContext`
    (mirror the existing `authLauncher` wiring).

**Docs (3):**
22. `__docs/ARCHITECTURE-client.md` — Mode A, the `SessionController` seam, the `AppRoot`
    mode dispatch, and the "no health data at rest" **Mode-A exception** (encrypted local store).
23. `__docs/threat-model.md` — add the Mode-A at-rest threat + mitigation (D5: whole-DB
    SQLCipher, DEK in OS secure store, app-lock use-gate).
24. `CHANGELOG.md` — Added: "Use HomeFlow entirely on one device — no server required."

### Tests to add (jvmTest unless noted)
- **`LocalSessionControllerTest`** — `start()` → `Locked` with `needsEnrollment` from a fake
  gate; `enroll`→`unlock` reaches `Authenticated` and its `repository` reads/writes the local
  store (start a cycle, read it back); `logout()` returns to `Locked`; `deleteAccount()`
  wipes data, calls `clearDek()`, and clears the mode (use fakes for `AppLockGate`/
  `LocalKeyStore`/`AppModeStore`, an in-memory `HomeFlowDb` via `TestDbHelper`).
- **No-network assertion** — a test (reflection or a structural check) that
  `LocalSessionController` holds **no** `HttpClient` field and the Mode-A path constructs
  none. (Acceptable: assert `LocalSessionController` has no member of type `HttpClient`.)
- **`LocalExporterTest`** — seed a full day (every category, pain w/ severities, notes, sex)
  through `HomeFlowRepository(LocalDataSource)`, then `LocalExporter.export()`: assert
  `homeflow_export == 1`, selections are **slugs** (no UUID via the `[0-9a-f]{8}-` regex),
  `notes`/`sex` are plaintext slugs, and `cycleStartDate` links each day to its cycle — i.e.
  byte-shape parity with the server's export test.
- **`AppModeStoreTest`** (jvm) — `save`→`load` round-trips; `clear()` → `load()==null`.
- **Manual / dev-server** (document, not automated here): the exported file imported via
  `POST /import?source=homeflow` into a dev server is lossless (deep-compare) — this closes
  the migration loop that Phase 15 depends on.

### Guardrails — do NOT
- Do NOT add runtime server-host config, a "Connect to a server" host field, or any sync —
  Phases 15/16. "Connect to a server" in Phase 14 = today's compile-time Keycloak path.
- Do NOT construct an `HttpClient`, import a Ktor client, or reference `AuthConfig`/OIDC in
  the Mode-A path (`LocalSessionController`, `AppRoot`'s local branch, the chooser).
- Do NOT extract a `:core` export mapper — build `LocalExporter` in `:app:shared` against the
  existing `HomeFlowExport` DTO (D-14.7). Do NOT call server code from the client.
- Do NOT store `AppMode` (or anything pre-unlock) in the encrypted local DB (D-14.1).
- Do NOT change `LocalDataSource`/`LocalDatabaseFactory`/`LocalKeyStore`/`AppLockGate`
  behavior — Mode A composes them as-is.
- Do NOT add a local *import* path (export only). Do NOT log health data, the export JSON,
  the DEK, or the passphrase. Keep `FLAG_SECURE` on Android.
- Do NOT regress Mode B: `AuthController`'s flow must stay identical (only the `: SessionController`
  conformance + moving `repository` into `Authenticated`).

### Done when
- [ ] `./gradlew :app:shared:check` green (new tests pass; ktlint + detekt clean; Android
  host-test target compiles). `:app:androidApp:assembleDebug` + `:app:desktopApp` build.
- [ ] **Manual, desktop-only, offline** (no server/Keycloak): fresh launch → chooser → "Use
  this device only" → set a passphrase → log a full day (every category, pain w/ severities,
  notes, sex) → start/close cycles → reorder preferences → **relaunch, unlock, all data
  present**. Same on Android with biometrics.
- [ ] Airplane mode / no server reachable has **no effect** on any Mode-A operation
  (no network is even attempted).
- [ ] Analytics + phase indicator render from local data (same `:core` math) — visible in the
  Analytics/Dashboard tabs.
- [ ] **Export:** the "Export my data" action writes a `homeflow_export` file;
  `LocalExporterTest` proves it is slug-keyed/UUID-free with plaintext notes/sex; the
  documented dev-server import of that file is lossless.
- [ ] **Delete:** account deletion wipes the local data, clears the DEK, and clears the mode;
  relaunch shows the first-run chooser (`LocalSessionControllerTest` + manual).
- [ ] **No-network proof:** `LocalSessionController` constructs no `HttpClient` (asserted in a
  test); the Mode-A path references no `AuthConfig`/OIDC.
- [ ] Signed desktop installer + Android build produced and launch clean into the chooser.
- [ ] `ARCHITECTURE-client.md` + `threat-model.md` updated; `CHANGELOG.md`: "Use HomeFlow
  entirely on one device — no server required."

### Risks / stop-conditions
- **Export-to-file platform dialog** is the fiddliest bit. If the Android SAF
  `ACTION_CREATE_DOCUMENT` round-trip (launcher → content URI → write stream) fights the
  existing `AndroidAppContext` launcher wiring, STOP and report — do not write the export to
  app-private storage or a hardcoded path as a silent fallback.
- **Moving `repository` into `AuthState.Authenticated`** touches `App.kt` + `AppShell`
  call-sites; the compiler verifies all of them. If any other code reads
  `AuthController.repository` directly (grep first), update it — do not re-add the val.
- **First-run gate on Android.** `gate.needsEnrollment()` is desktop-meaningful (passphrase);
  Android biometric "enrollment" is owned by the OS. In Mode A on Android, `enroll` is a
  no-op and `unlock(null)` shows the biometric prompt — confirm the `Locked` →
  `needsEnrollment=false` path drives biometrics, matching today's `AuthController` behavior.
- **`deleteAccount` terminal state.** Ensure clearing the mode actually re-renders the chooser
  (the state read in `AppRoot` must recompose). If `AppRoot` caches the mode in a
  non-observable `remember`, deletion won't return to the chooser — use observable state.

---

## Phase 15 — Server-connected mode + "adopt a server" migration (Mode B)

> **Executable spec.** Decisions are **closed**. Compose screen *layout* has latitude
> (match the existing screens' style), but every seam, type, persisted key, state
> transition, and file below is fixed. Do not add any sync, offline cache, or two-way
> merge — those are Phase 16. If the code contradicts the spec, **STOP and report**.

**Goal:** ship **Mode B** — let the user point the app at their self-hosted server at
**runtime** (today's compile-time host becomes user-entered + persisted), run the existing
Keycloak OIDC + PKCE login against it, and perform the **one-time "adopt a server"
migration** that lifts a Mode-A device's local data up to the server (local export →
`POST /import?source=homeflow`, Phase 12). After adoption the app is an online client
exactly as today; a second device "Connect to a server" + login and sees the same data.
This is the canonical "I have a desktop → I stand up a server + get a phone" story —
**without** offline sync (that is Phase 16).

### Why now / what's actually left
The seam (11), the interchange format + server import (12), the local store (13), and the
mode chooser + `SessionController` + `LocalExporter` (14) are done. Mode B is therefore
**runtime host config + a host-entry screen + one new remote upload call + a migration
orchestrator + Settings plumbing** — almost no new domain logic. `AuthController`,
`RemoteDataSource`, the OIDC actuals, and `AppRoot`'s SERVER branch already exist; this
phase makes the host runtime and adds the adoption path.

### Key facts (verified against the current client — do not violate)
- `AuthConfig(host, realm, scopes, scheme)` (`config/AuthConfig.kt`) derives `issuer`,
  `authorizationEndpoint`, `tokenEndpoint`, `endSessionEndpoint`, and `apiBaseUrl` from a
  **single `host`** string. `defaultAuthConfig() = AuthConfig(host = platformOidc.defaultHost)`.
  `platformOidc` (per-platform `clientId`/`redirectUri`/`defaultHost`) is **unchanged** by
  this phase — only the **host** becomes runtime. `buildAuthController(config)` builds the
  OIDC client + token store + gate from the config; `keycloakSessionController(config)`
  wraps it (`auth/SessionControllerFactory.kt`).
- `AppRoot(serverConfig: AuthConfig = defaultAuthConfig())` already dispatches `null` →
  chooser, `LOCAL_ONLY` → `localSessionController`, `SERVER` →
  `keycloakSessionController(serverConfig)`. Phase 15 makes the SERVER branch read the
  **persisted host** and adds a host-entry sub-gate when none is stored yet.
- **`RemoteDataSource` has no import method** and the `HomeFlowDataSource` seam has exactly
  20 methods, none of them import. The adoption upload is therefore a **remote-only** call
  added to `RemoteDataSource` **outside** the seam (LocalDataSource must NOT implement it).
- The server import is `POST /api/v1/import?source=homeflow`, **multipart/form-data** with a
  single file part (the route reads the first `PartData.FileItem` regardless of part name;
  use name `file`), 25 MB cap, returns `ImportResultDto(cyclesCreated, dailyLogsCreated,
  dailyLogsSkipped, warnings)`. It is **additive + idempotent** (Phase 12, D-12.7) — re-running
  is safe.
- `LocalExporter(db, refData, logsStore).export(): HomeFlowExport` (Phase 14, D-14.7) already
  produces the exact slug-keyed JSON the import accepts. `LocalKeyStore.loadDek()` returns the
  DEK **or null if none was ever created**; `LocalDatabaseFactory().create(dek)` opens the
  encrypted DB; `LocalBootstrap.seed(db)` + `LocalBootstrap.LOCAL_USER_ID` exist. Reuse all of
  these — do **not** modify them.
- Ktor client `MultiPartFormDataContent` + `formData {}` live in `ktor-client-core`
  (`io.ktor.client.request.forms.*`) — already on the `:app:shared` classpath. **No new
  dependency.** The auth/bearer plugin already attaches the access token to outgoing requests.

### Decisions (closed)

- **D-15.1 — Runtime server config lives in a NEW non-sensitive `ServerConfigStore`
  (`expect`/`actual`, sibling of `AppModeStore`).** It must be readable **before** unlock and
  is **not** secret (the host is a public hostname), so it does **not** live in the encrypted
  DB. Interface (package `org.homeflow.app.shared.config`):
  ```kotlin
  interface ServerConfigStore {
      fun loadHost(): String?        // null until the user enters one
      fun saveHost(host: String)
      fun isMigrated(): Boolean      // true once this device's local data was uploaded
      fun setMigrated()
      fun clear()                    // host + migrated flag (account deletion / mode reset)
  }
  expect fun createServerConfigStore(): ServerConfigStore
  ```
  Actuals mirror `AppModeStore`: **Android** = plain `SharedPreferences` (a new prefs file
  `homeflow_server`); **desktop** = a properties file `~/.homeflow/server.properties` (keys
  `server_host`, `migrated`). Do **not** widen `AppModeStore`; keep the two stores separate
  (`AppMode` is the dispatch enum, `ServerConfigStore` is the host + migration flag).
- **D-15.2 — Host is the only runtime piece; `platformOidc` is untouched.** The persisted host
  feeds `AuthConfig(host = storedHost)`; `clientId`, `redirectUri`, `realm`, `scheme`, and the
  derived endpoints come from the existing constants. Add a tiny helper to `AuthConfig.kt`:
  `fun authConfigForHost(host: String): AuthConfig = AuthConfig(host = host)` (keeps call sites
  uniform; no other change to `AuthConfig`). The Android debug `localhost:8443` override in
  `MainActivity` becomes the fallback **only when no host is stored** (stored host wins).
- **D-15.3 — `AppRoot` SERVER branch = host-entry gate → login.** Replace the SERVER branch:
  read `serverConfigStore.loadHost()`. `null` → render `ServerConnectScreen` (collect host);
  on submit `saveHost(host)` and recompose. Non-null → build
  `keycloakSessionController(authConfigForHost(host))` and render `App(controller, …)` as
  today. The chooser's `onServer` no longer needs to pre-seed a host — it just sets
  `AppMode.SERVER`; the SERVER branch's own gate collects the host. `AppRoot` keeps reading
  `AppModeStore`; add a `remember { createServerConfigStore() }`.
- **D-15.4 — `ServerConnectScreen` (new).** A single host text field (placeholder
  `myhost.ts.net`) + "Connect" button, styled like `LoginScreen`/`ModeChooserScreen`.
  Normalize the entry before saving: trim, strip a leading `https://`/`http://` scheme and any
  trailing `/`, reject blank (inline error). Persist the bare host (e.g. `myhost.ts.net`).
  Reachable from (a) the SERVER-branch gate and (b) Settings "Connect to a server" in a Mode-A
  install (D-15.8). Do **not** validate reachability here — a bad host surfaces as the existing
  OIDC/`getMe` failure path.
- **D-15.5 — Mode-B data source = `RemoteDataSource`, unchanged; the local DB is left
  DORMANT, never wiped, never edited in Mode B.** After adoption the app is an online client
  exactly as today (`AuthController` over the runtime host). The Mode-A SQLCipher DB and its
  DEK are **not** deleted (they are the pre-migration backup and become Phase 16's live cache)
  but Mode B **never reads or writes them**. Pin: in Mode B, reads and writes go to the server;
  the local store is not the source of truth. Do **not** add any local-cache write path in
  Mode B (that would create the very drift Phase 16 exists to solve).
- **D-15.6 — Adoption upload = one new remote-only method + a multipart helper.** Add to
  `data/ApiResult.kt` (next to `apiSend`) a helper:
  ```kotlin
  suspend fun HttpClient.apiUploadImport(path: String, json: String): ApiResult<ImportResultDto>
  ```
  that POSTs `MultiPartFormDataContent(formData { append("file", json, Headers { … filename …
  ContentType.Application.Json }) })` and maps the response via `toApiResult`/`toFailure`
  (same error mapping as the other helpers). Add to **`RemoteDataSource` only** (NOT the
  `HomeFlowDataSource` interface):
  ```kotlin
  suspend fun uploadHomeflowImport(json: String): ApiResult<ImportResultDto> =
      client.apiUploadImport("import?source=homeflow", json)
  ```
  (`import?source=homeflow` resolves under the `apiBaseUrl` trailing-slash, like every other path.)
- **D-15.7 — `ServerMigration` orchestrator (new, commonMain).** A pure orchestrator that
  builds the local export and uploads it, decoupled from `AuthController` via an uploader
  lambda so it is unit-testable:
  ```kotlin
  class ServerMigration(
      private val keyStore: LocalKeyStore,
      private val dbFactory: LocalDatabaseFactory,
      private val serverConfigStore: ServerConfigStore,
  ) {
      /** True only when a local DB with at least one cycle exists and it was not yet migrated. */
      fun hasUnmigratedLocalData(): Boolean
      /**
       * Builds the local HomeFlowExport, uploads it via [upload], and on success calls
       * serverConfigStore.setMigrated(). Returns the server summary, or null if there was
       * nothing to migrate. Never throws on an empty/absent local store.
       */
      suspend fun migrate(upload: suspend (String) -> ApiResult<ImportResultDto>): ImportResultDto?
  }
  ```
  `hasUnmigratedLocalData()` short-circuits to `false` when `keyStore.loadDek() == null`
  (a pure Mode-B install never created a DEK — do **not** call `loadOrCreateDek` here, that
  would fabricate an empty encrypted DB). When a DEK exists, open the DB
  (`dbFactory.create(dek)` + `LocalBootstrap.seed`), check for ≥1 cycle row, and return
  `false`/skip when empty. `migrate` reuses `LocalExporter` to serialize the same JSON the
  server import accepts. After a successful upload, `setMigrated()` so it is not offered again.
- **D-15.8 — Migration is surfaced two ways, both calling `ServerMigration.migrate`:**
  1. **Auto-prompt once**, right after the first successful server login on a device with
     unmigrated local data: `AppRoot`'s SERVER branch, on the `Authenticated` state, if
     `serverMigration.hasUnmigratedLocalData()` and not already prompted this session, shows an
     `AlertDialog` "Upload this device's data to the server?" → Confirm runs `migrate`, shows the
     `ImportResultDto` summary; Skip dismisses (does **not** set migrated, so Settings can still
     offer it). Use an **observable** flag so the dialog state recomposes (mirror Phase 14's
     `deleteAccount` recompose caveat).
  2. **Settings action** (Mode B, while unmigrated local data exists): an "Upload local data to
     server" button in `PreferencesScreen` that runs the same `migrate` and shows the summary.
  The upload lambda passed to `migrate` is `controller::uploadLocalData` (D-15.9).
- **D-15.9 — `AuthController` exposes a migration upload hook; `AppRoot`'s SERVER branch holds
  the concrete `AuthController`.** Add to `AuthController` a public
  `suspend fun uploadLocalData(json: String): ApiResult<ImportResultDto> =
  remote.uploadHomeflowImport(json)` (keep a `private val remote: RemoteDataSource` typed
  reference alongside the existing `api: HomeFlowDataSource` — they are the same instance; type
  the field as `RemoteDataSource` and assign `api = remote`). It is valid only while
  `Authenticated` (the bearer token is attached then). This is **not** on `SessionController`
  (it is a remote-only migration affordance). `AppRoot`'s SERVER branch calls
  `buildAuthController(config)` to obtain the concrete `AuthController`, uses it as a
  `SessionController` for `App(...)`, and passes `it::uploadLocalData` to `ServerMigration`.
- **D-15.10 — Settings shows mode + host; Mode-A installs get "Connect to a server".** Thread
  the current `AppMode`, the connected host (Mode B), an `onConnectServer: (() -> Unit)?`
  (Mode A only), and an `onUploadToServer: (suspend () -> ImportResultDto?)?` (Mode B + unmigrated)
  through `AppShell` to `PreferencesScreen` (mirror the Phase-14 `onExport` threading). In Mode A,
  "Connect to a server" flips `AppMode = SERVER` (via an `AppRoot` callback that does
  `appModeStore.save(SERVER)` + sets the observable mode state) **without** wiping the local DB,
  so `AppRoot` recomposes into the SERVER host-entry gate. The user's local data stays put and is
  offered for upload after login.

### File manifest (exhaustive — create / modify exactly these)

**Create — commonMain:**
1. `config/ServerConfigStore.kt` — interface + `expect fun createServerConfigStore()` (D-15.1).
2. `auth/ServerMigration.kt` — the orchestrator (D-15.7).
3. `ui/ServerConnectScreen.kt` — host-entry screen (D-15.4).

**Create — platform actuals (2):**
4. `androidMain/.../config/ServerConfigStore.android.kt` (SharedPreferences `homeflow_server`).
5. `jvmMain/.../config/ServerConfigStore.jvm.kt` (`~/.homeflow/server.properties`).

**Modify — commonMain:**
6. `config/AuthConfig.kt` — add `fun authConfigForHost(host: String): AuthConfig` (D-15.2).
7. `data/ApiResult.kt` — add `apiUploadImport(path, json)` multipart helper (D-15.6).
8. `data/RemoteDataSource.kt` — add `uploadHomeflowImport(json)` (D-15.6); **not** on the seam.
9. `auth/AuthController.kt` — type the data-source field as `RemoteDataSource remote` (assign
   `api = remote`); add public `uploadLocalData(json)` (D-15.9). No other behavior change.
10. `ui/AppRoot.kt` — SERVER branch host-entry gate + concrete `AuthController` + auto-migration
    prompt; Mode-A→SERVER mode flip callback; `remember { createServerConfigStore() }` +
    `ServerMigration` (D-15.3/D-15.8/D-15.10).
11. `ui/App.kt` — thread the new optional Settings callbacks (`onConnectServer`,
    `onUploadToServer`, current mode/host) to `AppShell` (additive params, default null/Mode-A
    unaffected).
12. `ui/shell/AppShell.kt` — add the optional params and pass to `PreferencesScreen` (mirror
    `onExport`).
13. `ui/screens/PreferencesScreen.kt` — add a "Server" section: Mode A → "Connect to a server";
    Mode B → show host + (if unmigrated) "Upload local data to server" with an `ImportResultDto`
    summary.

**Modify — entry points (1):**
14. `app/androidApp/.../MainActivity.kt` — pass the stored host (fallback to the debug
    `localhost:8443` / `defaultAuthConfig()` only when none stored) into `AppRoot`. Desktop
    `main.kt` needs no change (it already calls `AppRoot()` and the SERVER branch reads the store).

**Docs (3):**
15. `__docs/ARCHITECTURE-client.md` — runtime host config replaces the compile-time constant;
    the `ServerConfigStore` seam; the Mode-B "local DB dormant, server is source of truth" rule;
    the adoption (local-export → import) path.
16. `__docs/DEPLOYMENT.md` — the "adopt a server" runbook (enter the canonical Tailscale
    `*.ts.net` host; the `iss` must match that host — cross-ref `KEYCLOAK.md`).
17. `CHANGELOG.md` — Added: "Connect the app to your self-hosted server and upload your local
    data to it; use the same data across devices."

### Tests to add (jvmTest unless noted)
- **`ServerConfigStoreTest`** (jvm, against `DesktopServerConfigStore` with a temp dir):
  `saveHost`→`loadHost` round-trips; `isMigrated` defaults false and flips after `setMigrated`;
  `clear()` → host null + migrated false.
- **`ServerMigrationTest`** (jvm, fakes for `LocalKeyStore`/`ServerConfigStore`, in-memory DB via
  `TestDbHelper`, a recording upload lambda): (a) no DEK → `hasUnmigratedLocalData()==false`,
  `migrate{}` returns null, upload never called; (b) DEK + seeded cycles/day → `migrate` builds a
  slug-keyed `HomeFlowExport`, calls upload **once** with that JSON, returns the stubbed
  `ImportResultDto`, and `isMigrated()` becomes true; (c) already migrated → skip (upload not
  called); (d) the JSON passed to upload is UUID-free (reuse the `[0-9a-f]{8}-` regex from
  `LocalExporterTest`).
- **`RemoteImportUploadTest`** (commonTest, Ktor `MockEngine`): `uploadHomeflowImport(json)` issues
  a `POST` to `…/import?source=homeflow` with a `multipart/form-data` body containing the JSON and
  decodes a `200` `ImportResultDto`; a `400 VALIDATION_ERROR` body maps to
  `ApiResult.Failure(VALIDATION_ERROR)`.
- **Manual / dev-server** (document, not automated): a Mode-A desktop with logged data → Settings
  "Connect to a server" → enter host → Keycloak login (password + TOTP) → confirm the upload
  prompt → server shows the data; re-running the upload is a no-op (`cyclesCreated=0,
  dailyLogsCreated=0`). A fresh Android "Connect to a server" + login sees the same data.

### Guardrails — do NOT
- Do NOT add any sync engine, outbox push/pull, server delta routes, or a Mode-B local-write
  cache — Phase 16. Mode B reads/writes the server only.
- Do NOT wipe the local DB or DEK on adoption (they are the backup + Phase-16 cache). Do NOT edit
  the local store in Mode B.
- Do NOT add `uploadHomeflowImport`/`uploadLocalData` to the `HomeFlowDataSource` interface —
  it is remote-only; `LocalDataSource` must not implement it.
- Do NOT change `platformOidc`, `clientId`, `redirectUri`, the OIDC actuals, token storage, or
  the body-logging posture (refresh token in secure store, access token in memory, **no body
  logging** — the export JSON is health data; never log it).
- Do NOT introduce a new server route, a new `ErrorCode`, or a new export/import format — reuse
  Phase 12's `POST /import?source=homeflow` exactly.
- Do NOT regress Mode A or the chooser: `LocalSessionController`, `LocalExporter`,
  `LocalDatabaseFactory`, `LocalKeyStore`, `AppLockGate`, and Phase-14 tests stay unchanged.
- Do NOT store the host in the encrypted DB (chicken/egg with the DEK) — `ServerConfigStore` is
  pre-unlock, non-encrypted (D-15.1).

### Done when (automated unless noted)
- [x] `./gradlew :app:shared:check` green (ktlint + detekt + the three new test files; Android
  host-test target compiles). `:app:androidApp:assembleDebug` + `:app:desktopApp` build.
- [x] `ServerConfigStoreTest`, `ServerMigrationTest`, `RemoteImportUploadTest` pass.
- [ ] **Manual, two devices:** a Mode-A desktop with logged data enters a server host →
  completes Keycloak login (password + TOTP) → confirms the upload → and thereafter
  reads/writes that data **on the server** (verified via the server DB or a second client).
- [ ] **Manual, second device:** a fresh Android app picks "Connect to a server," logs into the
  same server, and sees the desktop-originated data — desktop + Android show identical data.
- [ ] **Idempotent upload:** re-running the upload is a no-op (`cyclesCreated=0,
  dailyLogsCreated=0`; relies on Phase 12).
- [ ] Switching a device to Mode B does **not** lose its local data (uploaded first; local DB
  retained but no longer authoritative).
- [ ] Token/security posture unchanged (refresh token in secure store, access token in memory,
  no body logging) — re-verify the client security checklist; the export JSON is never logged.
- [x] `ARCHITECTURE-client.md` + `DEPLOYMENT.md` + `CHANGELOG.md` updated.

### Risks / stop-conditions
- **Ktor client multipart.** If `MultiPartFormDataContent`/`formData` is not resolvable on the
  `:app:shared` client classpath, or the server rejects the part (it expects the first
  `PartData.FileItem`), **STOP and report** — do not switch the server route to a raw body or
  add a new dependency without an explicit decision. (Per D-15.6 multipart should already be on
  the classpath via `ktor-client-core`.)
- **Cross-mode DB access during migration.** `ServerMigration` opens the encrypted local DB
  while the app is running in Mode B. If opening it requires app-lock state that is unavailable
  post-server-login (the gate is a Mode-A concern), **STOP and report** — the DEK is rooted in
  the OS secure store and should be readable via `LocalKeyStore.loadDek()` without the gate, but
  confirm before assuming.
- **Runtime host ↔ Keycloak.** The entered host must match what Keycloak stamps in `iss`,
  or login 401s (the canonical-hostname gotchas in
  `CLAUDE.md`/`KEYCLOAK.md`). This is a deployment/config concern, not a client bug — surface the
  OIDC/`getMe` failure clearly; do not try to "fix" it client-side.
- **Auto-prompt recompose.** If the migration `AlertDialog` is gated on a non-observable
  `remember`, it won't appear/recompose after `Authenticated` — use observable state (same caveat
  as Phase 14's `deleteAccount` → chooser).
- **`AuthController` field retype.** Typing the data-source field as `RemoteDataSource` must not
  change any existing behavior; the field is still used via the `HomeFlowDataSource` surface
  everywhere except the new `uploadLocalData`. The compiler verifies the call sites.

---

## Phase 16 — Offline sync engine (Mode C)

> Mode C is delivered by **three stacked sub-phases (16a → 16b → 16c)**, each its own branch
> that lands green independently (the "split for stability" rule used for 11–14). **16a** is
> server-only and additive (old clients keep working); **16b** adds the `:core` merge + the
> client reconciler; **16c** wires triggers, status UI, the delete UI, and the end-to-end
> convergence guarantees. Do not start a sub-phase until the prior one's **Done when** is fully
> green. Each sub-phase below is an **executable spec** — decisions are closed; if the code
> contradicts one, **STOP and report**.

### Shared model (binds all three sub-phases)
- **Authority:** in Mode C the data source is `LocalDataSource` (Phase 13) — authoritative for
  the device. The server is a **sync peer**, not the source of truth. A background `SyncEngine`
  pushes/pulls; the UI never blocks on the network.
- **Syncable aggregates (exactly three):** **cycle** (a `cycles` row), **day** (the
  `daily_logs` anchor **plus** all its sub-logs + pain, synced as ONE unit keyed by the anchor
  id — D2/D7), and **preferences** (whole-value). Sub-logs are **never** synced individually;
  a sub-log edit bumps the *day* aggregate.
- **Conflict resolution = Last-Write-Wins per aggregate.** Comparator = `updatedAt` (ISO-8601
  UTC instant strings, so lexicographic compare == chronological). Ties break by **greater
  `id` lexicographically** — deterministic and symmetric on every device, needing no notion of
  "who is server." **Tombstones participate in LWW** (a delete is a versioned state: a later
  edit resurrects; a later delete beats an earlier edit). Two domain-aware exceptions resolved
  by a shared `:core` rule, not raw LWW: **cycle boundaries** (two open cycles → the
  later-started stays open, the earlier is auto-closed via `autoCloseEndDate`) and
  **preferences** (a single whole-value aggregate).
- **Identity & references:** entity ids are the **shared client UUIDs (D1)** the server already
  honors (Phase 11) — so the day→cycle link crosses the wire as `cycleId` (UUID), **not** by
  date (this is the key difference from Phase 12 export/import, which mints new server ids).
  Selections cross as **slugs (D4)**; each side maps slug↔local-UUID at the boundary.
- **Encryption boundaries unchanged (D5):** local at rest = SQLCipher DEK; server at rest =
  `APP_ENCRYPTION_KEY` (notes/sex ciphertext in PG); the wire is **plaintext over TLS** (server
  decrypts to send, re-encrypts on store; client stores plaintext inside its encrypted DB).
  **No key ever crosses the network. No request/response body is ever logged** (it is health
  data).
- **Cursor:** the pull cursor is a **server-assigned monotonic sequence** (`server_seq`), never
  a timestamp — this sidesteps clock skew in the pull direction. `updatedAt` is used **only** as
  the LWW comparator.
- **Account deletion** stays a hard global purge (server cascade + Keycloak + local wipe) — it
  is **not** a tombstone-sync operation.

---

### Phase 16a — Server sync foundation (change-log, soft-delete, stamping, `/sync` + delete routes)

**Goal:** make the server a sync peer. Add a Flyway migration that introduces soft-delete on the
syncable anchors and a per-user-meaningful `sync_changes` change-log; stamp `updated_at` + record
a change row inside **every** write transaction; add the `GET/POST /api/v1/sync/changes`
protocol and the `DELETE` routes that produce tombstones. **Server-only, fully additive** — the
existing routes/DTOs are unchanged, so a pre-16 client keeps working. No client change here.

**Why first:** the client reconciler (16b) cannot be built or tested until the server can emit a
delta feed and accept a push. Building and proving the server side under Testcontainers first
means 16b integrates against a real, green protocol.

#### Key facts (verified against the current server — do not violate)
- Syncable anchors `cycles`, `daily_logs`, `daily_log_sex`, `pain_logs`,
  `user_dashboard_preferences` already have `updated_at timestamptz` but **no `deleted_at`** (the
  server currently hard-deletes nothing in syncable scope — there is **no** delete-cycle/delete-day
  route anywhere today). Sub-log tables have only `created_at` (replaced wholesale).
- Writes flow Route → Service → Repository (Exposed `transaction {}`). `ImportExportService`
  already assembles a whole-day, slug-keyed, decrypted aggregate (`exportAll`) and applies a
  slug-keyed day through the existing services (`importHomeflow`) — the `GET`/`POST` sync handlers
  reuse the **same assembly/apply approach** (decrypt-to-send, validate+encrypt-on-store), never
  touching ciphertext directly.
- `AppDependencies` (in `Application.kt`) constructs every repo/service; `configureRouting`
  (`plugins/Routing.kt`) mounts each module's routes. Adding a module = one repo + one service +
  one routes file + wiring in those two places (mirror `importexport`).
- Errors use the fixed 6-code `ErrorCode`; row-scoping is always by `principal.id`.

#### Decisions (closed)
- **D-16a.1 — Migration `V3__sync.sql`.** (a) `ALTER TABLE cycles ADD COLUMN deleted_at
  timestamptz NULL;` and the same for `daily_logs`. These two anchors are the only tombstonable
  aggregates (a day tombstone covers its sub-logs/pain; preferences is whole-value and never
  deleted). (b) Create `sync_changes(user_id uuid NOT NULL, entity_type text NOT NULL, entity_id
  uuid NOT NULL, server_seq bigint NOT NULL, updated_at timestamptz NOT NULL, deleted boolean NOT
  NULL, PRIMARY KEY(user_id, entity_type, entity_id))` with a global `BIGSERIAL`-backed sequence
  `sync_seq` for `server_seq`, and an index `(user_id, server_seq)`. One row **per aggregate**
  (upserted, latest state) — not an append log — so a pull returns each changed aggregate once.
  A global sequence is correct: each client filters to its own `user_id` and `server_seq >
  cursor`; cross-user gaps are invisible and harmless. Reads of live data must add
  `deleted_at IS NULL` (update the existing `cycles`/`daily_logs` queries' WHERE clauses).
- **D-16a.2 — `entity_type` ∈ {`cycle`, `day`, `preferences`}.** `entity_id` = the aggregate's
  UUID (`cycles.id`, `daily_logs.id`, or the prefs row id). A sub-log/pain write records a **`day`**
  change for its anchor id — never a per-sub-log change.
- **D-16a.3 — Change recording lives in a `ChangeLogRepository.record(userId, type, entityId,
  updatedAt, deleted)` called inside the SAME Exposed `transaction {}` as the write** (recording a
  change row is persistence, not business logic, so it is repository-layer; but the *caller* that
  knows the aggregate identity drives it). Every existing write must record: `CyclesRepository`
  (insert/close/delete → `cycle`), `DailyLogsRepository` (anchor insert, notes update, delete →
  `day`), `DailyLogSubsRepository` (every `replaceMulti/replaceSingle/replaceSex/replacePain` →
  `day` for the owning anchor), `PreferencesRepository` (upsert → `preferences`). **If wiring the
  record call into an existing repository transaction proves to require restructuring the
  transaction boundary, STOP and report** rather than recording in a second transaction (a second
  transaction breaks atomicity — the change row could be lost after a committed write).
- **D-16a.4 — New soft-delete operations (produce tombstones).**
  - `DELETE /api/v1/cycles/{id}` → `CyclesService.deleteCycle`: set `cycles.deleted_at = now()`,
    record `cycle` tombstone; **cascade**: soft-delete every `daily_logs` row in that cycle
    (`deleted_at = now()`, record a `day` tombstone each). 404 on unknown/foreign id.
  - `DELETE /api/v1/daily-logs/{date}` → `DailyLogsService.deleteDay`: set `daily_logs.deleted_at
    = now()` for the date, record a `day` tombstone. 404 if no live anchor for the date. (Sub-log
    rows stay but are unreachable — reads filter by the anchor's `deleted_at`.)
  - Both are additive routes; both stamp `updated_at` on the tombstoned anchor.
- **D-16a.5 — `GET /api/v1/sync/changes?since=<cursor>` (cursor defaults to 0).** Returns
  `SyncPullResponse` (D-16b.1 DTOs): every `cycle`/`day`/`preferences` aggregate for the principal
  whose `sync_changes.server_seq > since`, **including tombstones** (`deleted=true` carries id +
  `updatedAt` only), selections **slug-keyed**, notes/sex **decrypted to plaintext**, day→cycle by
  `cycleId`; plus `cursor` = the max `server_seq` returned (or `since` if none). Reuse the
  `ImportExportService` assembly for live days; for a tombstone emit the minimal aggregate.
- **D-16a.6 — `POST /api/v1/sync/changes` (body = `SyncPushRequest`).** For each incoming
  aggregate, apply **LWW vs. the server's current row** using the shared `:core`
  `mergeDecision(localUpdatedAt, localId, serverUpdatedAt, serverId)` (16b): if the incoming wins,
  upsert it through the existing services (honoring the client `id`/`cycleId` per D1, mapping
  slugs→option UUIDs per D4; a tombstone sets `deleted_at`); if the server wins, leave it. Then
  apply the **cycle-boundary** rule (D-16b.3) across the resulting open cycles. Return
  `SyncPushResponse` = the authoritative post-merge state of every touched aggregate (so the client
  adopts server-won values immediately) + the new `cursor`. **Row-scoped to `principal.id`** — an
  aggregate's `userId` is always the principal, never from the body. The push is **idempotent**
  (re-applying the same batch is a no-op by LWW) so an interrupted push is safe to retry.
- **D-16a.7 — Reuse services for all writes (D-12.6 lesson).** Sync apply goes through
  `DailyLogsService`/`DailyLogSubsService`/`CyclesService`/`PreferencesService` so validation +
  encryption are identical to a normal write. Do **not** write rows or ciphertext directly in the
  sync service. (If honoring a client-supplied `cycleId`/`id` on these paths needs a new
  explicit-insert variant, mirror Phase 12's `insertExplicit`.)

#### File manifest (16a, exhaustive)
**Create:**
1. `server/src/main/resources/db/migration/V3__sync.sql` — D-16a.1.
2. `server/.../modules/sync/ChangeLogRepository.kt` — `record(...)` + `findChangesSince(userId,
   cursor)` + `maxSeq(userId)` (Exposed).
3. `server/.../modules/sync/SyncService.kt` — `pull(principal, since): SyncPullResponse`,
   `push(principal, req): SyncPushResponse` (D-16a.5/6/7).
4. `server/.../modules/sync/SyncRoutes.kt` — `authenticate(KEYCLOAK_AUTH){ route("/api/v1/sync"){
   get("/changes"){…}; post("/changes"){…} } }`.
5. `server/src/test/.../integration/SyncTest.kt` — Testcontainers + in-process RS256 (mirror
   `ImportExportTest`).

**Modify:**
6. `core/.../dto/SyncDtos.kt` — **created in 16b**, but 16a depends on it; if 16a lands first,
   add the DTOs here in 16a and reference them (they are pure serialization, no client coupling).
7. `core/.../service/SyncMerge.kt` — likewise the pure `mergeDecision`; if 16a lands first, create
   it here (16b reuses it). (These two `:core` files are the contract both sides share — whichever
   sub-phase lands first creates them.)
8. `cycles/CyclesRepository.kt` + `CyclesService.kt` — `deleteCycle`; `deleted_at IS NULL` on
   reads; record `cycle` changes on insert/close/delete.
9. `dailylogs/DailyLogsRepository.kt` + `DailyLogsService.kt` — `deleteDay`; `deleted_at IS NULL`
   on reads; record `day` changes on anchor insert / notes / delete.
10. `dailylogs/DailyLogSubsRepository.kt` — record a `day` change on every replace.
11. `preferences/PreferencesRepository.kt` — record a `preferences` change on upsert.
12. `cycles/CyclesRoutes.kt` + `dailylogs/DailyLogsRoutes.kt` — add the two `DELETE` routes.
13. `Application.kt` (`AppDependencies`) + `plugins/Routing.kt` — construct + mount
    `ChangeLogRepository`/`SyncService`/`syncRoutes` (mirror `importexport`).
14. `__docs/API.md` — document `DELETE /cycles/{id}`, `DELETE /daily-logs/{date}`, and the
    `/sync/changes` GET/POST shapes (request/response/error codes).

#### Tests to add (`SyncTest`, Testcontainers)
- Stamping: any write (cycle create, sub-log put, notes, pain, prefs) creates/updates exactly one
  `sync_changes` row for the right aggregate with a fresh `server_seq`.
- `DELETE /cycles/{id}` tombstones the cycle **and** cascades `day` tombstones for its days;
  `DELETE /daily-logs/{date}` tombstones the day; both surface in `GET /sync/changes` with
  `deleted=true`; live reads (`GET /cycles`, `GET /daily-logs/{date}`) no longer return them.
- `GET /sync/changes?since=0` returns all aggregates slug-keyed + decrypted, day→cycle by
  `cycleId`, with a `cursor`; `since=<cursor>` returns only newer changes.
- `POST /sync/changes` LWW: incoming newer `updatedAt` wins (server row updated); incoming older
  loses (server row unchanged); a tie resolves by greater id deterministically; a tombstone with a
  newer `updatedAt` deletes; response carries the authoritative post-merge state + new cursor.
- Cycle-boundary: pushing a second open cycle auto-closes the earlier one (shared rule).
- **Security:** `GET`/`POST /sync/changes` are row-scoped — a second user's principal sees none of
  the first user's changes and cannot push into them (cross-user → its own scope only). No body is
  logged. Notes/sex are ciphertext in PG after a push (assert the column is not plaintext).
- Idempotency: re-`POST` the same batch → no duplicate rows, identical post-merge state.

#### Guardrails — do NOT
- Do NOT change any existing route's request/response/JSON/status; 16a is purely additive.
- Do NOT record a change in a separate transaction from the write (atomicity) — STOP if forced.
- Do NOT write health rows or ciphertext directly in `SyncService`; reuse the services (D-16a.7).
- Do NOT hard-delete in syncable scope; deletes are tombstones (D3). Account deletion stays the
  one hard purge.
- Do NOT emit option/location **UUIDs** on the wire (slugs only, D4); do NOT log sync bodies.
- Do NOT take `userId` from the request body — always `principal.id`.

#### Done when (16a)
- [ ] `./gradlew :server:check` green incl. `SyncTest` (all cases above) and **all** existing
  suites unchanged.
- [ ] `V3__sync.sql` applies cleanly; `cycles`/`daily_logs` have `deleted_at`; `sync_changes`
  exists with a working `server_seq`.
- [ ] Every write path records exactly one aggregate change; deletes tombstone (+ cascade for
  cycles); `GET/POST /sync/changes` behave per D-16a.5/6 with row-scoping + encryption intact.
- [ ] `git grep -n "deleted_at IS NULL"` shows the live-read filter on `cycles`/`daily_logs`.
- [ ] `API.md` updated. (No `CHANGELOG.md` entry yet — 16a ships no user-visible behavior on its
  own; 16c flips on Mode C.)

#### Risks / stop-conditions (16a)
- **Transaction boundary for change recording** (top risk) — see D-16a.3; STOP if it can't be done
  in-transaction.
- **Honoring client `id`/`cycleId` on apply** — if the existing services can't accept an explicit
  id on the sync-write path, add an `insertExplicit`-style variant (Phase 12 precedent); STOP if
  that would duplicate business logic.
- **Cascade delete volume** — soft-deleting a long cycle's days is many change rows; that is fine
  (one per day) but keep it in one transaction.

---

### Phase 16b — `:core` merge + client reconciler (outbox, SyncEngine, Mode C composition)

**Goal:** build the device half of sync against 16a's protocol: the pure `:core` merge contract,
outbox-append on **every** local write, a `sync_state` cursor, the `SyncEngine` (push outbox →
pull changes → merge into `LocalDataSource`), the delete-cycle/delete-day operations on the seam,
and the Mode-C composition that runs `LocalDataSource` primary with the engine attached. Triggers
+ status UI + delete UI are **16c** — here the engine exposes a `suspend fun syncNow()` driven by
tests.

**Why now:** 16a gives a real delta feed; the local store (13) + adopt-a-server upload (15) give
the initial state. 16b is the reconciler that makes two devices converge; proving it with a
deterministic `syncNow()` (no timers/UI) keeps it testable.

#### Key facts (verified against the current client — do not violate)
- `sync_outbox` exists (`SyncOutbox.sq`: `id, entity_type, entity_id, op, updated_at, synced`)
  with `insert/selectPending/markSynced/deleteAll`, but **nothing writes to it yet** (only
  `deleteAll` on account deletion). Local anchors already carry `updated_at`/`deleted_at`.
- The seam `HomeFlowDataSource` has **no delete method**; `RemoteDataSource` (HTTP) and
  `LocalDataSource` (SQLDelight) both implement it. Adding deletes grows the seam by two methods,
  implemented by both (Mode A/B get delete too — a welcome side effect).
- `LocalDataSource` composes `LocalCyclesStore/LocalDailyLogsStore/LocalSubsStore/LocalPrefsStore`;
  each write is the natural place to append an outbox row. `LocalExporter` already maps a day's
  UUIDs→slugs (reuse its mapping for building `SyncDay`s).
- The authenticated HTTP client lives in `AuthController` (Phase 15 exposed `uploadLocalData` the
  same way); the `SyncEngine` needs the same authenticated client — pass it the push/pull lambdas
  bound to a `RemoteDataSource`-style call, exactly as `ServerMigration` takes an upload lambda.

#### Decisions (closed)
- **D-16b.1 — Sync DTOs in `:core/dto/SyncDtos.kt`** (created here if not already by 16a):
  ```kotlin
  @Serializable data class SyncCycle(
      val id: String, val startDate: String, val endDate: String? = null,
      val updatedAt: String, val deleted: Boolean = false,
  )
  @Serializable data class SyncDay(
      val id: String, val date: String, val cycleId: String,
      val flow: String? = null, val collectionMethod: String? = null, val energy: String? = null,
      val emotions: List<String> = emptyList(), val sleep: List<String> = emptyList(),
      val discharge: List<String> = emptyList(), val skin: List<String> = emptyList(),
      val digestion: List<String> = emptyList(), val mind: List<String> = emptyList(),
      val sex: List<String> = emptyList(), val pain: List<ExportPain> = emptyList(),
      val notes: String? = null, val updatedAt: String, val deleted: Boolean = false,
  )
  @Serializable data class SyncPreferences(val categoryOrder: List<String>, val updatedAt: String)
  @Serializable data class SyncPushRequest(
      val cycles: List<SyncCycle> = emptyList(), val days: List<SyncDay> = emptyList(),
      val preferences: SyncPreferences? = null,
  )
  @Serializable data class SyncPullResponse(
      val cycles: List<SyncCycle> = emptyList(), val days: List<SyncDay> = emptyList(),
      val preferences: SyncPreferences? = null, val cursor: Long,
  )
  @Serializable data class SyncPushResponse(
      val cycles: List<SyncCycle> = emptyList(), val days: List<SyncDay> = emptyList(),
      val preferences: SyncPreferences? = null, val cursor: Long,
  )
  ```
  Reuse `ExportPain` (Phase 12). `SyncDay` differs from `ExportDay` by carrying `id`, `cycleId`
  (UUID, not `cycleStartDate`), `updatedAt`, `deleted` (D1).
- **D-16b.2 — Pure merge in `:core/service/SyncMerge.kt`** (created here if not by 16a):
  ```kotlin
  enum class MergeWinner { LOCAL, REMOTE }
  /** LWW: later updatedAt wins; tie → greater id (lexicographic). Symmetric on both sides. */
  fun mergeDecision(localUpdatedAt: String, localId: String,
                    remoteUpdatedAt: String, remoteId: String): MergeWinner
  ```
  Unit-tested; used by both the client reconciler and the server push (16a). No I/O, no clock.
- **D-16b.3 — Cycle-boundary rule in `:core/service/CycleRules.kt`** (extend the Phase-11 file):
  `fun reconcileOpenCycles(cycles: List<CycleBoundary>): List<CycleBoundary>` where a
  `CycleBoundary(id, startDate, endDate?)` list with >1 open (null end) collapses to: the
  latest-`startDate` cycle stays open; each earlier open cycle gets `endDate =
  autoCloseEndDate(nextStartDate)`. Pure; unit-tested; reused by server push and client apply.
- **D-16b.4 — Outbox-append on every local write (aggregate granularity).** Add a tiny
  `LocalOutbox.record(entityType, entityId, op, updatedAt)` helper (writes `sync_outbox`) and call
  it from each `LocalDataSource` write **in the same SQLDelight transaction** as the data write:
  cycle create/close/delete → `cycle`; anchor create, notes, sub-log/pain replace, day delete →
  `day` (the anchor id); prefs upsert → `preferences`. `op ∈ {upsert, delete}`. The store keeps
  current state; the outbox records intent. (D-13.7 reserved this; 16b activates it.)
- **D-16b.5 — `sync_state` cursor (SQLDelight).** New `SyncState.sq`: a single-row table
  `sync_state(id INTEGER PRIMARY KEY CHECK(id=0), last_pulled_seq INTEGER NOT NULL DEFAULT 0)`
  with `get`/`set`. Holds the pull cursor (`server_seq`). Reset to 0 on account deletion
  (`deleteAll`).
- **D-16b.6 — Seam grows by two delete methods.** Add to `HomeFlowDataSource`:
  `suspend fun deleteCycle(cycleId: String): ApiResult<Unit>` and
  `suspend fun deleteDay(date: String): ApiResult<Unit>`. `RemoteDataSource` → HTTP `DELETE
  cycles/{id}` / `daily-logs/{date}` (via `apiSendEmpty`). `LocalDataSource` → set `deleted_at`,
  record an outbox `delete`, and (cycle) cascade-tombstone its days. Update the interface KDoc
  contract. `HomeFlowRepository` gets matching pass-throughs.
- **D-16b.7 — `SyncEngine` (commonMain), driven by `syncNow()` here.**
  ```kotlin
  class SyncEngine(
      private val db: HomeFlowDb,
      private val localDataSource: LocalDataSource,
      private val refData: LocalRefData,
      private val push: suspend (SyncPushRequest) -> ApiResult<SyncPushResponse>,
      private val pull: suspend (since: Long) -> ApiResult<SyncPullResponse>,
  ) {
      val status: StateFlow<SyncStatus>
      suspend fun syncNow(): SyncResult   // push pending outbox → pull since cursor → merge → advance cursor
  }
  ```
  `syncNow()`: (1) build a `SyncPushRequest` from `selectPending` outbox rows (map each entity to
  its current aggregate, UUIDs→slugs via `refData`); `push`; on success `markSynced` those rows and
  adopt the authoritative post-merge state + advance the cursor. (2) `pull(lastPulledSeq)`; for each
  remote aggregate, load the local counterpart, decide with `mergeDecision`, and if remote wins
  apply it into `LocalDataSource` (slugs→local UUIDs; tombstone → local `deleted_at`); then
  `reconcileOpenCycles`; set `last_pulled_seq = response.cursor`. All best-effort; on network
  failure → `SyncStatus.Offline`/`Error`, leave the outbox intact (retry later). Applying a remote
  change must **not** re-enqueue it to the outbox (guard: apply via a path that skips
  `LocalOutbox.record`, e.g. an internal `applyRemote*` that writes rows + bumps `updated_at`
  without recording intent).
- **D-16b.8 — `SyncStatus`** = `sealed interface { Idle(lastSyncedAt: String?); Syncing; Offline;
  Error(message) }`. Exposed as `StateFlow`; surfaced in UI in 16c.
- **D-16b.9 — Mode-C composition (no triggers/UI yet).** Add a `SyncSessionController` **or** reuse
  Phase 15's controller with the engine attached: in Mode C the data source is `LocalDataSource`
  (Phase 13/14 path) **and** a `SyncEngine` is constructed against the authenticated server client
  (push/pull lambdas bound to a `RemoteDataSource` over the Phase-15 host). The adopt-a-server
  upload (Phase 15) becomes the **initial push** (first `syncNow()` ships the whole outbox). Mode C
  is a new `AppMode` value **or** a flag on SERVER mode — **decide in 16c when the UI lands**; in
  16b wire it behind tests only (do not add a chooser entry yet). **STOP and report** if attaching
  the engine forces a change to the `LocalSessionController`/`AuthController` public contracts.

#### File manifest (16b, exhaustive)
**Create — `:core`:** `dto/SyncDtos.kt` (D-16b.1, if not in 16a), `service/SyncMerge.kt` (D-16b.2,
if not in 16a); tests `service/SyncMergeTest.kt`, `service/CycleReconcileTest.kt`.
**Modify — `:core`:** `service/CycleRules.kt` (+`reconcileOpenCycles`, D-16b.3).
**Create — `:app:shared` commonMain:** `data/local/LocalOutbox.kt` (D-16b.4),
`sqldelight/.../SyncState.sq` (D-16b.5), `data/sync/SyncEngine.kt` + `data/sync/SyncStatus.kt`
(D-16b.7/8).
**Modify — `:app:shared` commonMain:** `data/HomeFlowDataSource.kt` (+2 delete methods, D-16b.6),
`data/RemoteDataSource.kt` (delete impls), `data/local/LocalDataSource.kt` (delete impls + outbox
on every write + `applyRemote*` paths), the `Local*Store` files (outbox-append in-transaction),
`data/HomeFlowRepository.kt` (delete pass-throughs).
**Create — tests (jvmTest):** `data/sync/SyncEngineTest.kt` (drive two in-memory
`LocalDataSource`s through a fake/in-process server or a `MockEngine`-backed push/pull and assert
convergence), `data/local/LocalDeleteTest.kt`.

#### Tests to add
- **`SyncMergeTest`** — later `updatedAt` wins; equal `updatedAt` → greater id wins; symmetric
  (swapping args flips the winner consistently); tombstone vs. edit by `updatedAt`.
- **`CycleReconcileTest`** — two open cycles collapse to one open (later start) + the earlier closed
  at `autoCloseEndDate(laterStart)`; a single open cycle is unchanged.
- **`SyncEngineTest`** — (a) push: an outbox edit is sent and marked synced; (b) pull: a remote
  aggregate not present locally is applied; (c) conflict: same-day edit on both sides → later
  `updatedAt` wins on both; (d) tombstone: a remote delete tombstones locally and is not
  re-enqueued; (e) slug boundary (D4): a `mood_swings` selection round-trips even when the two
  stores assign different option UUIDs; (f) resumable: interrupting between push and pull then
  re-running converges with no dupes.
- **`LocalDeleteTest`** — `deleteCycle` tombstones the cycle + cascades day tombstones + records
  outbox `delete`s; `deleteDay` tombstones the day; live reads exclude them.

#### Guardrails — do NOT
- Do NOT sync sub-logs individually or add per-sub-log change tracking — aggregate granularity only.
- Do NOT let applying a remote change re-enqueue it to the outbox (echo loop) — use `applyRemote*`.
- Do NOT compare timestamps for the pull cursor — the cursor is the server `server_seq` (D-16b.5).
- Do NOT add triggers, timers, connectivity listeners, a `SyncStatus` UI, or a Mode-C chooser entry
  (all 16c). 16b exposes `syncNow()` for tests only.
- Do NOT change `LocalDataSource`/`RemoteDataSource` existing method behavior beyond adding deletes
  + outbox recording; do NOT log sync bodies or health data.
- Keep `:core` pure — `SyncDtos`/`SyncMerge`/`CycleRules` import only kotlinx-serialization/datetime.

#### Done when (16b)
- [ ] `./gradlew :core:allTests` green incl. `SyncMergeTest`, `CycleReconcileTest`.
- [ ] `./gradlew :app:shared:check` green incl. `SyncEngineTest`, `LocalDeleteTest`; Android
  host-test compiles; `:app:androidApp:assembleDebug` + `:app:desktopApp` build.
- [ ] Outbox records exactly one aggregate row per local write; `applyRemote*` does not.
- [ ] Two in-memory stores driven by `syncNow()` converge for: new edits both ways, same-day
  conflict (LWW), tombstones (no resurrection), slug-keyed selections across differing UUIDs,
  and an interrupted-then-rerun sync (idempotent).
- [ ] `deleteCycle`/`deleteDay` exist on the seam and both data sources; local deletes tombstone +
  enqueue. (No `CHANGELOG.md` entry yet — 16c ships Mode C.)

#### Risks / stop-conditions (16b)
- **Echo loop** (applying a pulled change re-enqueues it, causing endless sync) — the `applyRemote*`
  path is the guard; if the store design can't cleanly bypass outbox recording, STOP and report.
- **Authenticated client reuse** — the engine needs the same bearer-attached client `AuthController`
  builds; bind push/pull to it as Phase 15 did for upload. STOP if it forces a public-contract
  change (D-16b.9).
- **Aggregate assembly cost** — building a `SyncDay` per pending outbox row re-reads the day; fine
  for this data volume; do not prematurely batch.

---

### Phase 16c — Triggers, status UI, delete UI, Mode-C activation + end-to-end convergence

**Goal:** turn the 16b reconciler into the shipped **Mode C** feature: automatic sync triggers,
a visible `SyncStatus`, the delete-cycle/delete-day UI, the Mode-C selection/activation, and the
end-to-end two-device convergence guarantees + docs + changelog.

#### Decisions (closed)
- **D-16c.1 — Triggers (all best-effort, all funnel to `SyncEngine.syncNow()`):** on app
  foreground (lifecycle), after each local write **debounced** (~2 s; coalesce a burst into one
  sync), on **connectivity regained** (platform `expect`/`actual` connectivity signal — Android
  `ConnectivityManager`, desktop a reachability poll), and a periodic timer (~15 min) while
  foregrounded. Failures retry on the next trigger; no trigger blocks the UI.
- **D-16c.2 — `SyncStatus` in the shell.** Surface `SyncEngine.status` in `AppShell` (a small top-bar
  indicator: Idle+lastSyncedAt / Syncing / Offline / Error) — visible only in Mode C.
- **D-16c.3 — Delete UI.** A "Delete cycle" affordance in the Cycles screen and "Delete day" in the
  Day screen, each behind a type-/tap-to-confirm dialog (mirror `PreferencesScreen`'s delete
  dialog), calling `repository.deleteCycle/deleteDay`. Available in all modes (the seam supports it
  everywhere); a delete in Mode C enqueues a tombstone that syncs.
- **D-16c.4 — Mode-C activation.** Decide the surfacing: either a third chooser path / a Settings
  toggle "Work offline (sync with my server)" on a Mode-B install, **or** make Mode C the default
  behavior of SERVER mode once a server is adopted (online-first becomes offline-first). Pick the
  least-surprising option for the single-user product and pin it here; reuse Phase 15's host +
  adopt-a-server upload as the initial push.
- **D-16c.5 — Encryption/security re-verification** (no new boundaries): wire stays plaintext over
  TLS; server at-rest ciphertext; local at-rest DEK; no key on the wire; no body logging;
  row-scoping holds.

#### File manifest (16c)
**Create:** `platform/Connectivity.kt` (+ android/jvm actuals), UI for delete dialogs + the
`SyncStatus` indicator, a `SyncController`/trigger wiring (foreground/debounce/timer).
**Modify:** `ui/shell/AppShell.kt` (status indicator + delete entry points), `ui/screens/CyclesScreen.kt`
+ `DayScreen.kt` (delete actions), `ui/AppRoot.kt` (Mode-C composition + engine lifecycle), the
entry points if a lifecycle hook is needed.
**Docs:** `ARCHITECTURE-server.md` + `ARCHITECTURE-client.md` (sync design), `threat-model.md`
(sync wire/at-rest threats), `API.md` (final `/sync` + delete docs), `CHANGELOG.md`: "Work
offline; your devices sync through your server when reconnected."

#### Tests to add
- Trigger unit tests (debounce coalesces; connectivity-regained fires `syncNow`) with a fake engine.
- Delete-UI Compose tests (confirm dialog → `deleteCycle/deleteDay` called).
- **Manual / E2E two-device** (document; needs a running server): edit on desktop → appears on
  Android after sync and vice-versa (deep compare); offline edits on different days reconcile with
  no loss; same-day offline conflict → later `updatedAt` wins on both; delete on A propagates to B
  with no resurrection; interrupting a sync and re-running converges.

#### Guardrails — do NOT
- Do NOT let any trigger block the UI thread or the composition; sync is background + best-effort.
- Do NOT change the 16a wire protocol or the 16b merge contract — 16c is wiring + UI only.
- Do NOT log sync bodies/health data; keep `FLAG_SECURE`; do NOT weaken row-scoping or encryption.

#### Done when (16c) — the Mode-C guarantees
- [ ] `./gradlew check` green across modules; new trigger/UI tests pass.
- [ ] **Two-device convergence (online):** edits both directions converge (deep DB compare).
- [ ] **Offline reconcile:** different-day offline edits on A and B converge with no loss.
- [ ] **Conflict:** same-day offline edits → later `updatedAt` wins deterministically on both; no
  dup rows, no partial merge.
- [ ] **Tombstone propagation:** delete on A → B reflects it after sync and does not resurrect it.
- [ ] **Cycle-boundary conflict** resolves via the shared `:core` rule, not raw LWW.
- [ ] **Idempotent/resumable:** interrupting a sync mid-flight and re-running converges; no dupes.
- [ ] **Slug boundary (D4):** a `mood_swings` selection round-trips despite differing option UUIDs.
- [ ] Security re-verified (D-16c.5); `SyncStatus` visible; delete UI works in every mode.
- [ ] Docs updated; `CHANGELOG.md`: "Work offline; your devices sync through your server when
  reconnected."

#### Risks / stop-conditions (16c)
- **Connectivity signal portability** — if a clean cross-platform connectivity `expect`/`actual` is
  awkward, fall back to "try `syncNow`, treat failure as Offline, retry on timer/foreground"
  rather than blocking on a perfect signal.
- **Clock skew** — only the LWW comparator uses `updatedAt`; the cursor is the server seq. If skew
  is observed corrupting same-second ties, the deterministic id tie-break already converges both
  sides identically.
- **Partial-aggregate writes** — always ship the **whole** day aggregate as one unit (16b builds it
  whole) so a half-applied day cannot occur.
- Scope creep toward CRDTs — explicitly out of scope; per-aggregate LWW + the two `:core` domain
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

- One feature branch per phase (and per **sub-phase** for 16), stacked, matching the existing
  `feature/phase-N-…` convention (`BRANCHING.md`) — e.g. `feature/phase-16a-server-sync`,
  `feature/phase-16b-client-reconciler`, `feature/phase-16c-mode-c`. Do not start a phase until
  the prior phase's **Done when** is fully green.
- Phases 11–13 and **16a/16b** are safe to land with **no user-visible change** (refactor +
  dormant/internal capability) — ship them first to de-risk. Phases 14/15 and **16c** each flip
  on a mode.
- Keep the server backward-compatible: 11/12 and **16a**'s server additions are **additive**
  (new optional `id`, new routes, new `deleted_at`/`sync_changes`, the `/sync` endpoints) so an
  older client keeps working during rollout.
```
