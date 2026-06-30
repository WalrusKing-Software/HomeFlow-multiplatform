-- Phase 16a: soft-delete columns on syncable anchors + sync change-log.
--
-- The two tombstonable aggregates are 'cycle' and 'day' (daily_log anchor + its
-- sub-logs). Preferences are a whole-value aggregate and are never tombstoned.
-- Sub-log tables (emotions, sleep, …) are NOT individually tombstoned — a day
-- tombstone covers them; a cycle tombstone cascades day tombstones.

ALTER TABLE cycles ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE daily_logs ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- Global monotonic sequence for sync_changes.server_seq.
-- Sequence values are NOT rolled back on transaction abort — gaps are acceptable.
CREATE SEQUENCE IF NOT EXISTS sync_seq START 1;

-- One row per (user, entity_type, entity_id) — upserted on every write so each
-- aggregate appears at most once. Clients pull rows with server_seq > their cursor.
-- entity_type ∈ { 'cycle', 'day', 'preferences' }
-- entity_id   = cycles.id | daily_logs.id | user_dashboard_preferences.id
CREATE TABLE sync_changes (
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity_type TEXT        NOT NULL,
    entity_id   UUID        NOT NULL,
    server_seq  BIGINT      NOT NULL DEFAULT nextval('sync_seq'),
    updated_at  TIMESTAMPTZ NOT NULL,
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, entity_type, entity_id)
);

CREATE INDEX idx_sync_changes_user_seq ON sync_changes (user_id, server_seq);
