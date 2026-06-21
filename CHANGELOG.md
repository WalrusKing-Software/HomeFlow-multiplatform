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
