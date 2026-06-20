# Keycloak Configuration — Secure Period Tracking Web App

## Overview

Keycloak handles all identity and authentication for this app. The backend never stores passwords or manages sessions directly — it validates JWTs issued by Keycloak on every request.

This document covers:
- Realm settings
- Client configuration (three clients: frontend public + backend confidential + android public/native)
- WebAuthn passwordless authentication setup
- The audience mapper (critical — JWT validation will fail without this)
- Token lifetimes
- Brute-force protection
- How the realm export is generated and imported
- Manual setup steps that cannot be captured in the realm export

---

## Clients and Their Purposes

> **Multiplatform note (this repo).** This is the Kotlin rebuild — there is **no
> web frontend**. The actual realm (`infra/keycloak/realm-export.json`, imported on
> first Keycloak boot) defines **three** clients: `homeflow-backend` (confidential),
> `homeflow-android` (public, native), and **`homeflow-desktop`** (public, loopback
> redirect) — the desktop client replaces the old `homeflow-frontend` web client.
> Both `homeflow-android` and `homeflow-desktop` are PKCE-S256 public clients that
> request `offline_access` for long-lived sessions, and each carries its own
> audience mapper for `homeflow-backend`. Where the prose below says
> "Fastify backend" or `homeflow-frontend`, read "Ktor backend" / `homeflow-desktop`.
> The realm-export.json is the source of truth.
>
> The realm export intentionally uses Keycloak's **default browser flow** for now;
> the custom password + WebAuthn-passkey 2FA flow (`browser-with-passkey`) and the
> `webauthn-register` default required action described below are bound in the auth
> phase (IMPLEMENTATION-PHASES Phase 3), not at infra bring-up.

This app uses **three separate Keycloak clients**. This is a common source of confusion — do not consolidate them into one.

| Client ID | Type | Used by | Purpose |
|---|---|---|---|
| `homeflow-backend` | Confidential (has secret) | Ktor backend | JWT audience validation + Keycloak Admin API calls (account deletion) |
| `homeflow-android` | Public (no secret) | Native Android app | OIDC Authorization Code + PKCE flow in a Chrome Custom Tab; uses `offline_access` for long-lived mobile sessions |
| `homeflow-desktop` | Public (no secret) | Desktop app | OIDC Authorization Code + PKCE flow via system browser + loopback redirect; uses `offline_access` for long-lived desktop sessions |

**Why separate clients?**

The two front-end clients (`homeflow-frontend`, `homeflow-android`) are public because each initiates a browser/Custom-Tab redirect — they cannot hold a secret safely since the redirect is visible to the user agent. They are kept separate so the native app can use a **custom-scheme redirect URI** and **`offline_access`** (longer-lived offline refresh token) without changing the web client's redirect URIs or session policy. The backend client is confidential because it runs server-side and needs to call the Keycloak Admin API to delete user accounts. Mixing these into one client would either expose a secret to the user agent or force one set of redirect/session rules onto both surfaces.

> All three clients share the **same realm, same user, and same backend audience** — so the Android app and the web app are interchangeable clients of the same `/api/v1`, and the audience mapper requirement (below) applies to both public clients.

---

## Realm Settings

**Realm name:** `homeflow`

### Registration & Login
| Setting | Value | Reason |
|---|---|---|
| User registration | **Disabled** | Single-user app — no self-signup |
| Forgot password | Disabled | Admin manages account recovery |
| Remember me | Disabled | Session lifetime handles persistence |
| Login with email | Enabled | Convenience |
| Duplicate emails | Not allowed | Single user, no conflict expected |

### Session & Token Lifetimes
| Setting | Value | Reason |
|---|---|---|
| Access token lifespan | **15 minutes** | Threat model TS-4 — short-lived tokens limit exposure window |
| SSO session idle | **8 hours** | Workday session — user stays logged in while active (web) |
| SSO session max | **8 hours** | Hard cap regardless of activity (web) |
| Refresh token lifespan | **8 hours** | Matches SSO session idle (web online refresh token) |
| Offline session idle | **30 days** | Mobile `offline_access` — the app stays logged in across days behind the biometric gate |
| Offline session max | **Off (unlimited)** *(or a deliberate cap, e.g. 90 days)* | Mobile session ceiling; the access token is still 15 min |

