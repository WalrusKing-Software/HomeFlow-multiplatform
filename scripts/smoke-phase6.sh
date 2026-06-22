#!/usr/bin/env bash
#
# smoke-phase6.sh — manual end-to-end smoke test for the FULL server API (phases
# 4–6) against a LIVE dev stack (`make dev`). It fetches a real Keycloak token and
# drives cycles, the daily-log anchor, symptom sub-logs, reference data, analytics,
# and preferences through Caddy exactly as a client would — proving the whole
# server surface works after Phase 6 ("server API is feature-complete").
#
# Correctness is covered by `:server:test` (Testcontainers). This script is a
# developer convenience for verifying the live, Dockerised stack end to end.
#
#   bash scripts/smoke-phase6.sh
#
# ── Prerequisites ────────────────────────────────────────────────────────────
#   * `make dev` is up (Caddy on https://localhost, Keycloak on :8180, PG on :5433)
#     and the backend image contains current code (`make build` after changes).
#   * `jq` (preferred) or `python3`, plus `curl` and GNU `date` (Git Bash is fine).
#   * A password-login test user exists AND `Direct access grants` is temporarily
#     enabled on the token client (the realm's normal flow is password + passkey,
#     which can't be scripted). See the one-time setup in the chat / scripts notes.
#     REVERT that toggle when done — it bypasses 2FA and is for local testing only.
#
#   * DESTRUCTIVE: to make analytics deterministic this script CLEARS the user's
#     cycles + daily logs + saved preferences (single-user self-hosted app) before
#     seeding known history. It does this via the Postgres dev container. Set
#     SMOKE_KEEP_DATA=1 to skip the reset (exact-value checks are then relaxed).
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
#   SMOKE_KEEP_DATA  set to 1 to skip the destructive reset
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
REF_FILE="$(mktemp)"
PASS=0
FAIL=0
RESET_OK=0
trap 'rm -f "$BODY_FILE" "$REF_FILE"' EXIT

# ── Output helpers ───────────────────────────────────────────────────────────
if [ -t 1 ]; then GRN=$'\e[32m'; RED=$'\e[31m'; DIM=$'\e[2m'; RST=$'\e[0m'; else GRN=; RED=; DIM=; RST=; fi
pass() { PASS=$((PASS + 1)); printf '%s  ✓ %s%s\n' "$GRN" "$1" "$RST"; }
fail() { FAIL=$((FAIL + 1)); printf '%s  ✗ %s%s\n' "$RED" "$1" "$RST"; }
info() { printf '%s· %s%s\n' "$DIM" "$1" "$RST"; }
die()  { printf '%s%s%s\n' "$RED" "$1" "$RST" >&2; exit 1; }

# Pick a *working* JSON reader (the Windows `python3` Store stub is on PATH but dead).
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

# jget FILTER — read a scalar field (.a or .a.b.c) from the last response in $BODY_FILE.
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

# option_id CATEGORY_SLUG OPTION_SLUG — pull an option UUID from the ref-data in $REF_FILE.
if [ "$JSON_TOOL" = "jq" ]; then
  option_id() {
    jq -r --arg c "$1" --arg o "$2" \
      '.categories[]|select(.slug==$c)|.options[]|select(.slug==$o)|.id' "$REF_FILE"
  }
else
  option_id() { "$PY" - "$1" "$2" "$REF_FILE" <<'PY'
import json, sys
cat, opt, f = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(f))
for c in data.get("categories", []):
    if c.get("slug") == cat:
        for o in c.get("options", []):
            if o.get("slug") == opt:
                print(o.get("id")); sys.exit(0)
print("")
PY
  }
fi

# order_lines — emit the .categoryOrder slugs (one per line) from $BODY_FILE.
if [ "$JSON_TOOL" = "jq" ]; then
  order_lines() { jq -r '.categoryOrder[]' "$BODY_FILE"; }
else
  order_lines() { "$PY" -c 'import json,sys;[print(s) for s in json.load(open(sys.argv[1]))["categoryOrder"]]' "$BODY_FILE"; }
fi

# json_arr_from_lines — turn a newline list on stdin into a JSON string array.
json_arr_from_lines() {
  local out="[" first=1 line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    if [ "$first" = 1 ]; then out="$out\"$line\""; first=0; else out="$out,\"$line\""; fi
  done
  printf '%s]' "$out"
}

# count NEEDLE — how many times NEEDLE appears in the last response body (compact JSON).
count() { grep -o -F -- "$1" "$BODY_FILE" | wc -l | tr -d ' '; }
# contains NEEDLE — true if the last response body contains NEEDLE.
contains() { grep -qF -- "$1" "$BODY_FILE"; }

command -v curl >/dev/null 2>&1 || die "Need 'curl' on PATH."

# req METHOD PATH [JSON_BODY] — perform the call, write the body to $BODY_FILE, echo status.
req() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-k -s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API_BASE$path")
  [ -n "${TOKEN:-}" ] && args+=(-H "Authorization: Bearer $TOKEN")
  if [ -n "$data" ]; then args+=(-H "Content-Type: application/json" -d "$data"); fi
  curl "${args[@]}"
}

