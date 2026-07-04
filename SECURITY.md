# Security Policy

HomeFlow is a self-hosted application that stores highly sensitive menstrual and
sexual-health data for a single user. Security is a first-class concern, not an
afterthought. If you find a vulnerability, please report it responsibly.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security problems.**

Report privately through one of these channels:

1. **GitHub private vulnerability reporting** (preferred) — go to the repository's
   **Security** tab → **Report a vulnerability**. This opens a private advisory
   visible only to the maintainer.
2. **Email** — `shaydrew3334@gmail.com` as a fallback if you cannot use GitHub.

Please include:

- a description of the issue and its impact,
- the affected component (`:server`, a client, Keycloak/Caddy config, deployment),
- steps to reproduce or a proof of concept,
- any suggested remediation.

You can expect an acknowledgement within a few days. Because this is a
single-maintainer hobby project, fixes are made on a best-effort basis; please
allow a reasonable window before any public disclosure.

## Scope

In scope:

- The Ktor server (`:server`) — auth/JWT validation, row scoping, application-layer
  encryption, error handling.
- The desktop and Android clients — token storage, at-rest encryption of the local
  store, screenshot/recents protection.
- The shared `:core` contract and domain logic.
- The reference deployment: Docker Compose, Caddy TLS edge, Keycloak realm config,
  PostgreSQL init.

Out of scope:

- Vulnerabilities in third-party dependencies (PostgreSQL, Keycloak, Caddy, the JVM,
  Kotlin/Compose libraries) — report those upstream. We will still update to patched
  versions when advisories affect us.
- Issues that require an already-compromised host, physical device access, or a
  misconfiguration explicitly warned against in the deployment docs.
- Denial of service against a server the reporter does not own.

## Security model

The design and the threats it defends against are documented in
[`__docs/threat-model.md`](__docs/threat-model.md). Key invariants:

- **Row scoping** — every server read/write of health data is scoped to the `userId`
  taken from the validated JWT, never from the request body.
- **Encryption at rest** — `daily_logs.notes` and `daily_log_sex.encrypted_payload`
  are AES-256-GCM encrypted server-side; clients never hold the key. Local client
  stores (local-only / offline modes) use whole-DB SQLCipher encryption keyed from
  the user's passphrase.
- **Auth** — Keycloak OIDC Authorization Code + PKCE (S256), password + TOTP 2FA,
  RS256-only JWT validation checking `iss`, `aud`, and `exp`.
- **No sensitive data in logs** — request/response bodies, health data, tokens, and
  plaintext user IDs are never logged.

## Supported versions

This project is pre-1.0 and released per component (server / desktop / android).
Only the latest released version of each component receives security fixes. See
[`COMPATIBILITY.md`](COMPATIBILITY.md) for the client–server compatibility matrix.
