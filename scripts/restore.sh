#!/bin/sh
# Restore an encrypted PostgreSQL backup produced by scripts/backup.sh.
#
#   gpg --decrypt *.sql.gpg  |  psql (inside the postgres container)
#
# The private key matching the backup's GPG_RECIPIENT must be available in the
# keyring running this script.
#
# Usage:
#   ./scripts/restore.sh <backup_file.sql.gpg> [target_db]
#
# target_db defaults to POSTGRES_DB from .env. Pass a scratch database name to
# test a restore without touching live data (see __docs/BACKUP.md).

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

set -a
# shellcheck disable=SC1091
. "$ROOT_DIR/.env"
set +a

BACKUP_FILE="${1:?Usage: restore.sh <backup_file.sql.gpg> [target_db]}"
TARGET_DB="${2:-$POSTGRES_DB}"

: "${POSTGRES_USER:?POSTGRES_USER missing from .env}"
[ -f "$BACKUP_FILE" ] || { echo "Backup file not found: $BACKUP_FILE" >&2; exit 1; }

echo "Restoring $BACKUP_FILE -> database '$TARGET_DB'"

# Decrypt on the host, stream the SQL into psql inside the container.
# ON_ERROR_STOP=1 makes the restore fail loudly instead of limping past errors.
gpg --batch --quiet --decrypt "$BACKUP_FILE" \
  | docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T postgres \
      psql -U "$POSTGRES_USER" -d "$TARGET_DB" -v ON_ERROR_STOP=1 --quiet

echo "Restore complete."
