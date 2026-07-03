# HomeFlow Server — Setup Guide

This guide walks you through deploying the HomeFlow server on a Raspberry Pi (or any
Docker-capable Linux host). The server stores your health data in an encrypted
PostgreSQL database behind Keycloak authentication.

**Estimated time:** 30–60 minutes on a first deployment.

---

## What you need

**Hardware**
- Raspberry Pi 5, **8 GB RAM** — two JVMs run simultaneously (Keycloak and the Ktor server)
- USB SSD or NVMe drive — do not run PostgreSQL on an SD card
- Official 27 W USB-C power supply

**Software on the Pi**
- Raspberry Pi OS 64-bit (Bookworm or newer)
- Docker Engine:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"
# Log out and back in for the group change to take effect
```

**Remote access (pick one)**
- **Tailscale** (recommended) — install on the Pi and on each client device; gives a
  publicly-trusted TLS certificate and a stable hostname reachable from anywhere
- **LAN-only** — a static DNS record on your router mapping `homeflow.lan` to the Pi's IP

---

## Step 1 — Get the server files

Download `homeflow-server-X.Y.Z.zip` from this release and unzip it on the Pi, **or**
use the pre-built Docker image and skip straight to Step 4a.

```bash
unzip homeflow-server-X.Y.Z.zip -d homeflow-server
cd homeflow-server
```

Alternatively, clone the full repository if you want to build from source:

```bash
git clone <your-repo-url> homeflow && cd homeflow
```

---

## Step 2 — Create the environment file

```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` and replace every `replace-with-*` placeholder:

| Variable | Value |
|---|---|
| `APP_HOSTNAME` | `homeflow.lan` (LAN) or `homeflow.<tailnet>.ts.net` (Tailscale) |
| `PUBLIC_KEYCLOAK_URL` | `https://<APP_HOSTNAME>` — **must exactly match `APP_HOSTNAME`** or every JWT 401s on `iss` |
| `TZ` | Your timezone, e.g. `America/New_York` |
| `POSTGRES_PASSWORD` | `openssl rand -base64 24` |
| `POSTGRES_APP_PASSWORD` | A **different** password: `openssl rand -base64 24` |
| `KEYCLOAK_ADMIN_PASSWORD` | `openssl rand -base64 24` |
| `KEYCLOAK_CLIENT_SECRET` | Leave the placeholder for now — you'll set it in Step 6a |
| `APP_ENCRYPTION_KEY` | **Set once, never change**: `openssl rand -base64 32` — rotating this key makes all encrypted notes permanently unreadable |

---

## Step 3 — Set the WebAuthn Relying Party ID

Open `infra/keycloak/realm-export.json` and find:

```json
"webAuthnPolicyRpId": "homeflow.lan",
"webAuthnPolicyPasswordlessRpId": "homeflow.lan",
```

Change both values to your `APP_HOSTNAME` exactly (e.g. `homeflow.<tailnet>.ts.net` for
Tailscale). The passkey registered on first login is permanently bound to this hostname —
choose the canonical hostname you'll use permanently.

---

## Step 4 — Start the services

### Option A — Pull the pre-built image (recommended, no compilation on the Pi)

Edit `docker-compose.yml`: in the `backend` service, replace the `build:` block with:

```yaml
    image: ghcr.io/<owner>/homeflow-multiplatform-server:X.Y.Z
```

Then start everything:

```bash
docker compose up -d
docker compose logs -f keycloak   # wait for "Listening on ...:8080"
```

### Option B — Build on the Pi from source

```bash
docker compose build   # Gradle runs inside the container; ~10 min on first run
docker compose up -d
docker compose logs -f keycloak
```

Verify all services are up:

```bash
docker compose ps
```

---

## Step 5 — Run database migrations

The runtime image does not run migrations automatically. Run them once on first deploy,
and again after any upgrade whose release notes mention schema changes.

```bash
# Build the migration runner (uses the build stage, which has Gradle + SQL)
docker build --target build -t homeflow-migrate -f server/Dockerfile .

# Find your project's network name
docker network ls --filter name=app-network

source .env
docker run --rm --network <project>_app-network \
  -e DATABASE_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}" \
  -e DATABASE_USER="${POSTGRES_USER}" \
  -e DATABASE_PASSWORD="${POSTGRES_PASSWORD}" \
  homeflow-migrate ./gradlew :server:flywayMigrate --no-daemon
```

Replace `<project>_app-network` with your actual network name from the `docker network ls`
output (typically `homeflow_app-network` or `homeflow-server_app-network`).

---

## Step 6 — Keycloak post-import setup

The realm imports automatically on first Keycloak start, but three things must be
configured manually. These commands run against the Keycloak container:

```bash
KC="docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh"
source .env

$KC config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "${KEYCLOAK_ADMIN_USER}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}"
```

**6a — Set the backend client secret**

```bash
BE_ID=$($KC get clients -r homeflow -q clientId=homeflow-backend --fields id --format csv --noquotes)
NEW_SECRET="$(openssl rand -base64 32)"
$KC update clients/$BE_ID -r homeflow -s secret="$NEW_SECRET"
echo "Copy this into KEYCLOAK_CLIENT_SECRET in .env: $NEW_SECRET"
```

Update `KEYCLOAK_CLIENT_SECRET` in `.env` to match, then restart the backend so it picks
up the new secret:

```bash
docker compose up -d backend
```

**6b — Assign the manage-users role** (required for account deletion)

```bash
$KC add-roles -r homeflow \
  --uusername service-account-homeflow-backend \
  --cclientid realm-management \
  --rolename manage-users
```

**6c — Create your user account**

