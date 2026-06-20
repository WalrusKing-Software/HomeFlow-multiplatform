# Backup & Restore — Encrypted Database Backups

This document describes how to create and restore encrypted backups of the
PostgreSQL database. See `threat-model.md` TS-6 (compromised backup media): a
database backup is exactly as sensitive as the live database, so it is **never**
written to disk in plaintext.

## Principle

```
pg_dump (inside the postgres container)  |  gpg --encrypt  >  <db>_<timestamp>.sql.gpg
```

The plaintext SQL dump is streamed directly into `gpg` and only the encrypted
result is written to disk. Backups are encrypted to a GPG **public** key; the
matching **private** key is the only thing that can restore them and must be
stored separately from the backup media (e.g. on a hardware token or a machine
that does not hold the backups).

## One-time setup

You need a GPG key pair whose public half is in the keyring on the backup host:

```bash
# Generate a key pair if you do not already have one
gpg --quick-generate-key "homelab-backups@yourdomain" default default 0

# Note the key's email/ID — that is your GPG_RECIPIENT
gpg --list-keys
```

Back up the **private** key somewhere safe and offline:

```bash
gpg --export-secret-keys --armor homelab-backups@yourdomain > backup-private-key.asc
# Move this to offline storage (NOT next to the backups), then delete the copy.
```

If you ever lose the private key, existing backups are unrecoverable — that is
the point.

## Create a backup

```bash
GPG_RECIPIENT=homelab-backups@yourdomain ./scripts/backup.sh
# Writes ./backups/period_tracker_YYYYMMDD_HHMMSS.sql.gpg

# Or choose an output directory:
GPG_RECIPIENT=homelab-backups@yourdomain ./scripts/backup.sh /mnt/external/pt-backups
```

The stack must be running (`docker compose up -d`). The script reads the
database name and superuser from `.env`.

## Restore a backup

The private key matching the backup's recipient must be in the keyring.

```bash
# Restore over the live database (destructive — the dump DROPs and recreates objects)
./scripts/restore.sh ./backups/period_tracker_20260612_232349.sql.gpg

# Restore into a different / scratch database to test without touching live data
./scripts/restore.sh ./backups/period_tracker_20260612_232349.sql.gpg period_tracker_restore_test
```

`restore.sh` runs with `ON_ERROR_STOP=1`, so a corrupt or partial backup fails
loudly rather than producing a half-restored database.

## Verifying a backup (do this periodically)

A backup you have never restored is not a backup. To verify a round trip
without risking live data:

```bash
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE DATABASE period_tracker_restore_test;"

./scripts/restore.sh ./backups/<file>.sql.gpg period_tracker_restore_test

# Compare row counts against the live DB, then drop the scratch DB
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "DROP DATABASE period_tracker_restore_test;"
```

This procedure was verified during Phase 13: a full backup → restore into a
scratch database reproduced every row, and the application-layer-encrypted
columns (`daily_logs.notes`, `daily_log_sex.encrypted_payload`) remained
ciphertext throughout — they are encrypted independently of the backup, so the
backup encryption is defense in depth on top of column encryption.

## Notes

- **Application-layer encryption is independent of backup encryption.** Even an
  unencrypted dump would still have `notes` and sex data as ciphertext, because
  those columns are encrypted by the app before they ever reach PostgreSQL. The
  GPG layer additionally protects the rest of the schema (dates, option
  references, structure) and is what makes a backup safe to store off-box.
- `backups/` is git-ignored.
- The Keycloak database (`keycloak`) is not health data; it holds realm/user
  config and can be re-imported from `keycloak/realm-export.json` if needed.
