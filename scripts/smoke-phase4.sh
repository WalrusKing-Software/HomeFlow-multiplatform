#!/usr/bin/env bash
#
# smoke-phase4.sh — manual end-to-end smoke test for Phase 4 (cycles + daily-log
# anchor) against a LIVE dev stack (`make dev`). It fetches a real Keycloak token,
# drives the cycle/daily-log routes through Caddy exactly as a client would, and
# peeks at Postgres to prove notes are encrypted at rest.
#
# This is a developer convenience for local verification — NOT part of CI (that is
# covered by :server:test). Run it from anywhere:
#
#   bash scripts/smoke-phase4.sh
#
# ── Prerequisites ────────────────────────────────────────────────────────────
#   * `make dev` is up (Caddy on https://localhost, Keycloak on :8180, PG on :5433).
#   * The backend image contains the Phase 4 code (`make build` after code changes).
#   * `jq` (preferred) or `python3` on PATH, plus `curl` and GNU `date` (Git Bash ok).
#   * A password-login test user exists AND direct-access-grants is temporarily
#     enabled on the token client — the realm's normal flow is password + passkey,
#     which can't be scripted. See scripts/README or the Phase 4 chat notes. REVERT
#     that change when you're done; it bypasses 2FA and is for local testing only.
#
# ── Configuration (override via environment) ─────────────────────────────────
#   SMOKE_PASSWORD   (required) the test user's password
#   SMOKE_USERNAME   test user (default: test)
#   API_BASE         default: https://localhost
#   KEYCLOAK_URL     default: http://localhost:8180
#   REALM            default: homeflow
#   CLIENT_ID        token client with direct grants on (default: homeflow-desktop)
#   PG_CONTAINER     default: homeflow-postgres-dev
#   PG_USER          default: postgres
#   PG_DB            default: period_tracker
#
set -u

API_BASE="${API_BASE:-https://localhost}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
REALM="${REALM:-homeflow}"
CLIENT_ID="${CLIENT_ID:-homeflow-desktop}"
SMOKE_USERNAME="${SMOKE_USERNAME:-test}"
PG_CONTAINER="${PG_CONTAINER:-homeflow-postgres-dev}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-period_tracker}"

BODY_FILE="$(mktemp)"
PASS=0
FAIL=0
trap 'rm -f "$BODY_FILE"' EXIT

# ── Output helpers ───────────────────────────────────────────────────────────
if [ -t 1 ]; then GRN=$'\e[32m'; RED=$'\e[31m'; DIM=$'\e[2m'; RST=$'\e[0m'; else GRN=; RED=; DIM=; RST=; fi
pass() { PASS=$((PASS + 1)); printf '%s  ✓ %s%s\n' "$GRN" "$1" "$RST"; }
fail() { FAIL=$((FAIL + 1)); printf '%s  ✗ %s%s\n' "$RED" "$1" "$RST"; }
info() { printf '%s· %s%s\n' "$DIM" "$1" "$RST"; }
die()  { printf '%s%s%s\n' "$RED" "$1" "$RST" >&2; exit 1; }

# Pick a *working* JSON reader. We probe rather than trust `command -v`, because on
# Windows `python3` is often the Microsoft Store stub (on PATH but non-functional).
JSON_TOOL=""
PY=""
if command -v jq >/dev/null 2>&1 && printf '{"a":1}' | jq -e . >/dev/null 2>&1; then
  JSON_TOOL="jq"
else
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 &&
      printf '{"a":1}' | "$candidate" -c 'import json,sys;json.load(sys.stdin)' >/dev/null 2>&1; then
      JSON_TOOL="py"; PY="$candidate"; break
    fi
  done
fi
[ -n "$JSON_TOOL" ] || die "Need a working 'jq' (or Python) on PATH to parse JSON responses."

# jget FILTER — read a field (.a or .a.b) from the last response in $BODY_FILE.
if [ "$JSON_TOOL" = "jq" ]; then
  jget() { jq -r "$1" "$BODY_FILE"; }
else
  jget() { "$PY" - "$1" "$BODY_FILE" <<'PY'
import json, sys
path, f = sys.argv[1], sys.argv[2]
cur = json.load(open(f))
for key in [p for p in path.strip().lstrip('.').split('.') if p]:
    cur = (cur or {}).get(key)
print('null' if cur is None else (cur if isinstance(cur, str) else json.dumps(cur)))
PY
  }
