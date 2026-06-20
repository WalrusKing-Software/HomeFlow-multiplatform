# Secure Period Tracking Web App

## Overview
This project is a secure period tracking web application designed to help users monitor their menstrual cycles while ensuring their data privacy and security. The app provides features such as cycle tracking, symptom logging, and personalized insights, all while adhering to strict security standards to protect user information. This project will eventually be hosted on homelab to be served on my local network. This app could also eventually be exposed to the internet when hosted on the homelab. 


## Features

### Daily Health Tracking
Users can log a comprehensive set of health data each day across the full menstrual cycle (~28 days). Each data category is presented as its own row, with customizable ordering so users can arrange their dashboard to match their priorities. Tracked categories include:

- **Emotions** — multi-select from states such as fine, mood swings, anxious, irritable, sad/depressed, and more.
- **Sleep Quality** — multi-select covering trouble falling asleep, restless sleep, waking up tired, or feeling rested.
- **Energy** — single-select scale from exhausted through fully energized.
- **Sex & Sex Life** — multi-select covering protection, drive level, and other indicators.
- **Discharge** — multi-select for consistency and color characteristics.
- **Skin & Face** — multi-select for conditions such as acne, dryness, oiliness, or inflammation.
- **Digestion** — multi-select for symptoms including bloating, nausea, heartburn, and more.

### Menstruation-Phase Tracking
When a user is actively logging a period, additional tracking categories become available:

- **Blood Flow** — single-select from light, medium, heavy, or super heavy.
- **Collection Method** — single-select for tampon, pad, or panty liner.
- **Mind** — multi-select covering cognitive and emotional states such as focus, motivation, brain fog, and stress.
- **Pain** — multi-select with an optional 1–10 severity rating and detailed anatomical location selection. Location is organized into collapsible groups (head & neck, back, abdomen, legs, vagina) each with specific sub-locations.

### Pain Tracking
Users can record pain with both a numeric severity rating (1–10) and a precise body location. Location selection is available via two interfaces: an interactive front-and-back body diagram with selectable regions, and collapsible dropdown menus organized by body area (head, arms, torso, legs, etc.) that expand into granular multi-select options.

### Analytics: Ovulation Prediction
A calendar view that highlights predicted period days (red) and predicted ovulation days (blue) based on the user's logged cycle history, helping them identify their fertile window for family planning.

### Analytics: Cycle Statistics
A set of at-a-glance statistics derived from logged period data:

- **Average Cycle Length** — a single number representing the user's typical cycle length.
- **Cycle Variation** — the average variation in cycle length over the past 6 months, giving insight into cycle regularity.
- **Average Period Length** — the mean number of bleeding days per cycle, alongside a chart plotting bleeding duration across individual cycles so the user can spot trends over time.

### Sleep Predictions
Based on the user's historical sleep quality logs, the app predicts how the user is likely to sleep during each phase of their current cycle: menstruation, follicular, ovulation, and luteal.

Feature specifics can be found in the [Features Document](./features.md).


## Tech Stack

> This is the Kotlin Multiplatform rebuild. The original web app's stack
> (Node/Fastify + SvelteKit) has been replaced; see `README.md` /
> `ARCHITECTURE-server.md` / `ARCHITECTURE-client.md` for the authoritative stack.

- **Shared** — Kotlin Multiplatform (`:core`): kotlinx.serialization DTOs,
  cycle/analytics domain math, validation, error codes
- **Server** — Kotlin, Ktor (JVM), Exposed, HikariCP, Flyway, PostgreSQL, Keycloak
- **Clients** — Compose Multiplatform + Material 3 (desktop JVM + Android), Ktor client
- **Database** — PostgreSQL
- **Auth** — Keycloak (OIDC Authorization Code + PKCE for users; WebAuthn passkey 2FA)
- **Deployment** — Docker (self-hosted; Raspberry Pi over Tailscale)


## Security Considerations

### Threat Model:
The threat model can be found in the [Threat Model Document](./threat-model.md). 


## Technical Notes

### Code Quality Tooling
Pre-commit and pre-push hooks are enforced via Husky to ensure code quality gates run before any code reaches the repository. Hooks cover linting, formatting, and TypeScript type checks. A pre-commit hook also scans for accidentally committed secrets (e.g. `.env` files, API keys). This should be one of the first things implemented — it protects against the most likely class of accidental data exposure in a solo project.

### Git Branching Strategy
The repository follows a structured branching model designed to produce clean, readable release notes and protect stable code.

- `main` is the protected production branch. It always reflects the latest stable release and only accepts pull requests from versioned release branches.
- Release branches are named `version-x.x.x` and are protected. They only accept pull requests from `feature/*` or `bugfix/*` branches.
- Feature branches are named `feature/short-description` and should encapsulate a single feature or a closely related group of changes. This keeps pull requests focused and makes auto-generated GitLab release notes meaningful.
- Bugfix branches are named `bugfix/short-description`.
- Both feature and bugfix branches must be created from their corresponding version branch (e.g. work targeting version 1.1.0 branches from `version-1.1.0`).

### Changelog Format
Changelog entries answer one question: *what can I do now that I couldn't before?* Implementation details — which files changed, which functions were refactored — belong in commit messages and pull request descriptions, not the changelog. Each entry should be a plain-language sentence describing user- or developer-visible behaviour that changed.

### Docker Architecture
The Ktor backend, Keycloak, and PostgreSQL each run in their own Docker container (the desktop and Android clients are installed apps, not served containers). This keeps concerns isolated, makes local development reproducible, and allows the same configuration used locally to be promoted directly to the homelab deployment. All containers except the reverse proxy (Caddy) run on a private Docker network with no published ports — the reverse proxy is the only publicly reachable entry point. See `DOCKER.md`.

More details on the technical notes can be found at the bottom of the [Features Document](./features.md).


## Data Model
The data model can be found in the [Data Model Document](./data-model.md).