# Client–Server Compatibility

Records which client versions are compatible with which server versions.
Update this file before any release that changes the API contract or raises the
minimum supported client version.

From **v0.2.0** onward, the server and clients can be released independently.
**v0.1.x** releases were lockstep — all three components always shared the same
version number.

---

## Compatibility Matrix

| Server version | Min client version | API version | Notes |
|---|---|---|---|
| 0.1.x | 0.1.x | v1 | Lockstep initial releases — server and both clients always matched |

*Newest first. Add rows above this line when cutting a release that changes the
compatibility requirements.*

---

## How to add a row

1. Determine the oldest client version that still works against the new server
   (run the previous client release against the new server before raising the minimum).
2. Add a row at the top of the matrix (newest-first ordering).
3. When deploying to the homelab: set `MIN_CLIENT_VERSION=<value>` in your `.env`
   to match the "Min client version" column for the server you are running.

---

## Runtime enforcement

The server exposes `GET /api/v1/version` (no authentication required). Clients call
this endpoint on every login to check compatibility:

- If `clientVersion < minClientVersion` → the client shows an upgrade prompt and
  refuses to connect.
- If the server returns 404 → the server predates this feature; the client treats it
  as compatible (backward-compatible behavior).

The server reads two env vars at startup:

| Env var | Default | Effect |
|---|---|---|
| `SERVER_VERSION` | `"unknown"` | Self-reported version in the `/version` response |
| `MIN_CLIENT_VERSION` | equals `SERVER_VERSION` | Oldest client the server will accept |

---

## Tag formats

| Tag | Workflow | Release title |
|---|---|---|
| `v0.3.0` | `release.yml` | HomeFlow 0.3.0 (all 7 assets) |
| `server-v0.2.1` | `release-server.yml` | HomeFlow Server 0.2.1 (2 assets) |
| `desktop-v0.2.1` | `release-desktop.yml` | HomeFlow Desktop 0.2.1 (3 assets) |
| `android-v0.2.1` | `release-android.yml` | HomeFlow Android 0.2.1 (2 assets) |
| `clients-v0.2.1` | `release-clients.yml` | HomeFlow Clients 0.2.1 (5 assets: desktop + Android, no server) |

A `clients-v*` release ships desktop + Android together without a server change, so
the server's `MIN_CLIENT_VERSION` requirement is unaffected — it assumes the current
server already satisfies the new client build. If a client change needs a newer
server, cut a `server-v*` (or lockstep `v*`) release instead and update the matrix above.