fi

command -v curl >/dev/null 2>&1 || die "Need 'curl' on PATH."

# req METHOD PATH [JSON_BODY] — performs the call, writes the body to $BODY_FILE,
# and echoes the HTTP status code. Sends the bearer token when $TOKEN is set.
req() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-k -s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API_BASE$path")
  [ -n "${TOKEN:-}" ] && args+=(-H "Authorization: Bearer $TOKEN")
  if [ -n "$data" ]; then
    args+=(-H "Content-Type: application/json" -d "$data")
  fi
  curl "${args[@]}"
}

# assert_status EXPECTED ACTUAL LABEL
assert_status() {
  if [ "$1" = "$2" ]; then pass "$3 (HTTP $2)"; else fail "$3 — expected $1, got $2"; fi
}
# assert_eq EXPECTED ACTUAL LABEL
assert_eq() {
  if [ "$1" = "$2" ]; then pass "$3"; else fail "$3 — expected '$1', got '$2'"; fi
}

# ── Test data: fresh, in-the-past dates so re-runs never collide ─────────────
# START is 60–1559 days ago; SECOND = START+28 (also past); the anchor sits inside
# the first cycle once SECOND closes it at START+27.
OFFSET=$(( (RANDOM % 1500) + 60 ))
START=$(date -d "-$OFFSET days" +%F)             || die "GNU 'date -d' required (use Git Bash)."
SECOND=$(date -d "$START +28 days" +%F)
EXPECTED_A_END=$(date -d "$SECOND -1 day" +%F)    # START + 27
ANCHOR=$(date -d "$START +1 day" +%F)             # inside cycle A
OUT_OF_RANGE=$(date -d "$START -5 days" +%F)      # before cycle A start -> 400
NO_LOG=$(date -d "$START +2 days" +%F)            # in range but never created -> 404
NOTE_TEXT="cramping in the afternoon $RANDOM"

echo
info "stack: API=$API_BASE  keycloak=$KEYCLOAK_URL  client=$CLIENT_ID"
info "dates: cycleA=$START  cycleB=$SECOND  anchor=$ANCHOR"
echo

# ── 1. Unauthenticated surface ───────────────────────────────────────────────
TOKEN=""
status=$(req GET /health)
assert_status 200 "$status" "GET /health is public"
assert_eq "ok" "$(jget .status)" "  health body is {\"status\":\"ok\"}"

status=$(req GET /api/v1/cycles)
assert_status 401 "$status" "GET /api/v1/cycles without a token is rejected"
assert_eq "UNAUTHORIZED" "$(jget .error.code)" "  401 uses the ApiError shape"

# ── 2. Token ─────────────────────────────────────────────────────────────────
[ -n "${SMOKE_PASSWORD:-}" ] || die "Set SMOKE_PASSWORD (the test user's password). See header comment."
info "fetching a token for '$SMOKE_USERNAME' via password grant…"
TOKEN_JSON=$(curl -s "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
  -d grant_type=password -d "client_id=$CLIENT_ID" \
  -d "username=$SMOKE_USERNAME" -d "password=$SMOKE_PASSWORD")
if [ "$JSON_TOOL" = "jq" ]; then
  TOKEN=$(printf '%s' "$TOKEN_JSON" | jq -r '.access_token // empty')
else
  TOKEN=$(printf '%s' "$TOKEN_JSON" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null)
fi
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  die "Token fetch failed. Check: user '$SMOKE_USERNAME'/password, and that 'Direct access grants' is enabled on client '$CLIENT_ID'."
fi
pass "obtained an access token"

# ── 3. Cycles: create, auto-close, current ───────────────────────────────────
status=$(req POST /api/v1/cycles "{\"startDate\":\"$START\"}")
assert_status 201 "$status" "POST /api/v1/cycles (cycle A)"
A_ID=$(jget .id)
assert_eq "null" "$(jget .endDate)" "  new cycle has a null endDate"
[ -n "$A_ID" ] && [ "$A_ID" != "null" ] || die "could not read cycle A id; aborting."

status=$(req POST /api/v1/cycles "{\"startDate\":\"$SECOND\"}")
assert_status 201 "$status" "POST /api/v1/cycles (cycle B)"
B_ID=$(jget .id)