assert_status() { if [ "$1" = "$2" ]; then pass "$3 (HTTP $2)"; else fail "$3 — expected $1, got $2"; fi; }
assert_eq()     { if [ "$1" = "$2" ]; then pass "$3"; else fail "$3 — expected '$1', got '$2'"; fi; }

# ── Known, in-the-past history so analytics are deterministic after a reset ──
TODAY=$(date +%F) || die "GNU 'date' required (use Git Bash)."
A_START=$(date -d "$TODAY -400 days" +%F)        || die "GNU 'date -d' required (use Git Bash)."
B_START=$(date -d "$A_START +28 days" +%F)       # starting B auto-closes A at B_START-1 (len 28)
A_END=$(date -d "$B_START -1 day" +%F)
B_END=$(date -d "$B_START +29 days" +%F)         # manual close -> cycle B inclusive length 30
PRED0_START=$(date -d "$B_START +29 days" +%F)   # avg 29 projected from the most recent start
PRED0_OVUL=$(date -d "$PRED0_START -14 days" +%F)

echo
info "stack: API=$API_BASE  keycloak=$KEYCLOAK_URL  client=$CLIENT_ID"
info "history: cycleA=$A_START..$A_END (28d, 3 flow)  cycleB=$B_START..$B_END (30d, 5 flow)"
echo

# ── 1. Unauthenticated surface ───────────────────────────────────────────────
TOKEN=""
status=$(req GET /health)
assert_status 200 "$status" "GET /health is public"
status=$(req GET /api/v1/analytics/cycle-stats)
assert_status 401 "$status" "analytics without a token is rejected"
status=$(req GET /api/v1/preferences)
assert_status 401 "$status" "preferences without a token is rejected"

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
  die "Token fetch failed. Check user '$SMOKE_USERNAME'/password and that 'Direct access grants' is on for '$CLIENT_ID'."
fi
pass "obtained an access token"
req GET /api/v1/users/me >/dev/null   # bootstrap the app-side user row

# ── 3. Optional reset for deterministic analytics ────────────────────────────
if [ "${SMOKE_KEEP_DATA:-0}" = "1" ]; then
  info "SMOKE_KEEP_DATA=1 — skipping reset; exact-value analytics checks relaxed."
elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -q \
    -c "DELETE FROM cycles;" -c "DELETE FROM user_dashboard_preferences;" >/dev/null 2>&1 \
    && { RESET_OK=1; info "cleared existing cycles + preferences for a clean run"; } \
    || info "reset query failed — exact-value analytics checks relaxed"
else
  info "Postgres container '$PG_CONTAINER' not found — skipping reset; exact checks relaxed"
fi

# ── 4. Reference data (Phase 5) ──────────────────────────────────────────────
req GET /api/v1/ref-data/symptom-categories >/dev/null
cp "$BODY_FILE" "$REF_FILE"
assert_eq "10" "$(count '"selectionType"')" "ref-data: 10 symptom categories returned"  # category-only field
status=$(req GET /api/v1/ref-data/pain-regions)
assert_status 200 "$status" "ref-data: pain regions"

FLOW_ID=$(option_id blood_flow medium)
SLEEP_ID=$(option_id sleep_quality woke_rested)
EMO_ID=$(option_id emotions anxious)
[ -n "$FLOW_ID" ] && [ -n "$SLEEP_ID" ] && [ -n "$EMO_ID" ] || die "could not resolve option ids from ref-data."
pass "resolved option ids (flow/sleep/emotions) from ref-data"

# ── 5. Seed history: cycles + flow + sleep + emotions (Phases 4–5) ───────────
status=$(req POST /api/v1/cycles "{\"startDate\":\"$A_START\"}")
assert_status 201 "$status" "create cycle A"
A_ID=$(jget .id)
status=$(req POST /api/v1/cycles "{\"startDate\":\"$B_START\"}")
assert_status 201 "$status" "create cycle B (auto-closes A)"
B_ID=$(jget .id)
status=$(req GET "/api/v1/cycles/$A_ID")
assert_eq "$A_END" "$(jget .endDate)" "  starting B auto-closed A at startB − 1 day"
status=$(req PATCH "/api/v1/cycles/$B_ID" "{\"endDate\":\"$B_END\"}")
assert_status 200 "$status" "close cycle B"

# log_day DATE CYCLE_ID  — anchor + flow + sleep for one day
log_day() {
  req POST /api/v1/daily-logs "{\"date\":\"$1\",\"cycleId\":\"$2\"}" >/dev/null
  req PUT "/api/v1/daily-logs/$1/flow"  "{\"optionId\":\"$FLOW_ID\"}" >/dev/null
  req PUT "/api/v1/daily-logs/$1/sleep" "{\"optionIds\":[\"$SLEEP_ID\"]}" >/dev/null
}
for d in 0 1 2; do log_day "$(date -d "$A_START +$d days" +%F)" "$A_ID"; done   # cycle A: 3 flow days
for d in 0 1 2 3 4; do log_day "$(date -d "$B_START +$d days" +%F)" "$B_ID"; done # cycle B: 5 flow days
pass "seeded 8 days of flow + sleep across the two cycles"

