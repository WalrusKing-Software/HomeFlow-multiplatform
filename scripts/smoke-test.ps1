<#
.SYNOPSIS
    Read-only smoke test for the HomeFlow self-hosted stack (Phases 0-3).

.DESCRIPTION
    Verifies the running Docker stack end-to-end without changing any data:
      - Docker is up and the four services are running
      - Postgres has both databases + the restricted app role
      - The schema is migrated and reference data is seeded (exact counts)
      - The backend answers /health through Caddy (and internally)
      - Unauthenticated / bad-token API calls are rejected 401 in the ApiError shape

    It does NOT need a real user token (that needs the OIDC + passkey flow, which
    arrives with the client apps in Phase 7). It only reads non-secret values from
    .env (APP_HOSTNAME, DB/role names) and never writes anything.

.NOTES
    Bring the stack up first:
        docker compose build backend
        docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
    Then run the migrations once (see __docs/DEPLOYMENT.md / README):
        $env:DATABASE_URL="jdbc:postgresql://localhost:5433/period_tracker"
        $env:DATABASE_USER="postgres"; $env:DATABASE_PASSWORD="<POSTGRES_PASSWORD>"
        .\gradlew.bat :server:flywayMigrate

.PARAMETER Hostname
    Override the host used for external HTTPS checks (defaults to APP_HOSTNAME in .env).

.EXAMPLE
    .\scripts\smoke-test.ps1
#>

[CmdletBinding()]
param(
    [string]$Hostname
)

$ErrorActionPreference = 'Continue'

# ── Result tracking ──────────────────────────────────────────────────────────
$script:pass = 0; $script:fail = 0; $script:warn = 0

function Section($title) {
    Write-Host ""
    Write-Host "== $title ==" -ForegroundColor Cyan
}

function Pass($name, $detail) {
    $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
}
function Fail($name, $detail) {
    $script:fail++; Write-Host "  [FAIL] $name" -ForegroundColor Red
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
}
function Warn($name, $detail) {
    $script:warn++; Write-Host "  [WARN] $name" -ForegroundColor Yellow
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
}

# ── Locate repo root (script lives in scripts/) ──────────────────────────────
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host "HomeFlow stack smoke test" -ForegroundColor White
Write-Host "Repo: $repoRoot" -ForegroundColor DarkGray

# ── Read non-secret config from .env ─────────────────────────────────────────
$cfg = @{
    APP_HOSTNAME      = 'localhost'
    POSTGRES_USER     = 'postgres'
    POSTGRES_DB       = 'period_tracker'
    POSTGRES_APP_USER = 'app_user'
}
$envPath = Join-Path $repoRoot '.env'
if (Test-Path $envPath) {
    foreach ($line in Get-Content $envPath) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#') -or -not $t.Contains('=')) { continue }
        $k, $v = $t -split '=', 2
        # strip inline "  # comment" (safe for the hostname/db/role keys we read)
        $v = ($v -replace '\s+#.*$', '').Trim()
        if ($cfg.ContainsKey($k.Trim())) { $cfg[$k.Trim()] = $v }
    }
} else {
    Warn ".env not found" "Using defaults; copy .env.example to .env for accurate values."
}
if ($Hostname) { $cfg.APP_HOSTNAME = $Hostname }

$pgUser = $cfg.POSTGRES_USER
$pgDb   = $cfg.POSTGRES_DB
$appHost   = $cfg.APP_HOSTNAME

# ── Helpers ──────────────────────────────────────────────────────────────────
function Test-DockerUp {
    docker info *> $null
    return $LASTEXITCODE -eq 0
}

function Test-ServiceRunning($svc) {
    $id = (docker compose ps -q $svc 2>$null | Out-String).Trim()
    if (-not $id) { return $false }
    $state = (docker inspect -f '{{.State.Running}}' $id 2>$null | Out-String).Trim()
    return ($state -eq 'true')
}

function Invoke-Psql($db, $sql) {
    return (docker compose exec -T postgres psql -U $pgUser -d $db -tAc $sql 2>$null | Out-String).Trim()
}

$haveCurl = [bool](Get-Command curl.exe -ErrorAction SilentlyContinue)

function Get-HttpStatus($url, [string[]]$ExtraArgs = @()) {
    $a = @('-k', '-s', '-o', 'NUL', '-w', '%{http_code}', '--max-time', '10', $url) + $ExtraArgs
    return (& curl.exe @a 2>$null | Out-String).Trim()
}
function Get-HttpBody($url, [string[]]$ExtraArgs = @()) {
    $a = @('-k', '-s', '--max-time', '10', $url) + $ExtraArgs
    return (& curl.exe @a 2>$null | Out-String)
}

# ── 1. Docker + services ─────────────────────────────────────────────────────
Section "Docker & services"
if (-not (Test-DockerUp)) {
    Fail "Docker daemon" "Docker does not appear to be running. Start Docker Desktop and retry."
    Write-Host ""
    Write-Host "Aborting: nothing else can be checked without Docker." -ForegroundColor Red
    exit 1
}
Pass "Docker daemon is running"

$servicesOk = $true
foreach ($svc in @('postgres', 'keycloak', 'backend', 'caddy')) {
    if (Test-ServiceRunning $svc) {
        Pass "service '$svc' is running"
    } else {
        Fail "service '$svc' is not running" "Start it: docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d $svc"
        $servicesOk = $false
    }
}

