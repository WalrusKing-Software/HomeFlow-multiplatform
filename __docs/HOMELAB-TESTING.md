# HOMELAB-TESTING.md — End-to-End Testing on Proxmox

Runbook for deploying HomeFlow on the Proxmox homelab server and verifying:
1. The server stack runs correctly (Postgres + Keycloak + Caddy + backend).
2. The Windows desktop app installs from the release-test artifact and works in Mode A.
3. The Mode A → Mode B (local-only → server-connected) transition works end-to-end.

**Prerequisites:** Proxmox VE is running on the tower. Tailscale is installed on the
tower host (or will be installed inside the LXC). You have SSH access to the Proxmox
host.

Read `__docs/DEPLOYMENT.md` and `__docs/DOCKER.md` alongside this — this guide
references their steps rather than duplicating them.

---

## Part 1 — Set up a Docker LXC on Proxmox

A privileged Debian LXC is the lightest way to run Docker on Proxmox. It uses
significantly less RAM than a VM and starts in seconds. If you prefer a VM, create a
standard Debian/Ubuntu VM and skip to step 1.4.

### 1.1 — Download the Debian LXC template

In the Proxmox web UI:

1. **Datacenter → your node → local storage → CT Templates**
2. Click **Templates** → search `debian-12` → **Download**

### 1.2 — Create the LXC

**Datacenter → Create CT**

| Field | Value |
|---|---|
| Hostname | `homeflow` |
| Password | strong root password |
| Template | `debian-12-standard_...` |
| Disk size | 32 GB (on the NVMe/SSD pool — NOT the HDDs) |
| CPU cores | 2 |
| RAM | 2048 MB (Keycloak + Ktor — both JVM) |
| Swap | 1024 MB |
| Network | DHCP (assign a static DHCP reservation on your router so the IP is stable) |

> **Important:** check **Privileged container** (required for Docker inside LXC).

After creation, before starting:

**Options → Features → enable `keyctl` and `nesting`**

Then **Start** the container.

### 1.3 — Install Docker

SSH into the LXC (`ssh root@<lxc-ip>` or open a shell in the Proxmox console):

```bash
apt-get update && apt-get install -y ca-certificates curl gnupg
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg \
  | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update && apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
docker run --rm hello-world   # confirm Docker works
```

### 1.4 — Install Tailscale inside the LXC

```bash
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up
# Follow the auth link — sign into the same tailnet as your other devices.
```

Enable **MagicDNS** and **HTTPS certificates** in the Tailscale admin console if not
already done. Note the assigned name: `homeflow.<tailnet>.ts.net` (or similar —
check under Machines in the admin console). This is your **canonical hostname**.

---

## Part 2 — Deploy the HomeFlow stack

### 2.1 — Clone the repo

```bash
git clone https://github.com/ashay1341/HomeFlow-multiplatform homeflow && cd homeflow
```

### 2.2 — Create the production `.env`

Follow **DEPLOYMENT.md Step 3** in full. Key values specific to Proxmox/Tailscale:

```bash
APP_HOSTNAME=homeflow.<tailnet>.ts.net        # your Tailscale machine name
PUBLIC_KEYCLOAK_URL=https://homeflow.<tailnet>.ts.net
PUBLIC_KEYCLOAK_REALM=homeflow
# ... all other values from DEPLOYMENT.md Step 3
```

Generate secrets:
```bash
APP_ENCRYPTION_KEY=$(openssl rand -base64 32)
echo "APP_ENCRYPTION_KEY=$APP_ENCRYPTION_KEY"    # copy into .env
chmod 600 .env
```

> **`APP_ENCRYPTION_KEY` must be set once and never changed.** Back it up somewhere
> safe (password manager). Rotating it makes all encrypted notes and sex-tracking
> data unreadable.

### 2.3 — Set the WebAuthn RP ID

Follow **DEPLOYMENT.md Step 2** — edit `infra/keycloak/realm-export.json`:

```json
"webAuthnPolicyRpId": "homeflow.<tailnet>.ts.net",
"webAuthnPolicyPasswordlessRpId": "homeflow.<tailnet>.ts.net",
```

### 2.4 — Configure Caddy for the Tailscale cert

Follow **DEPLOYMENT.md §11b**:

```bash
# On the LXC (needs Tailscale running):
mkdir -p /opt/homeflow/tlscerts && cd /opt/homeflow/tlscerts
tailscale cert homeflow.<tailnet>.ts.net
# produces homeflow.<tailnet>.ts.net.crt and .key
```

Edit `infra/caddy/Caddyfile` — replace `tls internal` with:

```
tls /tlscerts/{$APP_HOSTNAME}.crt /tlscerts/{$APP_HOSTNAME}.key
```

Add the bind mount to the `caddy` service in `docker-compose.yml`:

```yaml
    volumes:
      - /opt/homeflow/tlscerts:/tlscerts:ro
      - ./infra/caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
```

### 2.5 — Choose: build on server or pull the GHCR test image

**Option A — Pull the GHCR test image (fast, recommended for testing):**

Authenticate to GHCR once:
```bash
docker login ghcr.io -u ashay1341 --password-stdin
# Paste a GitHub PAT with `read:packages` scope, then Ctrl-D.
# Create a PAT at: GitHub → Settings → Developer settings → Personal access tokens (classic) → read:packages
```

Start the stack using the image override:
```bash
HOMEFLOW_IMAGE=ghcr.io/ashay1341/homeflow-multiplatform-server:test-<sha> \
  docker compose -f docker-compose.yml -f docker-compose.image.yml up -d
```

The test image SHA is printed in the **"Print pull command"** annotation in the
`Server (dist + image)` job of the latest Release Test run on GitHub Actions.

**Option B — Build on the server (slower, ~5–10 min on first run):**

```bash
docker compose build && docker compose up -d
```

> Option A is recommended for iterative testing — you avoid running Gradle + the
> Android SDK on the server every time. Use Option B when you want to verify the
> production build path.

### 2.6 — Run Flyway migrations

Follow **DEPLOYMENT.md Step 5** exactly.

```bash
# Find the network name:
docker network ls --filter name=app-network

# Build a throwaway image from the build stage:
docker build --target build -t homeflow-migrate -f server/Dockerfile .

# Run migrations (as the Postgres superuser):
source .env
docker run --rm --network homeflow_app-network \
  -e DATABASE_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}" \
  -e DATABASE_USER="${POSTGRES_USER}" \
  -e DATABASE_PASSWORD="${POSTGRES_PASSWORD}" \
  homeflow-migrate ./gradlew :server:flywayMigrate --no-daemon --no-configuration-cache
```

### 2.7 — Keycloak post-import setup

Follow **DEPLOYMENT.md Step 6** in full:

- **6a** — align the backend client secret
- **6b** — re-assign `manage-users` service-account role
- **6c** — create your user account and set a password

### 2.8 — Verify

```bash
# Keycloak issuer:
curl -s https://homeflow.<tailnet>.ts.net/realms/homeflow/.well-known/openid-configuration \
  | python3 -c "import sys,json; print('iss:', json.load(sys.stdin)['issuer'])"
# expect: https://homeflow.<tailnet>.ts.net/realms/homeflow

# Backend health:
curl -s -o /dev/null -w '%{http_code}\n' https://homeflow.<tailnet>.ts.net/health
# expect: 200
```

If both pass, the stack is healthy.

---

## Part 3 — Test the Windows desktop app (Mode A)