> **How this works in practice (web):** The access token expires after 15 minutes. `hooks.server.ts` detects the expiry and silently exchanges the refresh token for a new access token — the user never sees a login prompt unless they've been inactive for 8 hours or closed their browser.
>
> **How this works in practice (mobile):** The native app requests the `offline_access` scope, so Keycloak issues an **offline refresh token** governed by the *Offline* session settings (above), independent of the 8-hour web SSO session. The app keeps that token in Keystore-backed storage and, after a biometric unlock on each launch, silently exchanges it for a fresh 15-minute access token. A full Keycloak re-login is only needed when the offline session expires or the session is revoked. The 15-minute access-token TTL is unchanged for mobile — the device never holds a long-lived *access* token.

> ⚠️ **Enabling offline sessions is a deliberate change.** The original web-only config kept *Offline session idle* disabled ("no offline access needed"). Adding the Android client flips this on realm-wide. This is acceptable because the offline token lives only in the device Keystore and the app gates its use behind biometrics; revoking the session in Keycloak still forces re-login on the next refresh.

### Brute Force Protection
| Setting | Value |
|---|---|
| Enabled | **Yes** |
| Max login failures | 5 |
| Wait increment | 30 seconds |
| Max wait | 15 minutes |
| Failure reset time | 12 hours |

---

## Client: `homeflow-frontend` (Public)

This client handles the browser-facing OIDC Authorization Code flow with PKCE.

### Settings
| Setting | Value |
|---|---|
| Client ID | `homeflow-frontend` |
| Client authentication | **Off** (public client — no secret) |
| Authorization Code Flow | **Enabled** |
| Direct Access Grants | **Disabled** |
| Implicit Flow | **Disabled** |
| Service Accounts | **Disabled** |

### PKCE
| Setting | Value |
|---|---|
| Proof Key for Code Exchange | **S256** (required) |

PKCE is mandatory for public clients. It prevents authorization code interception attacks. The frontend generates a random `code_verifier`, hashes it to a `code_challenge`, and sends the challenge with the authorization request. Keycloak verifies the original verifier on token exchange. Claude Code must implement this in `lib/server/auth.ts`.

### Redirect URIs

Add both local dev and production URIs during setup. Update the production URI once the homelab hostname is decided.

| Environment | Valid Redirect URI |
|---|---|
| Local dev | `https://localhost/auth/callback` |
| Production | `https://<APP_HOSTNAME>/auth/callback` |

**Post-logout redirect URIs:**
- `https://localhost/login`
- `https://<APP_HOSTNAME>/login`

> Use exact URIs. Wildcard redirect URIs (`*`) must never be used — they allow open redirect attacks.

### Web Origins (CORS)
```
https://localhost
https://<APP_HOSTNAME>
```

---

## Client: `homeflow-backend` (Confidential)

This client is used by the Fastify backend for two purposes:
1. JWT signature verification — the backend fetches the JWKS from Keycloak using this client's realm context
2. Keycloak Admin API calls — specifically `DELETE /admin/realms/homeflow/users/{id}` for account deletion

### Settings
| Setting | Value |
|---|---|
| Client ID | `homeflow-backend` |
| Client authentication | **On** (confidential — has a secret) |
| Authorization Code Flow | **Disabled** |
| Direct Access Grants | **Disabled** |
| Service Accounts | **Enabled** (required for Admin API calls) |
| Implicit Flow | **Disabled** |

### Client Secret
Generated by Keycloak. Copy this value into the `KEYCLOAK_CLIENT_SECRET` environment variable. Treat it like a password — never commit it to git.

To regenerate: Keycloak Admin Console → Clients → `homeflow-backend` → Credentials → Regenerate.

### Service Account Roles
The backend's service account needs permission to delete users via the Admin API.

