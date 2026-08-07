-- V5__row_level_security.sql — defense-in-depth per-user isolation (issue #78).
--
-- App-layer row scoping (`AND user_id = <jwt user>` in every repository query,
-- covered by the cross-user integration tests) remains the PRIMARY, tested control.
-- This migration adds a SECOND enforcement layer at the database so that a future
-- missing `user_id` filter in application code still cannot leak rows across users.
--
-- How it works:
--   • The server sets `app.current_user_id` at the start of every authenticated
--     transaction (via set_config(..., is_local => true) == SET LOCAL — see
--     org.homeflow.db.userScopedTransaction). The setting is transaction-local, so
--     it never leaks to the next transaction that reuses a pooled connection.
--   • Each user-scoped table gets a policy `user_id = app_current_user_id()`, applied
--     to SELECT/INSERT/UPDATE/DELETE (USING + WITH CHECK). If the setting is unset,
--     app_current_user_id() returns NULL and the policy matches no rows — a safe
--     deny-by-default: forgetting to scope a transaction returns nothing, never
--     someone else's data.
--
-- FORCE ROW LEVEL SECURITY is set in addition to ENABLE so the policy also applies
-- to the table OWNER, not just the restricted runtime role. In production the app
-- connects as the restricted DML role (POSTGRES_APP_USER — a non-owner, non-superuser,
-- so ENABLE alone already binds it); FORCE closes the gap for any owner-role access.
-- (Superusers still bypass RLS unconditionally, but the app never connects as one.)
--
-- Reference tables (ref_*) and `users` are intentionally NOT covered: they hold no
-- `user_id`, and `users` is read during authentication BEFORE the request's user is
-- known. Account deletion (DELETE FROM users) still cascades to these tables — FK
-- referential actions bypass RLS by design.
--
-- Run manually by the Postgres superuser via the :server flywayMigrate task, like all
-- migrations. DDL only; no data is touched.

-- Resolves the current request's user id from the transaction-local GUC. Returns NULL
-- when unset (missing_ok => true) so an unscoped query is denied rather than erroring.
CREATE OR REPLACE FUNCTION app_current_user_id() RETURNS uuid
    LANGUAGE sql
    STABLE
    AS $$
        SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid
    $$;

-- Enable + force RLS and (re)create the isolation policy on every user-scoped table.
-- DROP POLICY IF EXISTS keeps this idempotent so it is safe to re-apply directly to a
-- running server and still records cleanly when Flyway later runs it.
DO $$
DECLARE
    tbl text;
    user_scoped_tables text[] := ARRAY[
        'cycles',
        'daily_logs',
        'daily_log_energy',
        'daily_log_flow',
        'daily_log_collection',
        'daily_log_emotions',
        'daily_log_sleep',
        'daily_log_discharge',
        'daily_log_skin',
        'daily_log_digestion',
        'daily_log_mind',
        'daily_log_sex',
        'pain_logs',
        'pain_log_locations',
        'user_dashboard_preferences',
        'sync_changes'
    ];
BEGIN
    FOREACH tbl IN ARRAY user_scoped_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', tbl || '_user_isolation', tbl);
        EXECUTE format(
            'CREATE POLICY %I ON %I '
            'USING (user_id = app_current_user_id()) '
            'WITH CHECK (user_id = app_current_user_id())',
            tbl || '_user_isolation', tbl
        );
    END LOOP;
END $$;
