# DOCKER.md — Container Architecture

Four containers run on a single private Docker network (`app-network`). Only the
Caddy reverse proxy has published ports; everything else is reachable only by
container name inside the network. This is unchanged from the web app — the **only
difference is the backend image is a JVM app (Ktor) instead of Node**, and there
is no separate SvelteKit frontend container (the clients are native desktop/Android
apps, not a served web app).

```
[Desktop app] [Android app]      ← over Tailscale (DEPLOYMENT.md §11)
        │
        ▼
     [caddy]      ← ports 80, 443 (published)
        │
        └──▶ [backend]   :8080 (Ktor, internal only)
                  │
                  ├──▶ [postgres]  :5432 (internal only)
                  └──▶ [keycloak]  :8080 (internal only)
                            │
                            └──▶ [postgres] (same container, separate DB)
```

> **Naming convention:** container names are prefixed with `homeflow-` and
> suffixed by environment — production uses `homeflow-postgres-prod`, …; the dev
> overlay (`docker-compose.dev.yml`, never auto-merged) uses `homeflow-…-dev`.
> The `homeflow-` prefix avoids collisions with other projects on the same host.
> Internal DNS resolves *service* names (`backend`, `postgres`), so
> `http://backend:8080` works in both.

> **No frontend container.** The web app's `frontend` service is gone — clients
> are installed apps. Caddy routes everything to the backend API. If you ever add
> a web client, reintroduce a `frontend` service then.

---

## Containers

### `postgres`
- Image `postgres:16-alpine`; hostname `postgres`; no published port in prod (5432
  in dev overlay only).
- Two DBs on one instance: `period_tracker` (app) and `keycloak`.
- Restricted app role created on first boot from `infra/postgres/init/` (see below).
- Volume `postgres_data`.

### `keycloak`
- Image `quay.io/keycloak/keycloak:26.1` (pin the minor; never `latest`).
- Hostname `keycloak`; no published port (admin console dev-only via overlay).
- Imports `infra/keycloak/realm-export.json` on first start (`--import-realm`).
- Volume `keycloak_data`.

### `backend` (Ktor — the changed one)
- Built from `server/Dockerfile` (JVM image). Hostname `backend`, internal port `8080`.
- Depends on `postgres` (healthy) + `keycloak` (started). No published port.

### `caddy`
- Image `caddy:2-alpine`; published `80`/`443`. Config bind-mounted from
  `infra/caddy/Caddyfile`. Volume `caddy_data` (holds the local CA key + certs —
  **back this up**).

---

## `server/Dockerfile` (JVM, multi-stage)

```dockerfile
# ── Stage: build — produce a fat/distribution jar with Gradle ────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
# Build only the server (and its :core dependency). Use the Gradle wrapper.
RUN ./gradlew :server:installDist --no-daemon

# ── Stage: runtime — minimal JRE, non-root ───────────────────────────────────
FROM eclipse-temurin:21-jre AS production
WORKDIR /app
COPY --from=build /app/server/build/install/server ./
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser
EXPOSE 8080
CMD ["./bin/server"]
```

> JVM memory note: the JVM backend uses more RAM than the old Node one. On a Pi,
> prefer the **8 GB** board (Keycloak's JVM is already the memory hog) and consider
> `-XX:MaxRAMPercentage` via `JAVA_OPTS`. Still fine on a Pi 5.

> **Migrations are not run from this image** — it ships only the app distribution.
> Run Flyway via a Gradle task or a one-off build-stage container (see
> `DEPLOYMENT.md`).

---

## `docker-compose.yml` (production, sketch)

Same shape as the web app minus `frontend`, with the backend building from
`server/`:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: postgres-prod
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./infra/postgres/init:/docker-entrypoint-initdb.d
    networks: [app-network]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s; timeout: 5s; retries: 5

  keycloak:
    image: quay.io/keycloak/keycloak:26.1
    container_name: keycloak-prod
    restart: unless-stopped
    command: start --import-realm
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: ${POSTGRES_USER}
      KC_DB_PASSWORD: ${POSTGRES_PASSWORD}
      KC_HOSTNAME: ${PUBLIC_KEYCLOAK_URL}     # full URL, not bare host (issuer pinning)
      KC_HOSTNAME_STRICT: true
      KC_HTTP_ENABLED: true
      KC_PROXY: edge
      KEYCLOAK_ADMIN: ${KEYCLOAK_ADMIN_USER}
      KEYCLOAK_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
    volumes:
      - ./infra/keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
      - keycloak_data:/opt/keycloak/data
    networks: [app-network]
    depends_on:
      postgres: { condition: service_healthy }

  backend:
    build: { context: ., dockerfile: server/Dockerfile }
    container_name: backend-prod
    restart: unless-stopped
    environment:
      API_PORT: 8080
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_APP_USER}        # restricted role at runtime
      POSTGRES_PASSWORD: ${POSTGRES_APP_PASSWORD}
      KEYCLOAK_INTERNAL_URL: http://keycloak:8080
      PUBLIC_KEYCLOAK_URL: ${PUBLIC_KEYCLOAK_URL}
      PUBLIC_KEYCLOAK_REALM: ${PUBLIC_KEYCLOAK_REALM}
      KEYCLOAK_CLIENT_ID: ${KEYCLOAK_CLIENT_ID}
      KEYCLOAK_CLIENT_SECRET: ${KEYCLOAK_CLIENT_SECRET}
      APP_ENCRYPTION_KEY: ${APP_ENCRYPTION_KEY}
    networks: [app-network]
    depends_on:
      postgres: { condition: service_healthy }
      keycloak: { condition: service_started }

  caddy:
    image: caddy:2-alpine
    container_name: caddy-prod
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./infra/caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    environment:
      APP_HOSTNAME: ${APP_HOSTNAME}
    networks: [app-network]
    depends_on: [backend]

