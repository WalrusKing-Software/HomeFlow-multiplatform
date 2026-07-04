# HomeFlow Multiplatform

A self-hosted period tracking application for logging menstrual cycle data,
symptoms, pain, and intimacy records — built **Kotlin-everywhere** as a
**desktop** and **Android** app talking to a self-hosted server you run on your own
hardware (e.g. a Raspberry Pi). Security is a first-class concern: the data is
highly sensitive, so every layer is hardened accordingly.

This is a ground-up, Kotlin Multiplatform rebuild of the original
SvelteKit + Node **HomeFlow** web app. It keeps that project's data model, API
contract, Keycloak auth, and threat model (those docs are copied here as the
authoritative spec), and reimplements the server and clients in Kotlin.

> **Single user.** The app is designed for exactly one user account.
> Self-registration is disabled in Keycloak.

---

## Download

[![Latest release](https://img.shields.io/github/v/release/ashay1341/HomeFlow-multiplatform?include_prereleases&label=latest%20release)](https://github.com/ashay1341/HomeFlow-multiplatform/releases/latest)

Ready-to-run builds for all three apps are attached to every
**[GitHub Release](https://github.com/ashay1341/HomeFlow-multiplatform/releases/latest)**.
Download the piece(s) you need — `<version>` is the release version:

| To run… | Download | Setup guide |
|---|---|---|
| **Desktop — Windows** | `HomeFlow-<version>.msi` | `__docs/SETUP-DESKTOP.md` |
| **Desktop — macOS** | `HomeFlow-<version>.dmg` | `__docs/SETUP-DESKTOP.md` |
| **Desktop — Linux** | `homeflow_<version>_amd64.deb` | `__docs/SETUP-DESKTOP.md` |
| **Android** | `homeflow-android-<version>.apk` | `__docs/SETUP-ANDROID.md` |
| **Self-hosted server** | `homeflow-server-<version>.zip` / `.tar.gz` | `__docs/SETUP-SERVER.md` |

> **Self-hosting the server with Docker.** The server is also published as a multi-arch
> (`amd64` + `arm64`) container image under this repository's **Packages** (GitHub
> Container Registry) — this is what a Raspberry Pi / homelab box pulls:
>
> ```bash
> docker pull ghcr.io/ashay1341/homeflow-multiplatform-server:<version>
> ```
>
> GitHub **Packages** hosts only this server image (it is a container registry, not a
> file host); the downloadable app **executables** all live on the **Releases** page
> above.

The desktop app also runs fully **local-only** with no server — see the setup guides
for choosing a mode.

---

## Architecture

```
[Desktop app]   [Android app]          Compose Multiplatform + Material 3
        \           /                   (:app:shared + :app:desktopApp / :app:androidApp)
         \         /
          ▼       ▼   OIDC + Authorization: Bearer JWT  (over Tailscale)
        [ Caddy ]  ── TLS edge ──►  [ Ktor server ]  ──►  [ PostgreSQL ]
                                          │
                                          └──►  [ Keycloak ]  (OIDC + TOTP 2FA)

         shared contract + domain math:  :core  (DTOs, cycle/analytics logic)
```

- **`:core`** — KMP module: API DTOs (kotlinx.serialization), cycle/analytics
  domain math, validation, error codes. Compiled into both server and clients.
- **`:server`** — Ktor (JVM) + Exposed + Flyway over PostgreSQL; validates Keycloak
  JWTs; AES-256-GCM column encryption.
- **`:app:shared` / `:app:androidApp` / `:app:desktopApp`** — Compose Multiplatform
  desktop + Android clients (shared UI in `:app:shared`, thin entry points in the apps).
- **Infra (unchanged from the web app):** PostgreSQL, Keycloak (OIDC + TOTP
  2FA), Caddy.

---

## Tech stack

| Layer | Technology |
|---|---|
| Shared | Kotlin Multiplatform, kotlinx.serialization, kotlinx.datetime |
| Clients | Compose Multiplatform, Material 3, Ktor client |
| Server | Ktor (Netty), Exposed, HikariCP, Flyway |
| Database | PostgreSQL 16 |
| Auth | Keycloak 26 — OIDC Authorization Code + PKCE (S256), TOTP 2FA |
| Reverse proxy | Caddy 2 (internal-CA TLS on LAN; real cert via Tailscale) |
| Build | Gradle (Kotlin DSL), version catalog |
| Containers | Docker Compose |

---

## Documentation

Start with **`CLAUDE.md`** (project entry point + non-negotiable rules), then:

| Doc | Covers |
|---|---|
| `__docs/SHARED-MODULE.md` | the `:core` boundary — what's shared vs server-only vs platform-specific |
| `__docs/ARCHITECTURE-server.md` | Ktor layering, Exposed, auth, encryption, errors |
| `__docs/ARCHITECTURE-client.md` | Compose Multiplatform structure, OIDC, token storage, `expect`/`actual` |
| `__docs/data-model.md` (+ sex addendum) | full schema |
| `__docs/API.md` | every route's contract |
| `__docs/KEYCLOAK.md` | realm, clients, audience mapper, TOTP 2FA, JWT validation |
| `__docs/threat-model.md` | threats + mitigations |
| `__docs/SETUP-SERVER.md` / `SETUP-DESKTOP.md` / `SETUP-ANDROID.md` | end-user install + server-connection guides |
| `__docs/DOCKER.md` / `__docs/DEPLOYMENT.md` | containers / Pi + Tailscale runbook |
| `__docs/TESTING.md` | test layers and what's covered |
| `__docs/BACKUP.md` / `__docs/BRANCHING.md` | backups / git model + CI gates |
| `__docs/RELEASE-PIPELINE.md` / `COMPATIBILITY.md` | release packaging / client–server compatibility |
| `__docs/project-planning/` | product overview + feature spec |
| `openapi.yaml` | the API contract (initial spec; may become a server-generated output) |

---

## Quick start (development)

> Detailed steps: `__docs/DEPLOYMENT.md` (deploy) and `__docs/DOCKER.md` (stack).

```bash
# Infra (postgres + keycloak + caddy)
cp .env.example .env        # then fill every secret (openssl rand -base64 32 / 24)
make dev                    # docker compose with the dev overlay

# Migrate the database (Flyway)
./gradlew :server:flywayMigrate

# Run the server
./gradlew :server:run

# Run the desktop client
./gradlew :app:desktopApp:run

# Build the Android client
./gradlew :app:androidApp:assembleDebug
```

---

## Status

Feature-complete and preparing the first release (**0.1.0**). The Kotlin
Multiplatform rebuild is implemented end to end: the shared `:core` contract, the
Ktor server (auth, cycles, daily logs, symptoms, analytics, preferences), and the
desktop + Android clients (read and write). Three deployment modes are supported:

- **Local-only** — run a client with no server; data is stored on-device in an
  encrypted local store.
- **Server-connected** — stand up the self-hosted server and point clients at it; the
  server is the source of truth (includes a one-time "adopt a server" migration that
  lifts existing local data up to the server).
- **Offline-capable + sync** — clients keep a first-class local store, work fully
  offline, and reconcile with the server when connectivity returns.

Releases are cut per component (server / desktop / android); see
`__docs/RELEASE-PIPELINE.md`, `COMPATIBILITY.md`, and `CHANGELOG.md`.

Licensed under **AGPL-3.0** (see `LICENSE`). Security disclosures: see `SECURITY.md`.

---

## Project layout

Kotlin Multiplatform, one repository, five Gradle modules:

| Module | Path | Role |
|---|---|---|
| `:core` | `core/` | shared DTOs, cycle/analytics domain math, validation, error codes |
| `:server` | `server/` | Ktor (JVM) backend — auth, persistence, encryption |
| `:app:shared` | `app/shared/` | shared Compose UI + repository + auth (`androidMain`/`jvmMain` actuals) |
| `:app:androidApp` | `app/androidApp/` | Android entry point |
| `:app:desktopApp` | `app/desktopApp/` | desktop entry point + packaging |

The module boundaries (what belongs in `:core` vs server-only vs platform-specific)
are described in `CLAUDE.md` and `__docs/SHARED-MODULE.md`.

## Build & test

```bash
# Run
./gradlew :server:run                       # Ktor server
./gradlew :app:desktopApp:run               # desktop client
./gradlew :app:desktopApp:hotRun --auto     # desktop client, hot reload
./gradlew :app:androidApp:assembleDebug     # Android APK (debug)

# Quality gate + tests
./gradlew check                             # ktlint + detekt + all tests
./gradlew :server:test                      # server (Testcontainers PostgreSQL)
./gradlew :app:shared:jvmTest               # shared, desktop host
./gradlew :app:shared:testAndroidHostTest   # shared, Android host
```

Contributions follow the branching model and CI gates in `__docs/BRANCHING.md`.