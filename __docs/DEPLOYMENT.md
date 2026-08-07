# DEPLOYMENT.md — Self-Hosting on a Raspberry Pi 5

Runbook for deploying the HomeFlow server to a **Raspberry Pi 5 (ARM64)** so the
**desktop and Android apps** can reach your data. Two access modes are covered: a
LAN deploy at `homeflow.lan` (internal-CA TLS) and — the recommended one for the
apps — **Tailscale with a canonical `*.ts.net` hostname** (§11).

Read `DOCKER.md`, `KEYCLOAK.md`, and `BACKUP.md` alongside this. The structure
mirrors the web app's runbook; the **differences are all in the build/migrate
steps** (Gradle + Flyway instead of pnpm + node-pg-migrate) and the absence of a
frontend container.

> **What's the same:** Postgres, Keycloak, Caddy, the dev-overlay-never-merges
> rule, the `iss`/RP-ID/canonical-hostname constraint, TLS trust. **What's
> different:** the backend is a JVM image; migrations are Flyway; there is no
> served web frontend (clients are installed apps).

---

## How production differs from dev

1. **The dev overlay (`docker-compose.dev.yml`) is never auto-merged.** Plain
   `docker compose up -d` / `make prod` runs the base file alone — dev ports
   (Postgres 5432, Keycloak 8180) and `KC_HOSTNAME_STRICT=false` never leak.
2. **Migrations don't run from the runtime image.** The production backend image
   ships only the app distribution — no Flyway CLI, no migration SQL on the
   classpath path used at runtime. Run migrations separately (Step 5).
3. **Dev and prod need different `.env`.** The Keycloak URL flips from
   `https://localhost` (dev) to the production hostname. Keep a separate prod `.env`
   on the Pi.

---

## Prerequisites

| Item | Recommendation |
|---|---|
| Board | Raspberry Pi 5, **8 GB** (two JVMs now — Keycloak *and* the Ktor backend) |
| Storage | USB SSD / NVMe — never run Postgres on an SD card |
| Power | Official 27 W PSU |
| OS | Raspberry Pi OS 64-bit (Bookworm+) |
| Docker | `curl -fsSL https://get.docker.com | sh` then `sudo usermod -aG docker "$USER"` |

All images (`postgres:16-alpine`, `quay.io/keycloak/keycloak:26.1`, `caddy:2-alpine`,
and the `eclipse-temurin` JVM build) are multi-arch and run natively on ARM64.

> JVM headroom: with two JVMs, set `JAVA_OPTS=-XX:MaxRAMPercentage=50` (or explicit
> `-Xmx`) on both the backend and Keycloak so they don't both grab the heap. Add
> zram/swap as a safety margin.

---

## Step 1 — Get the code onto the Pi

```bash
git clone <your-repo-url> homeflow-mp && cd homeflow-mp
```

The directory name becomes the Compose **project name** (prefixes volumes/networks,
e.g. `homeflow-mp_postgres_data`). Adjust later commands if yours differs.

---

## Step 2 — Second factor (TOTP) — no hostname config needed

The second factor is **TOTP** (an authenticator-app code), defined in
`infra/keycloak/realm-export.json` and imported on first boot. Unlike a WebAuthn passkey,
TOTP has **no Relying Party ID** — it is not bound to the hostname — so there is nothing to
edit here for either the LAN (`homeflow.lan`) or the Tailscale (`*.ts.net`) hostname, and no
re-enrollment when you move between them.

> Passkeys were the original second factor but were dropped: a passkey's RP-ID must be a
> public domain, and Android's Credential Manager will not offer any passkey provider for a
> private hostname. See `KEYCLOAK.md`. If a realm was imported before this change, switch the
> browser-flow binding to `browser-with-otp` and make `CONFIGURE_TOTP` the default required
> action via `kcadm`/the Admin Console, or recreate the Keycloak volume to re-import.

---

## Step 3 — Create the production `.env`

Generate fresh secrets (`openssl rand -base64 32` for `APP_ENCRYPTION_KEY`,
`openssl rand -base64 24` for passwords). Key variables:

| Variable | Value | Notes |
|---|---|---|
| `APP_HOSTNAME` | `homeflow.lan` | drives Caddy routing + `KC_HOSTNAME` |
| `PUBLIC_KEYCLOAK_URL` | `https://homeflow.lan` | **must match `APP_HOSTNAME`** or every JWT 401s on `iss` |
| `PUBLIC_KEYCLOAK_HTTP_PORT` | `443` | keeps the port out of the issuer URL |
| `PUBLIC_KEYCLOAK_REALM` | `homeflow` | |
| `KEYCLOAK_CLIENT_ID` | `homeflow-backend` | |
| `KEYCLOAK_CLIENT_SECRET` | *(generated)* | align in Keycloak per Step 6a |
| `POSTGRES_USER`/`POSTGRES_PASSWORD` | `postgres` / *(strong)* | superuser — migrations + Keycloak |
| `POSTGRES_APP_USER`/`POSTGRES_APP_PASSWORD` | `app_user` / *(strong, different)* | restricted runtime role |
| `POSTGRES_DB` | `period_tracker` | |
| `KEYCLOAK_ADMIN_USER`/`KEYCLOAK_ADMIN_PASSWORD` | `admin` / *(strong)* | bootstrap admin |
| `APP_ENCRYPTION_KEY` | *(32B base64)* | **set once, NEVER change** — rotating it makes all encrypted notes/sex logs unreadable |
| `TZ` | e.g. `America/New_York` | |

```bash
chmod 600 .env
```

The restricted `app_user` is created only on the **first** Postgres start (empty
volume) by `infra/postgres/init/`.

---

## Step 4 — Build and start

```bash
docker compose build        # backend (Gradle) + images; slow on first ARM64 run
make prod                   # docker compose up -d
docker compose ps
docker compose logs -f keycloak   # wait for realm import + "Listening on ...:8080"
```

The backend logs a DB-connection line, but the schema doesn't exist yet — migrate next.

---

## Step 5 — Run Flyway migrations

The runtime image doesn't carry the migration step, so run it from a one-off
**build-stage** container that has Gradle + the migration SQL, on the stack network,
as the **superuser**:

```bash
# 1. Find the network name
docker network ls --filter name=app-network

# 2. Build a throwaway image from the build stage
docker build --target build -t homeflow-migrate -f server/Dockerfile .

# 3. Run the Flyway migrate task against the postgres container
source .env
docker run --rm --network <project>_app-network \
  -e DATABASE_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}" \
  -e DATABASE_USER="${POSTGRES_USER}" \
  -e DATABASE_PASSWORD="${POSTGRES_PASSWORD}" \
  homeflow-migrate ./gradlew :server:flywayMigrate --no-daemon
```

> Alternatively wire a dedicated `migrate` Compose service or a
> `docker-compose.migrate.yml`. The throwaway-image method needs no extra repo
> wiring and is fine for a single node.
>
> **How Flyway is wired:** `:server` defines `flywayMigrate` / `flywayInfo` /
> `flywayValidate` as custom `JavaExec` tasks that run the Flyway CLI
> (`org.flywaydb:flyway-commandline` + the Postgres driver), pointed at
> `src/main/resources/db/migration`. The official `org.flywaydb.flyway` Gradle
> plugin is **not** used — it relies on Gradle APIs removed in Gradle 9 and is
> incompatible with the configuration cache. The custom tasks read
> `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` from the environment and
> set `cleanDisabled=true` so `clean` can never wipe the database.

---

## Step 6 — Keycloak post-import setup

No admin console port in prod — use the Admin CLI in the container. None of this is
in the realm export, so do it on every fresh deploy.

```bash
KC="docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD"
```

**6a — align the backend client secret** (env substitution during import is
unreliable):
```bash
BE_ID=$($KC get clients -r homeflow -q clientId=homeflow-backend --fields id --format csv --noquotes)
$KC update clients/$BE_ID -r homeflow -s secret="$KEYCLOAK_CLIENT_SECRET"
```

