# Threat Model — Secure Period Tracking Web App

## 1. Overview

This document defines the threat model for a self-hosted period tracking web application containing sensitive menstrual health data. The app is initially deployed on a private homelab network with potential future internet exposure. This threat model should be revisited any time the deployment model changes (e.g. LAN → internet-facing).

---

## 2. Assets to Protect

| Asset | Sensitivity | Why It Matters |
|---|---|---|
| Cycle & period logs | Critical | Can reveal pregnancy status, fertility windows, or reproductive choices |
| Symptom & pain logs | Critical | Detailed health data; could be used to infer medical conditions |
| Sex & intimacy logs | Critical | Highly personal; reputational and legal risk if exposed |
| Mood & mental health logs | High | Can reveal mental health conditions or emotional patterns |
| User identity (email, account) | High | Links all health data to a real person |
| Authentication credentials | High | Compromise grants full access to all data above |
| Database backups | Critical | Often overlooked; same sensitivity as live data |
| Application logs | Medium | Can leak PII or usage patterns if not carefully filtered |
| Keycloak configuration | High | Misconfiguration breaks all auth guarantees |
| Docker secrets & environment variables | High | DB credentials, JWT signing keys, client secrets |

---

## 3. Users & Trust Levels

| Actor | Trust Level | Description |
|---|---|---|
| Authenticated user | Trusted (scoped) | Can read and write only their own data |
| Homelab administrator (you) | Highly trusted | Full system access; primary operator |
| Unauthenticated user | Untrusted | No access to any data |
| Other LAN devices | Untrusted | Should not be able to reach internal services directly |
| External internet users | Untrusted | Blocked unless app is explicitly exposed |

---

## 4. Entry Points

| Entry Point | Exposure | Notes |
|---|---|---|
| SvelteKit frontend (browser) | LAN / future internet | Primary user-facing surface |
| Fastify REST API | Internal Docker network (via proxy) | All data reads/writes flow through here |
| Keycloak auth server | Internal Docker network only | Admin console must never be internet-facing |
| PostgreSQL | Internal Docker network only | Should never have a published port |
| Docker host (homelab machine) | LAN | Physical and SSH access is full system compromise |
| Backup files / volumes | Local disk | Plaintext backups are equivalent to a data breach |
| Reverse proxy (Caddy/Nginx) | LAN / future internet | The only container that should be publicly reachable |

---

## 5. Threat Actors

### TA-1 — Legal / Government Authority
**Motivation:** Subpoena or warrant for reproductive health data to establish evidence of a legal violation (e.g. seeking an abortion in a restricted jurisdiction).

**Capability:** High. Can compel disclosure from hosting providers, cloud services, and app operators. Cannot easily compel disclosure of data that is cryptographically unreadable.

**Relevance:** High. This is the primary adversary for a period tracking app in the current legal environment. Self-hosting reduces exposure compared to a commercial app, but does not eliminate it.

---

### TA-2 — Opportunistic External Attacker
**Motivation:** Credential stuffing, data theft for resale, ransomware.

**Capability:** Medium. Automated tooling, common vulnerability scanners, credential lists from other breaches.

**Relevance:** Low while LAN-only. High if the app is exposed to the internet. Grows significantly once a public URL exists.

---

### TA-3 — Targeted External Attacker
**Motivation:** Specific interest in this user's reproductive health data (e.g. stalker, abusive partner, private investigator).

**Capability:** Low to medium. Social engineering, phishing, attempting to access the homelab network.

**Relevance:** Medium. Depends heavily on the user's personal threat level. Should not be dismissed.

---

### TA-4 — Malicious or Compromised Dependency
**Motivation:** Supply chain attack via a compromised npm package, Docker base image, or Keycloak plugin.

**Capability:** High if successful. A compromised dependency has the same access as the app itself.

**Relevance:** Medium. Mitigated by locking dependency versions and using minimal base images.

---

### TA-5 — Physical Access to Homelab Hardware
**Motivation:** Theft, curiosity, or targeted access to the machine running the app.

**Capability:** High. Physical access to the host is game over unless disk encryption is in place.

**Relevance:** Low to medium. Depends on where the homelab machine lives and who has physical access to it.

---

### TA-6 — Accidental Data Exposure by Operator
**Motivation:** Not malicious — misconfiguration, committing secrets to git, exposing a port unintentionally.

**Capability:** N/A.

**Relevance:** High. The most statistically likely cause of a real data exposure for a solo homelab project.

---

## 6. Threat Scenarios

### TS-1 — Legal Subpoena of Health Data
**Actor:** TA-1  
**Attack path:** Authority requests data from operator; operator is compelled to provide database contents or backups.  
**Impact:** Critical — cycle and symptom data directly links user identity to reproductive health decisions.  
**Mitigations:**
- Encrypt sensitive columns at the application layer (not just disk); operator cannot hand over readable data they cannot decrypt
- Full account deletion permanently removes all records including backups
- Minimize data collection — only store what the user explicitly entered
- No third-party analytics or SDKs that could retain data independently

---

