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




## Kotlin Multiplatform Readme Documentation
This is a Kotlin Multiplatform project targeting Android, Desktop (JVM), Server.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
  - Standard run: `./gradlew :app:desktopApp:run`
- Server: `./gradlew :server:run`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Desktop tests: `./gradlew :app:shared:jvmTest`
- Server tests: `./gradlew :server:test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…