#!/bin/sh
# Increment the patch component of one or all per-component versions in gradle.properties.
#
# Usage:
#   sh scripts/bump-version.sh server    # bump version.server
#   sh scripts/bump-version.sh desktop   # bump version.desktop
#   sh scripts/bump-version.sh android   # bump version.android
#   sh scripts/bump-version.sh clients   # bump version.desktop AND version.android together
#   sh scripts/bump-version.sh all       # bump all three
#
# This is a manual operation run before tagging a component or full-suite release.
# It is NOT called automatically by the pre-commit hook (independent releases require
# intentional, per-component bumps). See __docs/BRANCHING.md and COMPATIBILITY.md.

set -e

component="${1:-}"
if [ -z "$component" ]; then
  echo "Usage: bump-version.sh <server|desktop|android|clients|all>" >&2
  exit 1
fi

case "$component" in
  server)  keys="version.server" ;;
  desktop) keys="version.desktop" ;;
  android) keys="version.android" ;;
  clients) keys="version.desktop version.android" ;;
  all)     keys="version.server version.desktop version.android" ;;
  *)       echo "bump-version: unknown component '$component' (must be server|desktop|android|clients|all)" >&2; exit 1 ;;
esac

# Resolve gradle.properties relative to this script, so the hook works from any CWD.
PROPS_FILE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/gradle.properties"

if [ ! -f "$PROPS_FILE" ]; then
  echo "bump-version: gradle.properties not found at $PROPS_FILE" >&2
  exit 1
fi

# "clients" ships desktop + Android in lockstep (release-clients.yml validates this
# at tag time), so refuse to bump if they've already drifted apart from independent
# desktop-only/android-only releases — align them by hand first, then re-run.
if [ "$component" = "clients" ]; then
  d="$(grep -E '^version\.desktop=' "$PROPS_FILE" | head -n1 | cut -d= -f2 | tr -d ' \t\r')"
  a="$(grep -E '^version\.android=' "$PROPS_FILE" | head -n1 | cut -d= -f2 | tr -d ' \t\r')"
  if [ "$d" != "$a" ]; then
    echo "bump-version: version.desktop ($d) != version.android ($a) — align them manually before bumping 'clients'" >&2
    exit 1
  fi
fi

for key in $keys; do
  current="$(grep -E "^${key}=" "$PROPS_FILE" | head -n1 | cut -d= -f2 | tr -d ' \t\r')"

  if [ -z "$current" ]; then
    echo "bump-version: no '${key}=' line in $PROPS_FILE" >&2
    exit 1
  fi

  # Require MAJOR.MINOR.PATCH shape.
  case "$current" in
    *.*.*) ;;
    *)
      echo "bump-version: '$current' is not in MAJOR.MINOR.PATCH form" >&2
      exit 1
      ;;
  esac

  major="${current%%.*}"
  rest="${current#*.}"
  minor="${rest%%.*}"
  patch="${rest#*.}"

  # Every component must be numeric.
  for part in "$major" "$minor" "$patch"; do
    case "$part" in
      '' | *[!0-9]*)
        echo "bump-version: '$current' has a non-numeric component" >&2
        exit 1
        ;;
    esac
  done

  # Arithmetic increment handles 9 -> 10 and 99 -> 100 without special-casing.
  patch=$((patch + 1))
  next="${major}.${minor}.${patch}"

  # In-place rewrite of the key= line (portable sed; keep a temp file).
  tmp="$PROPS_FILE.tmp"
  sed "s/^${key}=.*/${key}=${next}/" "$PROPS_FILE" > "$tmp" && mv "$tmp" "$PROPS_FILE"
  echo "bump-version: ${key}: $current -> $next"
done