# ── 2. Postgres databases & role ─────────────────────────────────────────────
Section "PostgreSQL"
if (Test-ServiceRunning 'postgres') {
    foreach ($db in @($pgDb, 'keycloak')) {
        $r = Invoke-Psql 'postgres' "SELECT 1 FROM pg_database WHERE datname='$db'"
        if ($r -eq '1') { Pass "database '$db' exists" }
        else { Fail "database '$db' missing" }
    }
    $role = Invoke-Psql 'postgres' "SELECT 1 FROM pg_roles WHERE rolname='$($cfg.POSTGRES_APP_USER)'"
    if ($role -eq '1') { Pass "restricted role '$($cfg.POSTGRES_APP_USER)' exists" }
    else { Fail "restricted role '$($cfg.POSTGRES_APP_USER)' missing" }
} else {
    Warn "Postgres checks skipped" "postgres service is not running."
}

# ── 3. Schema migrated + seed data ───────────────────────────────────────────
Section "Schema & seed data (Flyway)"
if (Test-ServiceRunning 'postgres') {
    $usersTable = Invoke-Psql $pgDb "SELECT to_regclass('public.users')"
    if (-not $usersTable) {
        Fail "schema not migrated" "Run: .\gradlew.bat :server:flywayMigrate (see script header for DATABASE_* env)."
    } else {
        Pass "schema present (users table exists)"
        $expected = @{
            'ref_symptom_categories' = 10
            'ref_symptom_options'    = 65
            'ref_pain_regions'       = 5
            'ref_pain_locations'     = 19
        }
        foreach ($tbl in $expected.Keys) {
            $count = Invoke-Psql $pgDb "SELECT count(*) FROM $tbl"
            if ($count -eq [string]$expected[$tbl]) {
                Pass "seed: $tbl = $count"
            } else {
                Fail "seed: $tbl = $count" "expected $($expected[$tbl]) (re-run V2 seed migration?)"
            }
        }
    }
} else {
    Warn "Seed checks skipped" "postgres service is not running."
}

# ── 4. Backend /health ───────────────────────────────────────────────────────
Section "Backend health"
# Internal path (always available regardless of TLS/hostname).
$internal = (docker compose exec -T caddy wget -qO- http://backend:8080/health 2>$null | Out-String).Trim()
if ($internal -match '"status"\s*:\s*"ok"') {
    Pass "internal /health (caddy -> backend:8080)" $internal
} else {
    Fail "internal /health failed" "Got: '$internal' (is the backend image rebuilt with current code?)"
}

# External path through Caddy (TLS).
if ($haveCurl) {
    $code = Get-HttpStatus "https://$appHost/health"
    $body = Get-HttpBody  "https://$appHost/health"
    if ($code -eq '200' -and $body -match '"status"\s*:\s*"ok"') {
        Pass "external https://$appHost/health -> 200"
    } elseif ($code -eq '000' -or -not $code) {
        Warn "external /health unreachable" "Could not connect to https://$appHost. If APP_HOSTNAME isn't 'localhost', add a hosts entry pointing it at 127.0.0.1."
    } else {
        Fail "external /health -> $code" "Body: $($body.Trim())"
    }
} else {
    Warn "external HTTPS checks skipped" "curl.exe not found on PATH."
}

# ── 5. Auth rejection (no real token needed) ─────────────────────────────────
Section "Auth rejection (401 in ApiError shape)"
if ($haveCurl) {
    $reachable = (Get-HttpStatus "https://$appHost/health") -eq '200'
    if (-not $reachable) {
        Warn "auth checks skipped" "https://$appHost not reachable (see hosts/cert note above)."
    } else {
        # No Authorization header.
        $code = Get-HttpStatus "https://$appHost/api/v1/users/me"
        $body = Get-HttpBody  "https://$appHost/api/v1/users/me"
        if ($code -eq '401' -and $body -match 'UNAUTHORIZED') {
            Pass "no token -> 401 UNAUTHORIZED"
        } else {
            Fail "no token -> $code" "Body: $($body.Trim())"
        }
        # Garbage bearer token.
        $h = @('-H', 'Authorization: Bearer not-a-real-token')
        $code2 = Get-HttpStatus "https://$appHost/api/v1/users/me" $h
        $body2 = Get-HttpBody  "https://$appHost/api/v1/users/me" $h
        if ($code2 -eq '401' -and $body2 -match 'UNAUTHORIZED') {
            Pass "bad token -> 401 UNAUTHORIZED"
        } else {
            Fail "bad token -> $code2" "Body: $($body2.Trim())"
        }
    }
} else {
    Warn "auth checks skipped" "curl.exe not found on PATH."
}

# ── Summary ──────────────────────────────────────────────────────────────────
Section "Summary"
Write-Host "  PASS: $script:pass   WARN: $script:warn   FAIL: $script:fail"
Write-Host ""
if ($script:fail -gt 0) {
    Write-Host "Smoke test FAILED ($script:fail check(s) failed)." -ForegroundColor Red
    exit 1
} elseif ($script:warn -gt 0) {
    Write-Host "Smoke test passed with warnings." -ForegroundColor Yellow
    exit 0
} else {
    Write-Host "Smoke test passed. The stack is healthy." -ForegroundColor Green
    exit 0
}