status=$(req GET "/api/v1/cycles/$A_ID")
assert_status 200 "$status" "GET cycle A by id"
assert_eq "$EXPECTED_A_END" "$(jget .endDate)" "  starting B auto-closed A at startB − 1 day"

status=$(req GET /api/v1/cycles/current)
assert_status 200 "$status" "GET /api/v1/cycles/current"
assert_eq "$B_ID" "$(jget .id)" "  current cycle is the still-open B"

status=$(req GET /api/v1/cycles/3333aaaa-0000-0000-0000-000000000000)
assert_status 404 "$status" "GET an unknown cycle id is 404"

# Future start date is rejected.
FUTURE=$(date -d "+2 days" +%F)
status=$(req POST /api/v1/cycles "{\"startDate\":\"$FUTURE\"}")
assert_status 400 "$status" "POST cycle with a future start date is 400"

# Manual close via PATCH.
CLOSE_DAY=$(date -d "$SECOND +3 days" +%F)
status=$(req PATCH "/api/v1/cycles/$B_ID" "{\"endDate\":\"$CLOSE_DAY\"}")
assert_status 200 "$status" "PATCH closes cycle B"
assert_eq "$CLOSE_DAY" "$(jget .endDate)" "  endDate reflects the close"

# ── 4. Daily-log anchor: validation + conflict ───────────────────────────────
status=$(req POST /api/v1/daily-logs "{\"date\":\"$ANCHOR\",\"cycleId\":\"$A_ID\"}")
assert_status 201 "$status" "POST /api/v1/daily-logs (in-range anchor)"
assert_eq "$ANCHOR" "$(jget .logDate)" "  anchor logDate echoes the request"

status=$(req POST /api/v1/daily-logs "{\"date\":\"$ANCHOR\",\"cycleId\":\"$A_ID\"}")
assert_status 409 "$status" "duplicate anchor for the same day is 409"

status=$(req POST /api/v1/daily-logs "{\"date\":\"$OUT_OF_RANGE\",\"cycleId\":\"$A_ID\"}")
assert_status 400 "$status" "anchor with an out-of-range date is 400"

status=$(req POST /api/v1/daily-logs "{\"date\":\"$ANCHOR\",\"cycleId\":\"not-a-uuid\"}")
assert_status 400 "$status" "anchor with a malformed cycleId is 400"

# ── 5. Encrypted notes round-trip ────────────────────────────────────────────
status=$(req PATCH "/api/v1/daily-logs/$ANCHOR/notes" "{\"notes\":\"$NOTE_TEXT\"}")
assert_status 200 "$status" "PATCH notes"
assert_eq "$NOTE_TEXT" "$(jget .notes)" "  response echoes the plaintext"

status=$(req GET "/api/v1/daily-logs/$ANCHOR")
assert_status 200 "$status" "GET the assembled day"
assert_eq "$NOTE_TEXT" "$(jget .notes)" "  notes come back decrypted"
assert_eq "null" "$(jget .emotions)" "  sub-logs are null (assembled in Phase 5)"

status=$(req GET "/api/v1/daily-logs/$NO_LOG")
assert_status 404 "$status" "GET a date with no log is 404"

# ── 6. Ciphertext at rest (direct DB peek) ───────────────────────────────────
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  STORED=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A \
    -c "SELECT notes FROM daily_logs WHERE log_date = '$ANCHOR';" | tr -d '\r')
  if [ -z "$STORED" ]; then
    fail "DB notes column is empty — expected ciphertext"
  elif printf '%s' "$STORED" | grep -qF -- "$NOTE_TEXT"; then
    fail "DB notes column contains the PLAINTEXT — encryption is not happening!"
  else
    pass "notes are ciphertext at rest (no plaintext in the DB column)"
    info "stored value: ${STORED:0:48}…"
  fi
else
  info "skipped DB ciphertext check (container '$PG_CONTAINER' not found / docker unavailable)"
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo
printf '%s%d passed%s, %s%d failed%s\n' "$GRN" "$PASS" "$RST" "${RED}${FAIL:+}" "$FAIL" "$RST"
[ "$FAIL" -eq 0 ] || exit 1
echo "Phase 4 smoke test passed."