```bash
$KC create users -r homeflow \
  -s username=<your-username> \
  -s email=<your@email.com> \
  -s emailVerified=true \
  -s enabled=true

$KC set-password -r homeflow --username <your-username> --new-password '<strong-password>'
```

---

## Step 7 — TLS and hostname

### Tailscale (recommended)

Install Tailscale on the Pi:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

In the [Tailscale admin console](https://login.tailscale.com/admin/machines):
- Enable **MagicDNS**
- Enable **HTTPS certificates**
- Note your hostname: `homeflow.<tailnet>.ts.net`

Get the TLS certificate:

```bash
sudo mkdir -p /opt/homeflow/tlscerts
sudo tailscale cert homeflow.<tailnet>.ts.net
sudo cp homeflow.<tailnet>.ts.net.crt homeflow.<tailnet>.ts.net.key /opt/homeflow/tlscerts/
```

Update `infra/caddy/Caddyfile` — replace `tls internal` with:

```
tls /tlscerts/homeflow.<tailnet>.ts.net.crt /tlscerts/homeflow.<tailnet>.ts.net.key
```

Add the volume mount to the `caddy` service in `docker-compose.yml`:

```yaml
    volumes:
      - /opt/homeflow/tlscerts:/tlscerts:ro
      # ... existing volumes
```

Restart everything to apply:

```bash
docker compose down
docker compose up -d
```

Also install Tailscale on each device that will run the desktop or Android app and join
the same tailnet.

**Certificate renewal:** The Tailscale cert is 90-day Let's Encrypt. Caddy won't
auto-renew a cert it didn't issue. Run `sudo tailscale cert homeflow.<tailnet>.ts.net`
monthly and reload Caddy: `docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile`.

### LAN-only

Add a static DNS record on your router: `homeflow.lan → <Pi IP>`. Add a DHCP reservation
so the Pi's IP doesn't change.

Export Caddy's internal CA certificate — each device that runs the app must trust it
(WebAuthn requires a trusted HTTPS context):

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

Copy `caddy-root.crt` to each device, then trust it. **How you trust it differs by client:**

- **Desktop app:** the app runs on the JVM, which does **not** use the OS trust store. In
  the app's **"Connect to your server"** screen, click **"My server uses a private
  certificate…"** and select `caddy-root.crt`. The app validates and remembers it. (The
  system browser used for login still needs the CA in the OS trust store for the passkey
  step — install it there too, see below.)
- **Android app:** install `caddy-root.crt` via **Settings → Security → Encryption &
  credentials → Install a certificate → CA certificate**.
- **Desktop OS / login browser:** install `caddy-root.crt` as a trusted root CA
  (Windows: *Trusted Root Certification Authorities* in the Local Machine store; macOS:
  add to the System keychain and mark *Always Trust*; Linux: `update-ca-certificates`).

---

## Step 8 — Verify

```bash
docker compose ps   # all services should show "Up"

# OIDC discovery — the issuer must match your APP_HOSTNAME
curl -sk "https://<APP_HOSTNAME>/realms/homeflow/.well-known/openid-configuration" \
  | python3 -c "import sys,json; print('iss:', json.load(sys.stdin)['issuer'])"
# Expected: https://<APP_HOSTNAME>/realms/homeflow

# Health check
curl -sk -o /dev/null -w '%{http_code}\n' "https://<APP_HOSTNAME>/health"
# Expected: 200
```

Now open a client app, enter your server hostname, and log in. **On first login** Keycloak
prompts you to register a passkey — save it to a password manager that supports passkeys
(e.g. Bitwarden).

---

## Step 9 — Backups

Back up these items regularly and test a restore before you need it:

- **`postgres_data` volume** — your health database (`pg_dump | gpg`)
- **`keycloak_data` volume** — your realm and user accounts
- **`caddy_data` volume** — TLS certificates and private key

Set up a cron job for automated encrypted backups. See `__docs/BACKUP.md` for the
recommended approach.

Enable Docker restart on boot:

```bash
sudo systemctl enable docker
```

---

## Upgrading

```bash
git pull   # or update the image tag in docker-compose.yml
# Check release notes — run migrations (Step 5) if schema changes are listed
docker compose build   # only if building from source
docker compose up -d
```

Volumes persist across `docker compose down` (without `-v`). Back up before upgrading.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Every API call returns 401 after login | `iss` mismatch — `PUBLIC_KEYCLOAK_URL` ≠ `APP_HOSTNAME` | Align them in `.env`; compare the live `iss` from Step 8 |
| WebAuthn passkey prompt never appears | RP ID ≠ hostname, or TLS not trusted by browser | Check RP ID (Step 3); trust the CA or use Tailscale cert (Step 7) |
| "Invalid credential" on passkey | Hostname changed since passkey was registered | Update RP ID to new hostname, re-register passkey |
| Migration fails | Ran inside the runtime image (no Flyway there) | Use the build-stage migration runner (Step 5) |
| Account deletion returns 403 | `manage-users` role missing | Re-assign the role (Step 6b) |
| Backend client auth fails after re-deploy | Secret in Keycloak ≠ `.env` | Re-run Step 6a |
| `*.ts.net` hostname won't resolve on a device | MagicDNS off, or device not enrolled in tailnet | Enable MagicDNS; check Tailscale status on the device |
| Passkey worked on LAN but fails over Tailscale | Passkey is bound to the old hostname | Set RP ID to the `*.ts.net` name (Step 3) and re-register |
| Postgres or Keycloak port reachable from LAN | Dev overlay was accidentally applied | Restart with `make prod` or plain `docker compose up -d` |
