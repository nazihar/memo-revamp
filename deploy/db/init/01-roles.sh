#!/bin/sh
# Creates the database roles and schema (AGENTS.md §3.2). The same script runs in:
#   - the local Compose stack (docker-entrypoint-initdb.d),
#   - Testcontainers (backend tests),
#   - on-prem installs (a DBA runs it once with psql).
# Roles:
#   memo_migrator  owns the schema; used only by Flyway.
#   memo_app       the application; NOT the owner, NOBYPASSRLS, DML only.
# Required env: MEMO_MIGRATOR_PASSWORD, MEMO_APP_PASSWORD. Optional: MEMO_DB (default: memo).
set -eu
: "${MEMO_MIGRATOR_PASSWORD:?}"
: "${MEMO_APP_PASSWORD:?}"
DB="${MEMO_DB:-${POSTGRES_DB:-memo}}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname "$DB" \
  -v migrator_pw="$MEMO_MIGRATOR_PASSWORD" -v app_pw="$MEMO_APP_PASSWORD" -v db="$DB" <<'SQL'
CREATE ROLE memo_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'migrator_pw';
CREATE ROLE memo_app      LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'app_pw';

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO memo_migrator, memo_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA memo AUTHORIZATION memo_migrator;
GRANT USAGE ON SCHEMA memo TO memo_app;
ALTER ROLE memo_app      SET search_path = memo;
ALTER ROLE memo_migrator SET search_path = memo;

-- Everything Flyway creates is usable (DML only) by the app role; no DDL, no TRUNCATE, no ownership.
ALTER DEFAULT PRIVILEGES FOR ROLE memo_migrator IN SCHEMA memo GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO memo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE memo_migrator IN SCHEMA memo GRANT USAGE, SELECT ON SEQUENCES TO memo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE memo_migrator IN SCHEMA memo GRANT EXECUTE ON FUNCTIONS TO memo_app;
SQL