In the Keycloak Admin Console:
1. Go to Clients → `homeflow-backend` → Service Account Roles
2. In the "Client Roles" dropdown, select `realm-management`
3. Assign the role: `manage-users`

> `manage-users` is the minimum role required for the `DELETE /users/{id}` endpoint. Do not assign `realm-admin` — that grants far more than is needed.

---

## Client: `homeflow-android` (Public, Native)

This client handles the native Android app's OIDC Authorization Code + PKCE flow.
The app launches a **Chrome Custom Tab** for the one-time login (so the existing
password + passkey 2FA flow is reused unchanged), exchanges the code for tokens,
and then stays logged in via an **offline refresh token** behind a biometric gate.
The app code that drives this lives in `android/app/src/main/java/org/homeflow/mobile/auth/`.

### Settings
| Setting | Value |
|---|---|
| Client ID | `homeflow-android` |
| Client authentication | **Off** (public client — no secret) |
| Authorization Code Flow (Standard Flow) | **Enabled** |
| Direct Access Grants | **Disabled** |
| Implicit Flow | **Disabled** |
| Service Accounts | **Disabled** |

### PKCE
| Setting | Value |
|---|---|
| Proof Key for Code Exchange | **S256** (required) |

PKCE is mandatory for public native clients — it is the only thing binding the
authorization code to the app that requested it, since a custom-scheme redirect
can in principle be claimed by another app. AppAuth generates and verifies the
`S256` challenge automatically; do not disable it.

### Redirect URI

A native app uses a **custom scheme**, not an `https://` URL. It must be exact —
no wildcards.

| Setting | Value |
|---|---|
| Valid Redirect URI | `org.homeflow.mobile:/oauth2redirect` |
| Valid Post-Logout Redirect URI | `org.homeflow.mobile:/oauth2redirect` |

This must match, character-for-character:
- `AuthConfig.REDIRECT_URI` in the app, and
- the `appAuthRedirectScheme` manifest placeholder (`org.homeflow.mobile`) in
  `android/app/build.gradle.kts`.

> Use the exact URI. Wildcard redirect URIs (`*`) must never be used — for a
> native client they would let a malicious app intercept the authorization code.
> (Optionally, an Android App Link `https://<APP_HOSTNAME>/auth/android` can be
> registered later for stronger binding; the custom scheme is sufficient for v1.)

### Web Origins (CORS)
Leave **empty**. A native client makes no browser CORS requests; the token
exchange is a direct HTTPS POST from the app to Keycloak.

### Scopes — `offline_access`
| Setting | Value |
|---|---|
| `offline_access` | **Default** (or at minimum *Optional* and requested by the app) |

`offline_access` is a built-in Keycloak client scope. The app requests it
(`AuthConfig.SCOPES = ["openid", "offline_access"]`) so Keycloak issues an
**offline refresh token** whose lifetime follows the *Offline* session settings
(see *Session & Token Lifetimes*), not the 8-hour web SSO session. This is what
lets the app stay logged in across days. Verify the scope is assigned under
Clients → `homeflow-android` → Client Scopes.

### Audience mapper
**Required — same as the web client.** Without it, every backend call from the
app 401s. See the *Audience Mapper* section below; the app's tokens must carry
`aud: homeflow-backend` exactly like the web client's do.

### WebAuthn (passkey) in the Custom Tab
The 2FA passkey prompt appears inside the login Custom Tab. For it to work, the
**WebAuthn Policy Relying Party ID must equal the canonical hostname** the app
connects to (the Tailscale `*.ts.net` name once Phase 0b lands — see
`DEPLOYMENT.md`). A passkey registered against `homeflow.lan` will not work when
the app connects via the `ts.net` name; pick one canonical hostname for the realm
and register the passkey against it. This is the same RP-ID constraint the web
client already has — there is no separate mobile passkey configuration.

---

## Audience Mapper (Critical — Do Not Skip)

**This is the most commonly misconfigured part of a Keycloak + custom backend setup. Without this, every single JWT validation will fail with a 401, and the error message will not make it obvious why.**