volumes: { postgres_data: , keycloak_data: , caddy_data: , caddy_config: }
networks: { app-network: { driver: bridge } }
```

`docker-compose.dev.yml` (opt-in via `make dev`) adds: Postgres `5432` and
Keycloak `8180` host ports, `KC_HOSTNAME_STRICT: false`, `LOG_LEVEL: debug` and
`ALLOW_DEV_SECRETS: true` on the backend. It is never auto-merged.

**Container hardening (SEC-07/08, in the real compose file, elided from the sketch):**
every service runs with `no-new-privileges`; Caddy drops all capabilities except
`NET_BIND_SERVICE`; the backend runs on a read-only root filesystem with a `/tmp`
tmpfs; Keycloak enables `KC_HEALTH_ENABLED` with a healthcheck on management port
9000 and the backend has a TCP healthcheck — the backend waits for Keycloak
readiness (`service_healthy`), not just start.

---

## Caddy config

`infra/caddy/Caddyfile` — only `/api/*` and `/health` exist now (no SPA to serve):

```caddyfile
{$APP_HOSTNAME} {
    tls internal                       # LAN; swap for tailscale cert (DEPLOYMENT §11)
    request_body { max_size 26MB }     # SEC-06: import cap (25 MB) + multipart overhead
    handle /api/*   { reverse_proxy backend:8080 }
    handle /health  { reverse_proxy backend:8080 }
    handle /realms/* { reverse_proxy keycloak:8080 }   # OIDC for the native clients
    handle /resources/* { reverse_proxy keycloak:8080 } # Keycloak login-theme JS/CSS (login/OTP pages)
    header {
        Strict-Transport-Security "max-age=31536000"   # SEC-06
        X-Frame-Options DENY
        X-Content-Type-Options nosniff
        Referrer-Policy same-origin
        -Server
    }
}
```

> Unlike the web app, the native clients **do** hit Keycloak through Caddy for the
> OIDC browser flow (discovery, auth, token, end-session) over the canonical
> hostname — hence the `/realms/*` route. Never expose `/realms/*/admin` or the
> admin console.

---

## PostgreSQL init scripts

`infra/postgres/init/01_create_databases.sql` creates the `keycloak` DB;
`02_create_app_role.sql` creates the restricted `app_user` (DML only, no DDL) used
by the Ktor runtime. Migrations run as the superuser. These run once on first boot
(empty volume). Identical to the web app.

---

## Database roles

| Role | Vars | Permissions | Used for |
|---|---|---|---|
| Superuser | `POSTGRES_USER` / `POSTGRES_PASSWORD` | DDL + DML | Flyway migrations, Keycloak schema |
| App role | `POSTGRES_APP_USER` / `POSTGRES_APP_PASSWORD` | DML only | Ktor runtime (Exposed/Hikari) |

Must be different credentials in production.

---

## Env reference

See `ARCHITECTURE-server.md` for the validated config and `DEPLOYMENT.md` for the
production `.env` table. Generate secrets: `openssl rand -base64 32`
(`APP_ENCRYPTION_KEY`), `openssl rand -base64 24` (passwords).

`POSTGRES_SSLMODE` (optional, default `disable`) sets the JDBC TLS mode. `disable`
is correct for the single-host Docker network (postgres has no published port);
use `verify-full` if the database ever moves to a remote host.

---

## Common commands

The Makefile exposes matching `dev-*` / `prod-*` targets so the dev overlay is
never accidentally pointed at the prod stack:

```bash
make dev-start             # start dev (no build)
make dev-build             # build dev images only
make dev-rebuild           # rebuild changed images + recreate dev containers (keeps data)
make dev-reset             # down -v + build --no-cache + up -d (wipes dev volumes)
make dev-stop              # docker compose down (preserve volumes)
make dev-logs              # docker compose logs -f backend

make prod-start            # start prod (no build)
make prod-build            # build prod images only
make prod-rebuild          # rebuild changed images + recreate prod containers (keeps data)
make prod-reset CONFIRM=yes  # down -v + build --no-cache + up -d (DESTROYS prod data; refuses without CONFIRM=yes)
make prod-stop              # docker compose down (preserve volumes)
make prod-logs              # docker compose logs -f backend
```

`make dev` / `make prod` / `make build` / `make down` / `make logs` still work
as aliases for `dev-start` / `prod-start` / `dev-build` / `dev-stop` / `dev-logs`.

Migrations: see `DEPLOYMENT.md` (one-off build-stage container or a `:server`
Gradle Flyway task) — not run from the runtime image.