### TS-2 — Unauthenticated Access to API
**Actor:** TA-2, TA-3  
**Attack path:** Attacker accesses Fastify API endpoints without a valid session token.  
**Impact:** High — could read or write any user's health data.  
**Mitigations:**
- All routes require a validated JWT; no public data endpoints
- JWT claims validated on every request (iss, aud, exp, sub)
- Rate limiting on all endpoints; stricter limits on auth routes

---

### TS-3 — Horizontal Privilege Escalation (User A Reads User B's Data)
**Actor:** TA-2, TA-3  
**Attack path:** Authenticated user manipulates a resource ID in a request to access another user's records.  
**Impact:** Critical — full exposure of another user's health data.  
**Mitigations:**
- Every database query scoped with `AND user_id = $authenticatedUserId`
- PostgreSQL Row Level Security as a secondary enforcement layer
- Automated tests asserting cross-user access is rejected (403, not 404)

---

### TS-4 — Token Theft via XSS
**Actor:** TA-2, TA-3  
**Attack path:** Attacker injects a script that reads auth tokens from the browser and exfiltrates them.  
**Impact:** High — attacker gains a valid session as the target user.  
**Mitigations:**
- Tokens stored in `httpOnly`, `Secure`, `SameSite=Strict` cookies only — not `localStorage`
- Strict Content Security Policy blocks inline scripts and unauthorized `connect-src`
- Refresh token rotation with family invalidation detects stolen token reuse

---

### TS-5 — Database Credential Compromise
**Actor:** TA-2, TA-4  
**Attack path:** Attacker obtains PostgreSQL credentials via a leaked `.env` file, a compromised dependency, or a misconfigured Docker port.  
**Impact:** Critical if data is unencrypted; reduced to High if sensitive columns are application-layer encrypted.  
**Mitigations:**
- PostgreSQL container has no published ports; reachable only within Docker network
- App DB role has minimum necessary permissions (no DDL privileges)
- Sensitive columns encrypted at application layer
- Secrets never committed to git; managed via Docker secrets or `.env` excluded by `.gitignore`

---

### TS-6 — Compromised Backup Media
**Actor:** TA-1, TA-5  
**Attack path:** A plaintext database backup on disk or external media is accessed by an adversary.  
**Impact:** Critical — equivalent to direct database access.  
**Mitigations:**
- All backups encrypted before writing to disk (`pg_dump | gpg --encrypt`)
- Backup decryption keys stored separately from backup media
- Homelab disk encryption (LUKS or equivalent) for defense in depth

---

### TS-7 — Keycloak Misconfiguration
**Actor:** TA-6 (accidental), TA-2  
**Attack path:** Keycloak admin console exposed publicly, weak realm settings, or overly permissive client configuration allows unauthorized auth or account takeover.  
**Impact:** High — auth bypass or ability to create/modify user accounts.  
**Mitigations:**
- Admin console bound to `127.0.0.1` only; not reachable outside Docker network
- MFA enforced as a required action for all users
- Brute-force protection enabled in Keycloak realm settings
- Short access token TTL (≤15 min); refresh token rotation enabled

---

### TS-8 — Supply Chain Attack via Dependency
**Actor:** TA-4  
**Attack path:** A malicious or compromised npm package or Docker base image executes arbitrary code with app-level access.  
**Impact:** High — could exfiltrate data, plant backdoors, or escalate to host.  
**Mitigations:**
- Lock all dependency versions (`package-lock.json` committed, Docker image pinned by digest)
- Use minimal base images (e.g. `node:alpine`) to reduce attack surface
- Periodically audit dependencies with `npm audit`
- Run containers as non-root users to limit blast radius

---

### TS-9 — Accidental Secret Exposure
**Actor:** TA-6  
**Attack path:** Database password, JWT signing key, or Keycloak client secret committed to a git repository.  
**Impact:** High — any committed secret must be treated as fully compromised.  
**Mitigations:**
- `.gitignore` excludes all `.env` files before the first commit
- Pre-commit hook (husky) scans for secrets before allowing a commit
- Secrets rotated immediately if a commit containing them is ever made

---

## 7. Out of Scope

The following threats are acknowledged but considered out of scope for the current version:

- **Compromise of the homelab host OS** — assumed that the underlying machine is kept patched and SSH is secured with key-based auth. This is a prerequisite, not a feature.
- **Keycloak zero-day vulnerabilities** — mitigated by keeping Keycloak updated; no additional app-layer controls are feasible.
- **User device compromise** — if the user's browser or device is fully compromised, no server-side control prevents data exposure. Out of scope by definition.
- **Insider threat beyond the single operator** — this is a single-user homelab app; multi-operator access control is not modeled here.

---

## 8. Assumptions

- The homelab machine is physically secured and not accessible to untrusted parties.
- The operator (you) is the only person with SSH or direct host access.
- The app is currently LAN-only; this threat model must be re-evaluated before any internet exposure.
- No third-party cloud services store or process user data (no S3, no analytics, no CDN for dynamic content).
- The user is aware of and accepts the inherent risks of self-hosting sensitive health data.

---

## 9. Revision History

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-06-09 | Initial threat model — LAN deployment only |