By default, Keycloak does not include the backend client ID in the JWT `aud` (audience) claim. The Fastify auth plugin validates the `aud` claim on every request (as specified in `ARCHITECTURE.md`). If `homeflow-backend` is not in the `aud` claim, validation fails.

### What to configure

On the **`homeflow-frontend`** client (the one that issues tokens to the browser):

1. Go to Clients → `homeflow-frontend` → Client Scopes
2. Click `homeflow-frontend-dedicated`
3. Add mapper → By configuration → Audience
4. Configure:

| Field | Value |
|---|---|
| Name | `backend-audience` |
| Included Client Audience | `homeflow-backend` |
| Add to ID token | Off |
| Add to access token | **On** |

This adds `homeflow-backend` to the `aud` array in every access token issued to the frontend. The backend then validates `aud.includes('homeflow-backend')`.

**Repeat the identical mapper on the `homeflow-android` client** (Clients →
`homeflow-android` → Client Scopes → `homeflow-android-dedicated` → Add mapper →
Audience → Included Client Audience `homeflow-backend`, Add to access token **On**).
The audience mapper is **per-client**, so the web mapper does not cover the app —
without its own mapper, every request from the Android app 401s with the same
non-obvious symptom. (Alternatively, define a single shared *Audience* client
scope and assign it to both public clients, so there is one mapper to maintain.)

### Verifying the mapper is working