**6b — re-assign the `manage-users` service-account role** (needed for account
deletion; doesn't survive import):
```bash
$KC add-roles -r homeflow --uusername service-account-homeflow-backend \
  --cclientid realm-management --rolename manage-users
```

**6c — create a user + password** (repeat for each user the server should serve):
```bash
$KC create users -r homeflow -s username=<you> -s email=<you@example.com> \
  -s emailVerified=true -s enabled=true
$KC set-password -r homeflow --username <you> --new-password '<strong>'
```
The `CONFIGURE_TOTP` action fires on first login (Keycloak shows a QR to enroll an authenticator). Each user's data is isolated server-side; adding another user later needs no restart or config change.

> **Confirm the `homeflow-android` and `homeflow-desktop` public clients exist**
> (PKCE S256 required, correct redirect URIs, the `homeflow-backend` audience
> mapper, `offline_access`). See `KEYCLOAK.md`.

---

## Step 7 — TLS trust & hostname (LAN deploy)

- **Resolve `homeflow.lan` to the Pi.** `.lan` has no mDNS fallback — add a static
  DNS record on the router (`homeflow.lan → <Pi IP>`) and a DHCP reservation so the
  Pi's IP is stable. Clients must use the router as DNS (VPNs / `1.1.1.1` bypass it).
- **Install Caddy's root CA** on each device (the app and login browser need a trusted
  secure context):
  ```bash
  docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
  ```

For app access from anywhere, prefer the Tailscale path (§11) — it gives a
publicly-trusted cert and no per-device CA install.

---

## Step 8 — Verify

```bash
docker compose ps
curl -ks https://homeflow.lan/realms/homeflow/.well-known/openid-configuration \
  | python3 -c "import sys,json;print('iss:',json.load(sys.stdin)['issuer'])"
# expect: https://homeflow.lan/realms/homeflow
curl -ks -o /dev/null -w '%{http_code}\n' https://homeflow.lan/health
```

Then launch a client: login Custom Tab / system browser → password + TOTP code →
`GET /api/v1/users/me` returns the user.

---

## Step 9 — Backups & ops

- `pg_dump | gpg` on a cron (see `BACKUP.md`). Also back up **`caddy_data`** (local
  CA key + certs) and **`keycloak_data`**. Test a restore.
- `restart: unless-stopped` on every service; `sudo systemctl enable docker`.
- Firewall: only `80`/`443` inbound; never expose `5432` or Keycloak.

---

## Upgrading

```bash
git pull
docker compose build
# run migrations (Step 5) if this release adds any, then:
docker compose up -d
```

Volumes persist across `docker compose down` (without `-v`). Back up first.

---

## 11 — Remote access over Tailscale (recommended for the apps)

Tailscale gives the Pi a **stable hostname reachable from anywhere** (incl.
cellular), a **publicly-trusted TLS cert** (no CA to install), and a **single
canonical hostname** for tokens — opening no inbound ports.

> **Core constraint:** Keycloak stamps a fixed `iss` per hostname and the backend
> validates it strictly; redirect URIs are exact. Pick **one canonical hostname** and
> use it everywhere. Don't run one client on `homeflow.lan` and another on the `*.ts.net`
> name against the same realm — tokens won't cross. (TOTP itself is hostname-independent,
> so the second factor keeps working across hostnames; only `iss`/redirects are bound.)

**11a — install Tailscale** on the Pi (`curl -fsSL https://tailscale.com/install.sh | sh; sudo tailscale up`)
and on each client device; sign all into the same tailnet. Enable **MagicDNS** and
**HTTPS certificates** in the admin console. Note the name `homeflow.<tailnet>.ts.net`.

**11b — real cert into Caddy** (keeps Caddy as the single TLS edge):
```bash
sudo mkdir -p /opt/homeflow/tlscerts && cd /opt/homeflow/tlscerts
sudo tailscale cert homeflow.<tailnet>.ts.net
```
Mount `/opt/homeflow/tlscerts:/tlscerts:ro` into `caddy`, and in the Caddyfile
replace `tls internal` with `tls /tlscerts/{$APP_HOSTNAME}.crt /tlscerts/{$APP_HOSTNAME}.key`.

