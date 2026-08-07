-- V1__initial_schema.sql — HomeFlow initial schema.
--
-- Mirrors _planning/data-model.md (and the daily_log_sex encryption addendum) exactly.
-- Run manually by the Postgres superuser via the :server flywayMigrate task; the
-- restricted runtime app role only ever gets DML on these tables (see
-- infra/postgres/init/02_create_app_role.sh and __docs/DOCKER.md).
--
-- gen_random_uuid() is built into PostgreSQL 13+ core — no extension required.
-- All timestamps are timestamptz and default to now(); the application also sets
-- updated_at explicitly on writes.

-- ── Identity ────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_sub varchar(255) NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- ── Cycles ──────────────────────────────────────────────────────────────────

CREATE TABLE cycles (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_date date NOT NULL,
    end_date   date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_cycles_user_id ON cycles(user_id);
CREATE INDEX idx_cycles_user_start ON cycles(user_id, start_date);

-- ── Reference data (seeded in V2, read-only at runtime) ──────────────────────

CREATE TABLE ref_symptom_categories (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug           varchar(100) NOT NULL UNIQUE,
    label          varchar(255) NOT NULL,
    selection_type varchar(20) NOT NULL,  -- 'single' | 'multi'
    phase          varchar(20) NOT NULL,  -- 'always' | 'menstruation'
    sort_order     integer NOT NULL
);

CREATE TABLE ref_symptom_options (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id uuid NOT NULL REFERENCES ref_symptom_categories(id) ON DELETE CASCADE,
    slug        varchar(100) NOT NULL,
    label       varchar(255) NOT NULL,
    sort_order  integer NOT NULL,
    UNIQUE (category_id, slug)
);

CREATE INDEX idx_ref_symptom_options_category_id ON ref_symptom_options(category_id);

CREATE TABLE ref_pain_regions (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       varchar(100) NOT NULL UNIQUE,
    label      varchar(255) NOT NULL,
    sort_order integer NOT NULL
);

CREATE TABLE ref_pain_locations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    region_id  uuid NOT NULL REFERENCES ref_pain_regions(id) ON DELETE CASCADE,
    slug       varchar(100) NOT NULL,
    label      varchar(255) NOT NULL,
    sort_order integer NOT NULL,
    UNIQUE (region_id, slug)
);

CREATE INDEX idx_ref_pain_locations_region_id ON ref_pain_locations(region_id);

-- ── Daily logs (the per-day anchor) ──────────────────────────────────────────

CREATE TABLE daily_logs (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cycle_id   uuid NOT NULL REFERENCES cycles(id) ON DELETE CASCADE,
    log_date   date NOT NULL,
    notes      text,  -- [encrypted] AES-256-GCM, encrypted in the service layer only
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, log_date)
);

CREATE INDEX idx_daily_logs_user_id ON daily_logs(user_id);
CREATE INDEX idx_daily_logs_cycle_id ON daily_logs(cycle_id);

-- ── Single-selection symptom sub-logs (one row per daily log) ────────────────

CREATE TABLE daily_log_energy (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL UNIQUE REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_daily_log_energy_user_id ON daily_log_energy(user_id);

CREATE TABLE daily_log_flow (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL UNIQUE REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_daily_log_flow_user_id ON daily_log_flow(user_id);

CREATE TABLE daily_log_collection (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL UNIQUE REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_daily_log_collection_user_id ON daily_log_collection(user_id);

-- ── Multi-select symptom sub-logs (one row per selected option) ──────────────

CREATE TABLE daily_log_emotions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (daily_log_id, option_id)
);

CREATE INDEX idx_daily_log_emotions_user_id ON daily_log_emotions(user_id);

CREATE TABLE daily_log_sleep (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (daily_log_id, option_id)
);

CREATE INDEX idx_daily_log_sleep_user_id ON daily_log_sleep(user_id);

CREATE TABLE daily_log_discharge (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (daily_log_id, option_id)
);

CREATE INDEX idx_daily_log_discharge_user_id ON daily_log_discharge(user_id);

CREATE TABLE daily_log_skin (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (daily_log_id, option_id)
);

CREATE INDEX idx_daily_log_skin_user_id ON daily_log_skin(user_id);

CREATE TABLE daily_log_digestion (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (daily_log_id, option_id)
);

CREATE INDEX idx_daily_log_digestion_user_id ON daily_log_digestion(user_id);

CREATE TABLE daily_log_mind (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id    uuid NOT NULL REFERENCES ref_symptom_options(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (daily_log_id, option_id)
);

CREATE INDEX idx_daily_log_mind_user_id ON daily_log_mind(user_id);

-- ── Sex/intimacy: single encrypted payload (addendum, Option A) ──────────────
-- Highest-sensitivity category. No FK to ref_symptom_options: option IDs live
-- inside the AES-256-GCM ciphertext and are validated in the service layer.

CREATE TABLE daily_log_sex (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id      uuid NOT NULL UNIQUE REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id           uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    encrypted_payload text NOT NULL,  -- base64(iv):base64(ciphertext):base64(authTag)
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_daily_log_sex_user_id ON daily_log_sex(user_id);

-- ── Pain ─────────────────────────────────────────────────────────────────────

CREATE TABLE pain_logs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_log_id uuid NOT NULL UNIQUE REFERENCES daily_logs(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_pain_logs_user_id ON pain_logs(user_id);

CREATE TABLE pain_log_locations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pain_log_id uuid NOT NULL REFERENCES pain_logs(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    location_id uuid NOT NULL REFERENCES ref_pain_locations(id),
    severity    smallint,  -- 1–10 for this location; null = selected, not yet rated
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (pain_log_id, location_id),
    CHECK (severity >= 1 AND severity <= 10)
);

CREATE INDEX idx_pain_log_locations_user_id ON pain_log_locations(user_id);
CREATE INDEX idx_pain_log_locations_pain_log_id ON pain_log_locations(pain_log_id);

-- ── User preferences ─────────────────────────────────────────────────────────

CREATE TABLE user_dashboard_preferences (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    category_order jsonb NOT NULL,  -- ordered array of ref_symptom_categories.slug
    updated_at     timestamptz NOT NULL DEFAULT now()
);
