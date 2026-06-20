-- Runs once on first boot (empty data volume), as the Postgres superuser.
-- The app database (period_tracker) is created by the POSTGRES_DB env var; this
-- adds the separate database Keycloak needs on the same instance.
SELECT 'CREATE DATABASE keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec
