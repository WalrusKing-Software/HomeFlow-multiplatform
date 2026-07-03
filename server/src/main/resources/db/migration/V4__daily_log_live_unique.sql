-- Phase 16 sync fix: one daily log per date must apply only to LIVE rows.
--
-- V1 declared `UNIQUE (user_id, log_date)` on daily_logs. V3 added soft-delete
-- (deleted_at) for sync tombstones, but the unique constraint still counts
-- tombstoned rows. So once a day is soft-deleted (e.g. a cycle delete cascades day
-- tombstones), pushing a NEW day for that same date — with a different id, as an
-- offline client legitimately does — collides with the dead tombstone and the whole
-- sync push aborts with `duplicate key value violates unique constraint
-- "daily_logs_user_id_log_date_key"`. That stalls cross-device sync: the change never
-- lands and the server_seq cursor never advances, so other devices pull nothing.
--
-- Replace the constraint with a PARTIAL unique index scoped to live rows: at most one
-- non-deleted daily log per (user, date), while any number of tombstones may coexist.
-- IF EXISTS / IF NOT EXISTS keep this idempotent so it is safe to apply directly to a
-- running server (immediate hotfix) and still record cleanly when Flyway later runs it.
ALTER TABLE daily_logs DROP CONSTRAINT IF EXISTS daily_logs_user_id_log_date_key;

CREATE UNIQUE INDEX IF NOT EXISTS daily_logs_user_id_log_date_live_key
    ON daily_logs (user_id, log_date)
    WHERE deleted_at IS NULL;