After configuration, decode a token (paste it into [jwt.io](https://jwt.io)) and confirm the `aud` field contains `homeflow-backend`:

```json
{
  "aud": ["homeflow-backend", "account"],
  "iss": "https://<APP_HOSTNAME>/realms/homeflow",
  "sub": "...",
  "exp": ...
}
```

If `aud` only contains `account`, the mapper is not configured correctly.

---

## Authentication Flow — Password + Passkey (2FA)

This app uses **two factors**:
1. **Username + password** (first factor)
2. **WebAuthn passkey** via Bitwarden (second factor)

On the very first login, a required action fires to register the passkey. After that, every login requires both factors.

**Why WebAuthn over TOTP as the second factor:**
- Phishing-resistant — credentials are cryptographically bound to the origin (`https://<APP_HOSTNAME>`), so a fake login page cannot steal them
- TOTP codes can be phished; passkeys cannot
- Bitwarden supports passkey storage on premium accounts via its browser extension

> **Note:** This is not the same as WebAuthn passwordless. The `webauthn-authenticator` (2FA) and `webauthn-authenticator-passwordless` are separate authenticators in Keycloak and store credentials separately. The realm export uses the regular WebAuthn 2FA flow (`browser-with-passkey`). The passwordless flow (`browser-passwordless`) is kept in the export but not bound.

### WebAuthn Policy (2FA — not passwordless)

In the Keycloak Admin Console:
1. Go to Authentication → Policies → **WebAuthn Policy** (not "WebAuthn Passwordless Policy")
2. Configure:

| Setting | Value |
|---|---|
| Relying Party Name | `Period Tracker` |
| Relying Party ID | `<APP_HOSTNAME>` (e.g. `localhost` in dev, `homeflow.lan` in production) |
| Signature Algorithms | `ES256` |
| Attestation Conveyance | `none` |
| Authenticator Attachment | `cross-platform` (allows Bitwarden browser extension) |
| Require Resident Key | `No` (2FA doesn't require a discoverable credential) |
| User Verification | `preferred` |

> **Relying Party ID must exactly match the hostname** the app is served from. This is enforced by the browser — a mismatch will silently prevent the passkey from working.

### Authentication Flow — `browser-with-passkey`

This flow is pre-configured in `realm-export.json` and imported automatically. The structure is:

```
browser-with-passkey (bound as Browser Flow)
├── Cookie (Alternative)
├── Kerberos (Disabled)
├── Identity Provider Redirector (Alternative)
└── browser-with-passkey forms (Alternative sub-flow)
    ├── Username Password Form (Required)
    └── browser-with-passkey WebAuthn (Conditional sub-flow)
        ├── Condition - User Configured (Required)
        └── WebAuthn Authenticator (Required)
```

The conditional sub-flow means: if the user has a WebAuthn credential registered, challenge them for it. If not (first login), skip it — and the `webauthn-register` required action will fire to prompt registration.

### Required Actions

The `webauthn-register` required action is set as the default for all new users (configured in the realm export). On first login, after entering their password, the user is immediately prompted to register a passkey via Bitwarden.

| Action | Setting |
|---|---|
| Verify Email | Disabled (no email server configured) |
| Update Password | Disabled (set via admin Credentials tab) |
| Webauthn Register | **Enabled as default action** |

### Register the Passkey (first login)

No manual pre-registration is needed — the required action handles it. On first login:
1. User enters username + password
2. Keycloak fires the `webauthn-register` required action
3. Browser prompts to save a passkey — Bitwarden will offer to store it
4. After saving, the login completes

On all subsequent logins, after entering username + password, Keycloak will challenge them with the WebAuthn prompt.

> **Important:** Register the passkey from the same browser/device that Bitwarden is installed on. The passkey is tied to the origin — if the hostname changes later, the passkey must be re-registered.

---

## Required Actions

These are already configured in the realm export. Verify them after any import:

| Action | Setting |
|---|---|
| Verify Email | Disabled (no email server configured) |
| Update Password | Disabled (set via admin Credentials tab instead) |
| Webauthn Register | **Enabled as default action** — fires on first login |
| Webauthn Register Passwordless | Disabled |

The `Webauthn Register` required action fires automatically for any new user on their first login, prompting them to register a passkey before the session completes.

---

## User Account Setup

This app has exactly one user account (plus the admin account). The user account is created by the admin — self-registration is disabled.

### Creating the user account

1. Log into Keycloak admin console: `http://localhost:8180/auth/admin`
2. Select realm: `homeflow`
3. Users → Add User
4. Set:
   - Username: (your choice)
   - Email: (your email — used for display only, no email server configured)
   - Email Verified: On
   - Enabled: On
5. Save
6. Go to the **Credentials** tab → **Set Password** → enter a strong password → toggle "Temporary" **off**
7. The `Webauthn Register` required action is already set as a realm default, so it will fire automatically on the user's first login
8. On first login: user enters username + password → Keycloak prompts to register a passkey → done

> After first login the user will need the passkey on every subsequent login (factor 2).

---

## Realm Export and Import

The realm configuration is exported as `keycloak/realm-export.json` and imported automatically on first container start via the `--import-realm` flag in `docker-compose.yml`.

### What the export includes
- Realm settings (session lifetimes incl. offline session, brute-force protection, registration settings)
- All three client definitions (frontend + backend + android) with their scopes and mappers
- The audience mappers (on both public clients, or the shared audience scope)
- The `browser-with-passkey` authentication flow (password + WebAuthn 2FA)
- WebAuthn policy settings (RP name, RP ID, attachment, verification)
- Required actions (`webauthn-register` as default)

> After adding the `homeflow-android` client (and enabling offline sessions),
> re-run the export below so `realm-export.json` stays the source of truth for a
> fresh import. Confirm the new client, its dedicated audience mapper, and the
> `offline_access` scope assignment all appear in the regenerated file.

### What the export does NOT include
- User accounts (exported separately or created manually — see above)
- Client secrets (these are environment-specific — regenerate after import)
- Passkey registrations (tied to the device — must be re-registered)

### Generating the export

```bash
# Export realm config (excludes users and secrets by design)
docker compose exec keycloak /opt/keycloak/bin/kc.sh export \
  --dir /tmp/export \
  --realm homeflow \
  --users skip

# Copy out of container
docker compose cp keycloak:/tmp/export/homeflow-realm.json \
  ./infra/keycloak/realm-export.json
```

### After importing to a new environment

1. Regenerate the `homeflow-backend` client secret
2. Update `KEYCLOAK_CLIENT_SECRET` in `.env`
3. Assign `manage-users` role to the backend service account (verify this survived the import)
4. Create the user account
5. Have the user register their passkey

---

## JWT Validation — What the Backend Checks

The Fastify `auth` plugin (`plugins/auth.ts`) validates the following claims on every request. All must pass or the request is rejected with `401 UNAUTHORIZED`.

| Claim | Expected value | Where it comes from |
|---|---|---|
| `iss` (issuer) | `https://<APP_HOSTNAME>/realms/homeflow` | `KEYCLOAK_INTERNAL_URL` + realm name in `config/keycloak.ts` |
| `aud` (audience) | Must contain `homeflow-backend` | Audience mapper configured above |
| `exp` (expiry) | Must not be expired (15 min TTL + 30s clock tolerance) | Token lifetime setting |
| `alg` (algorithm) | Must be `RS256` | Pinned in `keycloakConfig.algorithms` |
| `sub` (subject) | Must be a valid Keycloak user UUID | Used to look up or upsert the `users` row |

**JWKS endpoint** (used to verify token signatures):
```
http://keycloak:8080/realms/homeflow/protocol/openid-connect/certs
```

This is the internal container-to-container URL. The backend fetches this on startup and caches the public keys. Key rotation is handled automatically — the plugin refreshes the JWKS when it encounters a `kid` (key ID) it doesn't recognize.

**Why `RS256` only:** Pinning to RS256 prevents algorithm confusion attacks where an attacker substitutes `HS256` and signs a token with the server's public key as the HMAC secret. Keycloak uses RS256 by default; this just makes the check explicit and enforced.

---

## OIDC Flow Summary (Frontend → Keycloak → Backend)

```
1. User visits /dashboard
   → hooks.server.ts: no valid session cookie
   → redirect to /login

2. /login page
   → server generates: code_verifier (random), code_challenge = SHA256(code_verifier)
   → stores code_verifier in a short-lived httpOnly cookie
   → redirects browser to:
     https://<APP_HOSTNAME>/realms/homeflow/protocol/openid-connect/auth
       ?client_id=homeflow-frontend
       &redirect_uri=https://<APP_HOSTNAME>/auth/callback
       &response_type=code
       &scope=openid
       &code_challenge=<hash>
       &code_challenge_method=S256
       &state=<random CSRF token>

3. Keycloak shows WebAuthn prompt
   → user authenticates with passkey

4. Keycloak redirects to /auth/callback?code=<auth_code>&state=<state>

5. /auth/callback/+page.server.ts (server-side only)
   → validates state matches stored value (CSRF check)
   → retrieves code_verifier from cookie
   → POSTs to Keycloak token endpoint:
     POST http://keycloak:8080/realms/homeflow/protocol/openid-connect/token
       client_id=homeflow-frontend
       grant_type=authorization_code
       code=<auth_code>
       redirect_uri=https://<APP_HOSTNAME>/auth/callback
       code_verifier=<original verifier>
   → receives: access_token (15 min), refresh_token (8 hr), id_token
   → sets httpOnly, Secure, SameSite=Strict cookies:
       access_token=<jwt>
       refresh_token=<jwt>
   → calls GET /api/v1/users/me to confirm backend user record exists
   → redirects to /dashboard

6. Subsequent requests
   → hooks.server.ts reads access_token cookie
   → validates JWT claims (iss, aud, exp, alg)
   → if expired: exchanges refresh_token for new access_token silently
   → if refresh fails: clears cookies, redirects to /login
   → attaches access_token to Authorization: Bearer header for backend calls

7. Backend (every request)
   → auth plugin validates JWT
   → looks up user by keycloak_sub
   → attaches req.user = { id, keycloakSub }
```

### Android (native) variant

The native app runs the same flow with three differences:

- **No cookies.** Tokens are not stored as cookies; the offline refresh token is
  persisted in Keystore-backed `EncryptedSharedPreferences` and the access token
  is held in memory only. There is no `oauth_state`/`code_verifier` cookie — AppAuth
  keeps the PKCE verifier and state in the pending-intent it owns.
- **Custom-scheme redirect.** Step 4 redirects to
  `org.homeflow.mobile:/oauth2redirect?code=…` which Android routes back into the
  app (AppAuth's `RedirectUriReceiverActivity`) instead of to a web route.
- **`scope=openid offline_access`** (step 2) so Keycloak returns an offline refresh
  token. On relaunch the app requires a biometric unlock, then silently refreshes
  (steps 6–7 identical from the backend's perspective — it just sees a valid
  `Authorization: Bearer`).

The token exchange (step 5) and all backend validation (step 7) are byte-for-byte
the same; the backend cannot tell a web request from an app request beyond the
`azp`/client in the token.

---

## Cookie Specification

| Cookie | Value | Flags |
|---|---|---|
| `access_token` | Keycloak access JWT | `httpOnly`, `Secure`, `SameSite=Strict`, `Path=/` |
| `refresh_token` | Keycloak refresh JWT | `httpOnly`, `Secure`, `SameSite=Strict`, `Path=/` |
| `oauth_state` | CSRF state token | `httpOnly`, `Secure`, `SameSite=Lax`, `Path=/auth/callback`, `Max-Age=600` |
| `code_verifier` | PKCE verifier | `httpOnly`, `Secure`, `SameSite=Lax`, `Path=/auth/callback`, `Max-Age=600` |

The `oauth_state` and `code_verifier` cookies are short-lived (10 minutes) and scoped to `/auth/callback` only — they are only needed during the authorization flow and should not be accessible to other routes.

---

## Troubleshooting

### All requests return 401 after login appears to succeed
Most likely cause: audience mapper is missing or misconfigured. Decode the access token at [jwt.io](https://jwt.io) and check the `aud` claim. If `homeflow-backend` is not present, the mapper is not configured correctly. See the Audience Mapper section above.

### WebAuthn prompt doesn't appear
- Confirm the Relying Party ID matches the exact hostname the app is served from
- Confirm the `browser-passwordless` flow is bound as the Browser Flow in Authentication → Bindings
- In dev, Caddy's localhost TLS must be trusted — WebAuthn requires a secure context (HTTPS)

### Passkey registered but login fails with "invalid credential"
- The passkey is bound to the origin. If the hostname changed since registration, re-register the passkey.
- Check that `APP_HOSTNAME` in `.env` exactly matches what was used during passkey registration.

### JWT `iss` claim mismatch
The backend validates `iss` using the internal Keycloak URL (`http://keycloak:8080/realms/homeflow`). Keycloak issues tokens with the `iss` set to whatever hostname it thinks it's running on. The `KC_HOSTNAME` env var must be set correctly so the `iss` in the token matches what `config/keycloak.ts` expects. If these diverge, all tokens will fail validation.

### Service account missing `manage-users` role after realm import
Verify after every import: Clients → `homeflow-backend` → Service Account Roles → confirm `manage-users` is listed under `realm-management`. The role assignment sometimes does not survive a realm import cleanly and must be reassigned manually.

### Android app: login fails immediately with "redirect_uri" / "invalid redirect" error
The `homeflow-android` Valid Redirect URI does not match the app. It must be
exactly `org.homeflow.mobile:/oauth2redirect` and match both `AuthConfig.REDIRECT_URI`
and the `appAuthRedirectScheme` manifest placeholder. Note the single slash
(`scheme:/path`) — a mismatch here is the most common cause.

### Android app: login succeeds but every API call returns 401
Same root cause as the web case — the audience mapper. It is **per-client**:
confirm `homeflow-android` has its own audience mapper (or the shared audience
scope) adding `homeflow-backend` to the access token. Decode the token at jwt.io
and check `aud`.

### Android app: re-login is required every time / session doesn't persist
The `offline_access` scope is not being granted, so the app only has a short
online refresh token. Confirm: (1) the app requests `offline_access`, (2) the
scope is assigned to `homeflow-android`, and (3) realm *Offline session idle* is
enabled (not "Disabled"). Without all three, no offline token is issued.

### Android app: passkey prompt fails inside the login Custom Tab
The WebAuthn Relying Party ID must equal the hostname the app connects to. If the
app uses the Tailscale `*.ts.net` name but the passkey was registered against
`homeflow.lan` (or vice-versa), it fails. Pick one canonical hostname for the
realm (Phase 0b) and register the passkey against it.