**11c — make the tailnet name canonical:** set `APP_HOSTNAME` and
`PUBLIC_KEYCLOAK_URL` to `https://homeflow.<tailnet>.ts.net` (they must match); recreate
`keycloak caddy backend`. TOTP has no hostname binding, so the second factor needs no
change and no re-enrollment when you switch to the tailnet name.

**11d — point the apps at the host:** launch the app, choose "Connect to a server" from
the mode chooser (or from Settings if already in Mode A), and enter the bare hostname
`homeflow.<tailnet>.ts.net`. The app persists the host in `ServerConfigStore`
(`~/.homeflow/server.properties` / Android SharedPreferences) — you won't need to enter
it again. The Android custom-scheme redirect and the desktop loopback redirect are both
hostname-independent; only the hostname matters for the OIDC issuer and API base URL.

**11e — verify** from a device on cellular: the OIDC discovery `iss` is the
`*.ts.net` URL with a trusted cert (no `-k`), and both apps complete login + `GET
/users/me`.

**11f — cert renewal:** `tailscale cert` is Let's Encrypt (90-day) and Caddy won't
auto-renew a cert it didn't issue. Re-run monthly and reload Caddy (cron). With
`tailscale serve` instead, renewal is automatic (but Tailscale becomes the edge).

> Tailscale access is **not** public-internet exposure — no inbound ports; only
> enrolled devices reach the Pi. Record this when updating `threat-model.md` for
> the client actors.

---

## Step 12 — "Adopt a server" migration (Mode A → Mode B)

If you have been using HomeFlow in **local-only (Mode A)** on your desktop and now want
to move that data to your newly-standing server:

1. **Ensure the server is running and reachable** at `homeflow.<tailnet>.ts.net` (Step 11).
2. **Open Settings on the desktop app** → "Server" section → tap **"Connect to a server"**.
3. **Enter the hostname** (`homeflow.<tailnet>.ts.net`) and tap Connect.
4. **Log in** via Keycloak (password + TOTP code).
5. **Confirm the upload prompt**: the app detects your local data and offers to upload it.
   Tap **Upload**. The export JSON is sent to `POST /api/v1/import?source=homeflow`;
   the result shows how many cycles and days were created.
6. **Verify** by opening the app on a second device ("Connect to a server", same host),
   logging in, and confirming the data is present.

**Idempotency:** re-running the upload from Settings is safe — cycles already on the
server are reused; days that already exist are skipped (`dailyLogsSkipped` count).

**Hostname ↔ Keycloak constraint:** the host you enter must match what Keycloak stamps
in the `iss` claim. If the canonical hostname doesn't match, login will 401. (TOTP has no
hostname binding, so only `iss`/redirects are affected.) See `KEYCLOAK.md` for the
common-gotchas checklist.

---

## Future: internet exposure

Point `APP_HOSTNAME` at a real public domain; Caddy auto-obtains/renews Let's
Encrypt. This is a deliberate threat-model expansion — review `threat-model.md`,
rate limits, and brute-force settings first.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Login OK but every request 401s | `iss` mismatch (`PUBLIC_KEYCLOAK_URL`/port ≠ `KC_HOSTNAME`) | align them (Step 3); compare live issuer (Step 8) |
| TOTP code rejected ("invalid authenticator code") | server/device clock skew | sync NTP on the Pi and the code-generating device (±30s window) |
| Migrate fails / no Flyway | ran inside the runtime image | use the build-stage one-off (Step 5) |
| Account deletion → 403 | `manage-users` role missing after import | re-assign (Step 6b) |
| Backend client auth fails after import | secret in Keycloak ≠ `.env` | set explicitly (Step 6a) |
| Postgres/Keycloak reachable from LAN | dev overlay was applied | restart with `make prod` |
| `*.ts.net` won't resolve | MagicDNS off / device not on tailnet | enable MagicDNS; re-check enrollment (11a) |
| First login shows a passkey/security-key prompt, not a code field | realm still on the old `browser-with-passkey` flow | re-import realm, or bind `browser-with-otp` + make `CONFIGURE_TOTP` default (Step 2) |
