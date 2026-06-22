<#
.SYNOPSIS
    LOCAL DEV ONLY — mint a Keycloak access token (ROPC/password grant) and optionally
    run a Phase 5 smoke test against the running stack.

.DESCRIPTION
    A development convenience for poking the API by hand before the desktop/Android
    clients (Phase 7) exist. It uses Keycloak's Resource Owner Password Credentials
    grant, which REQUIRES "Direct Access Grants" enabled on the `homeflow-desktop`
    client and BYPASSES the password + passkey 2FA browser flow.

    NEVER enable ROPC in production. The project's mandated flow is Authorization
    Code + PKCE (S256). To return the dev realm to PKCE-only when you're done:

      $cid = docker exec homeflow-keycloak-dev /opt/keycloak/bin/kcadm.sh get clients `
        -r homeflow -q clientId=homeflow-desktop --fields id --format csv --noquotes
      docker exec homeflow-keycloak-dev /opt/keycloak/bin/kcadm.sh update clients/$cid `
        -r homeflow -s directAccessGrantsEnabled=false

    Uses Invoke-RestMethod (not curl.exe) because Windows PowerShell 5.1 strips the
    double quotes out of JSON when passing it to native executables. A localhost-only
    TLS-trust shim is installed for the session so the self-signed dev cert is accepted.

    The dev user's password is never stored in this script — supply it via the
    HOMEFLOW_DEV_PASSWORD env var, the -Password parameter, or the interactive prompt.

.EXAMPLE
    $env:HOMEFLOW_DEV_PASSWORD = 'DevTest123!'
    $tok = .\scripts\dev-token.ps1
    Invoke-RestMethod -Headers @{Authorization="Bearer $tok"} https://localhost/api/v1/users/me

.EXAMPLE
    .\scripts\dev-token.ps1 -Smoke          # prompts for the password if the env var is unset
#>
param(
    [string]$BaseUrl = "https://localhost",
    [string]$Realm = "homeflow",
    [string]$ClientId = "homeflow-desktop",
    [string]$User = "devtester",
    [string]$Password = $env:HOMEFLOW_DEV_PASSWORD,
    [switch]$Smoke
)

$ErrorActionPreference = "Stop"

# No credential is baked into this script. Provide the dev user's password via the
# HOMEFLOW_DEV_PASSWORD environment variable, the -Password parameter, or the prompt.
if (-not $Password) {
    $secure = Read-Host "Password for dev user '$User'" -AsSecureString
    $Password = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

# Dev-only: accept the self-signed localhost cert and force TLS 1.2 for this session.
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

$tokenResponse = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/realms/$Realm/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'password'
        client_id  = $ClientId
        username   = $User
        password   = $Password
        scope      = 'openid'
    }
$token = $tokenResponse.access_token

if (-not $Smoke) {
    Write-Host "Access token acquired (length $($token.Length))." -ForegroundColor Green
    return $token
}

# ── Phase 5 smoke test ────────────────────────────────────────────────────────
$headers = @{ Authorization = "Bearer $token" }
$date = (Get-Date).ToString('yyyy-MM-dd')

function Api {
    param([string]$Method, [string]$Path, $Body)
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Compress -Depth 6)
    }
    try {
        Invoke-RestMethod @params
    } catch [System.Net.WebException] {
        # Surface the API's JSON error body instead of a bare 4xx (e.g. 409 on re-run).
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            Write-Host "  ($Method $Path -> $([int]$resp.StatusCode)) $($reader.ReadToEnd())" -ForegroundColor DarkYellow
        } else { throw }
    }
}

Write-Host "1) Reference data - resolving option ids" -ForegroundColor Cyan
$cats = Api GET "/api/v1/ref-data/symptom-categories"
$regions = Api GET "/api/v1/ref-data/pain-regions"
function OptId($catSlug, $optSlug) {
    (($cats.categories | Where-Object slug -eq $catSlug).options | Where-Object slug -eq $optSlug).id
}
$fine = OptId emotions fine
$anxious = OptId emotions anxious
$noSex = OptId sex no_sex
$uterus = (($regions.regions.locations) | Where-Object slug -eq uterus).id

Write-Host "2) Start a cycle + create the day anchor for $date" -ForegroundColor Cyan
$cycle = Api POST "/api/v1/cycles" @{ startDate = $date }
Api POST "/api/v1/daily-logs" @{ date = $date; cycleId = $cycle.id } | Out-Null

Write-Host "3) PUT emotions / sex / pain" -ForegroundColor Cyan
Api PUT "/api/v1/daily-logs/$date/emotions" @{ optionIds = @($fine, $anxious) } | Out-Null
Api PUT "/api/v1/daily-logs/$date/sex" @{ optionIds = @($noSex) } | Out-Null
Api PUT "/api/v1/daily-logs/$date/pain" @{ locations = @(@{ locationId = $uterus; severity = 4 }) } | Out-Null

Write-Host "4) GET the assembled day" -ForegroundColor Cyan
Api GET "/api/v1/daily-logs/$date" | ConvertTo-Json -Depth 6