1. Go to the latest **Release Test** run on GitHub Actions →
   [Actions → Release Test](https://github.com/ashay1341/HomeFlow-multiplatform/actions/workflows/release-test.yml)
2. Click the most recent successful run → scroll to **Artifacts** at the bottom.
3. Download **`desktop-msi`** → extract → install `HomeFlow-0.1.19-test.<sha>.msi`.
4. Launch HomeFlow. On first run the **mode chooser** appears.
5. Choose **"Use this device only"** (Mode A).
6. Add some test data: start a cycle, log a day with a few symptoms and notes.
7. Verify the data appears on the Dashboard and Day view. The analytics tab may
   show empty states until you have at least two closed cycles — that's expected.

> If Windows Defender SmartScreen blocks the unsigned installer:
> click **More info → Run anyway**. The installer is unsigned (see
> `__docs/RELEASE-PIPELINE.md` §11 — desktop signing is a future enhancement).

---

## Part 4 — Test Mode A → Mode B transition

This is the "adopt a server" flow (`DEPLOYMENT.md Step 12`).

### Prerequisites
- Desktop app installed and running with data in Mode A (Part 3 done).
- Server stack verified (Part 2 done) — health check returns 200.
- Your device is on Tailscale (so `homeflow.<tailnet>.ts.net` resolves).

### Steps

1. **Open Settings** in the desktop app (gear icon).
2. Tap **"Connect to a server"** in the Server section.
3. Enter the bare hostname: `homeflow.<tailnet>.ts.net` (no `https://`).
4. The app opens a browser for Keycloak login — sign in with your username and
   password. On first login Keycloak will prompt you to **register a passkey**
   (WebAuthn) — follow the browser prompt to register one.
5. After login, the app detects your local Mode A data and shows an **upload prompt**.
6. Tap **Upload**. The app POSTs your full history to `POST /api/v1/import`.
7. A summary shows how many cycles and days were uploaded.
8. Navigate to Dashboard — your data should appear, now reading from the server.

### Verify on the server side

```bash
# Should return your user record:
curl -H "Authorization: Bearer <access-token>" \
  https://homeflow.<tailnet>.ts.net/api/v1/users/me

# Or just check that data came through by opening the app on a second device
# (install the APK on Android, connect to the same server, log in).
```

---

## Part 5 — Test on a second device (optional but recommended)

Install the APK (`homeflow-android-0.1.19-test.<sha>.apk`) on your Android device:

1. Enable **"Install from unknown sources"** in Settings → Apps → Special app access.
2. Transfer the APK to the device (AirDrop, USB, or email it to yourself).
3. Install it.
4. Open HomeFlow → **"Connect to a server"** → enter `homeflow.<tailnet>.ts.net`.
5. Log in (passkey registered in Part 4 works here too).
6. Your cycles and days should appear immediately — data is coming from the server.

---

## Cert renewal (monthly cron on the LXC)

Tailscale certs are 90-day Let's Encrypt certs. Add a monthly cron to renew:

```bash
crontab -e
# Add:
0 2 1 * * tailscale cert homeflow.<tailnet>.ts.net && docker exec homeflow-caddy-1 caddy reload --config /etc/caddy/Caddyfile
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `curl /health` returns `502` | Backend not started or crashed | `docker compose logs backend` |
| Login 401s on every request | `iss` mismatch — Keycloak URL ≠ `APP_HOSTNAME` | align `PUBLIC_KEYCLOAK_URL` and `APP_HOSTNAME` in `.env`; restart |
| Passkey registration fails | RP-ID ≠ hostname | confirm `webAuthnPolicyRpId` in realm-export matches `APP_HOSTNAME` (Step 2.3) |
| `homeflow.<tailnet>.ts.net` doesn't resolve | Device not on Tailscale / MagicDNS off | check tailnet enrollment; enable MagicDNS |
| Docker inside LXC crashes | `nesting` or `keyctl` not enabled | Proxmox → LXC → Options → Features → enable both; restart LXC |
| GHCR pull denied | Not authenticated | `docker login ghcr.io` with PAT (read:packages) |
| SmartScreen blocks installer | Unsigned MSI | More info → Run anyway (safe — built from your own repo) |