# Emotions multi-select round-trip + assembled day (Phase 5).
ANCHOR=$(date -d "$A_START +0 days" +%F)
status=$(req PUT "/api/v1/daily-logs/$ANCHOR/emotions" "{\"optionIds\":[\"$EMO_ID\"]}")
assert_status 200 "$status" "PUT emotions"
status=$(req GET "/api/v1/daily-logs/$ANCHOR")
assert_status 200 "$status" "GET the assembled day"
assert_eq "true" "$(contains "$EMO_ID" && echo true || echo false)" "  day assembles the emotion selection"
assert_eq "true" "$(contains "$FLOW_ID" && echo true || echo false)" "  day assembles the flow selection"

# A sub-log PUT for a never-created date is 404.
status=$(req PUT "/api/v1/daily-logs/$(date -d "$A_START +200 days" +%F)/emotions" "{\"optionIds\":[]}")
assert_status 404 "$status" "sub-log PUT with no anchor is 404"

# ── 6. Analytics (Phase 6) ───────────────────────────────────────────────────
status=$(req GET /api/v1/analytics/cycle-stats)
assert_status 200 "$status" "GET analytics/cycle-stats"
if [ "$RESET_OK" = 1 ]; then
  assert_eq "29" "$(jget .averageCycleLength)" "  averageCycleLength = mean(28,30) = 29"
  assert_eq "4"  "$(jget .averagePeriodLength)" "  averagePeriodLength = mean(3,5) = 4"
fi
if [ "$(jget .cycleVariation)" != "null" ]; then pass "  cycleVariation present"; else fail "  cycleVariation should not be null"; fi

status=$(req GET /api/v1/analytics/period-length-chart)
assert_status 200 "$status" "GET analytics/period-length-chart"
if [ "$RESET_OK" = 1 ]; then
  assert_eq "2" "$(count '"cycleStartDate"')" "  one data point per closed cycle"
  assert_eq "true" "$(contains '"bleedingDays":3' && echo true || echo false)" "  cycle A has 3 bleeding days"
  assert_eq "true" "$(contains '"bleedingDays":5' && echo true || echo false)" "  cycle B has 5 bleeding days"
fi

status=$(req GET /api/v1/analytics/ovulation-prediction)
assert_status 200 "$status" "GET analytics/ovulation-prediction"
assert_eq "3" "$(count '"predictedPeriodStart"')" "  3 future predictions"
if [ "$RESET_OK" = 1 ]; then
  assert_eq "29" "$(jget .averageCycleLength)" "  prediction uses the 29-day average"
  assert_eq "true" "$(contains "\"predictedPeriodStart\":\"$PRED0_START\"" && echo true || echo false)" \
    "  first predicted period start = $PRED0_START"
  assert_eq "true" "$(contains "\"predictedOvulationDate\":\"$PRED0_OVUL\"" && echo true || echo false)" \
    "  first predicted ovulation = start − 14 days"
fi

status=$(req GET /api/v1/analytics/sleep-predictions)
assert_status 200 "$status" "GET analytics/sleep-predictions"
if [ "$(jget .phases.menstruation)" != "null" ]; then
  pass "  menstruation phase has a sleep prediction"
  assert_eq "true" "$(contains "$SLEEP_ID" && echo true || echo false)" "  it ranks the logged sleep option"
else
  fail "  menstruation phase should have a sleep prediction"
fi

# ── 7. Preferences (Phase 6) ─────────────────────────────────────────────────
req GET /api/v1/preferences >/dev/null
DEFAULT_FIRST=$(order_lines | head -n1)
assert_eq "emotions" "$DEFAULT_FIRST" "preferences default to the reference sort order (first = emotions)"

REVERSED_JSON=$(order_lines | awk '{a[NR]=$0} END{for(i=NR;i>=1;i--)print a[i]}' | json_arr_from_lines)
status=$(req PUT /api/v1/preferences "{\"categoryOrder\":$REVERSED_JSON}")
assert_status 200 "$status" "PUT a full reordering"
req GET /api/v1/preferences >/dev/null
NEW_FIRST=$(order_lines | head -n1)
assert_eq "mind" "$NEW_FIRST" "  reordering persisted (first slug is now last default = mind)"

# An order missing a slug is rejected.
PARTIAL_JSON=$(order_lines | tail -n +2 | json_arr_from_lines)
status=$(req PUT /api/v1/preferences "{\"categoryOrder\":$PARTIAL_JSON}")
assert_status 400 "$status" "PUT an incomplete order is 400"
assert_eq "VALIDATION_ERROR" "$(jget .error.code)" "  rejection uses the ApiError shape"

# ── Summary ──────────────────────────────────────────────────────────────────
echo
printf '%s%d passed%s, %s%d failed%s\n' "$GRN" "$PASS" "$RST" "${RED}${FAIL:+}" "$FAIL" "$RST"
[ "$FAIL" -eq 0 ] || exit 1
echo "Phase 6 smoke test passed — the server API is feature-complete."
