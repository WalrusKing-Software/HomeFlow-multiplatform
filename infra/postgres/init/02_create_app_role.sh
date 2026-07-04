#!/bin/sh
# Runs once on first boot, after the .sql scripts. Creates the restricted runtime
# role used by the Ktor server (Exposed/Hikari): DML only, no DDL. Flyway
# migrations run as the superuser instead, so the app role never needs to create
# tables. A shell script (not .sql) so it can read the role credentials from the
# environment rather than hard-coding them.
set -e

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER must be set}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set}"
: "${POSTGRES_DB:?POSTGRES_DB must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
	DO \$\$
	BEGIN
	    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${POSTGRES_APP_USER}') THEN
	        CREATE ROLE "${POSTGRES_APP_USER}" LOGIN PASSWORD '${POSTGRES_APP_PASSWORD}';
	    END IF;
	END
	\$\$;

	GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${POSTGRES_APP_USER}";
	GRANT USAGE ON SCHEMA public TO "${POSTGRES_APP_USER}";

	-- DML on every existing table/sequence (none yet — created by Flyway later).
	GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${POSTGRES_APP_USER}";
	GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${POSTGRES_APP_USER}";

	-- And on everything Flyway (run as the superuser) creates from now on.
	ALTER DEFAULT PRIVILEGES IN SCHEMA public
	    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${POSTGRES_APP_USER}";
	ALTER DEFAULT PRIVILEGES IN SCHEMA public
	    GRANT USAGE, SELECT ON SEQUENCES TO "${POSTGRES_APP_USER}";
SQL

echo "Created restricted app role '${POSTGRES_APP_USER}' on database '${POSTGRES_DB}'."
