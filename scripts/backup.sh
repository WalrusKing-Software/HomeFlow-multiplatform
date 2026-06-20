#!/bin/sh
# Encrypted PostgreSQL backup.
#
#   pg_dump (inside the postgres container)  |  gpg --encrypt  >  *.sql.gpg
#
# The plaintext dump never touches disk: it is streamed straight into gpg and
# only the encrypted result is written. See threat-model.md TS-6 (compromised
# backup media) — a backup is as sensitive as the live database, so it must be
# encrypted at rest, and the decryption (private) key must live somewhere other
# than the backup media.
#
# Usage:
#   GPG_RECIPIENT=you@example.com ./scripts/backup.sh [output_dir]
#
# GPG_RECIPIENT is the key ID / email of an asymmetric GPG key whose PUBLIC half
# is in the keyring running this script. The matching private key is what you
# guard separately and need to restore.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

# Load DB name + superuser from the project .env (not echoed).
set -a
# shellcheck disable=SC1091
. "$ROOT_DIR/.env"
set +a

: "${GPG_RECIPIENT:?Set GPG_RECIPIENT to the GPG key/email that can decrypt the backup}"
: "${POSTGRES_USER:?POSTGRES_USER missing from .env}"
: "${POSTGRES_DB:?POSTGRES_DB missing from .env}"

OUT_DIR="${1:-$ROOT_DIR/backups}"
mkdir -p "$OUT_DIR"

TS=$(date +%Y%m%d_%H%M%S)
OUT_FILE="$OUT_DIR/${POSTGRES_DB}_${TS}.sql.gpg"

echo "Backing up database '$POSTGRES_DB' (encrypted for '$GPG_RECIPIENT')"
echo "  -> $OUT_FILE"

# pg_dump runs as the superuser inside the container; gpg runs on the host.
# --clean --if-exists makes the dump self-contained and safely re-runnable.
docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists \
  | gpg --batch --yes --encrypt --recipient "$GPG_RECIPIENT" -o "$OUT_FILE"

echo "Done. Encrypted backup written ($(wc -c < "$OUT_FILE") bytes)."
