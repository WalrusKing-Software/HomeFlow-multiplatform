#!/bin/sh
# Increment the patch component of the project version in gradle.properties by 1.
#
# The version source of truth for this repo is the `version=` key in
# gradle.properties (consumed by all Gradle modules; see __docs/BRANCHING.md).
# The last component is treated as a plain integer, so it is never capped at 9:
#   0.1.0  -> 0.1.1
#   0.1.9  -> 0.1.10
#   0.1.99 -> 0.1.100
#
# This is invoked from the Husky pre-commit hook on every commit. Run it by
# hand with `sh ./scripts/bump-version.sh` to bump without committing.

set -e

# Resolve gradle.properties relative to this script, so the hook works from any CWD.
PROPS_FILE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/gradle.properties"

if [ ! -f "$PROPS_FILE" ]; then
  echo "bump-version: gradle.properties not found at $PROPS_FILE" >&2
  exit 1
fi

current="$(grep -E '^version=' "$PROPS_FILE" | head -n1 | cut -d= -f2 | tr -d ' \t\r')"

if [ -z "$current" ]; then
  echo "bump-version: no 'version=' line in $PROPS_FILE" >&2
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

# In-place rewrite of the version= line (portable sed; keep a temp file).
tmp="$PROPS_FILE.tmp"
sed "s/^version=.*/version=${next}/" "$PROPS_FILE" > "$tmp" && mv "$tmp" "$PROPS_FILE"
echo "bump-version: $current -> $next"
