#!/usr/bin/env bash
# ===========================================================================
# AI SQL Analyst — database initialization wrapper (Change 8, task 2.2).
#
# Placed in the PostgreSQL image's /docker-entrypoint-initdb.d. The official
# postgres entrypoint runs files there in lexicographic order; the "01-" prefix
# makes this the orchestrator. It:
#   1. Runs schema.sql  -> e-commerce schema + interactions table.   (Req 2.1, 2.2)
#   2. Runs seed.sql    -> >=2000 rows across the allowlisted tables. (Req 2.3)
#   3. Creates read_only_role with the password from $RO_DB_PASSWORD. (Req 2.4)
#   4. Grants SELECT only on the four allowlisted tables and withholds
#      every privilege on interactions.                              (Req 2.4, 2.5)
#
# Secrets: the read-only role password is read from the RO_DB_PASSWORD
# environment variable. It is NEVER hardcoded here, defaulted, or echoed. It is
# handed to psql through a psql variable (--set) and applied via quote_literal,
# so it does not get interpolated into this committed file or into shell logs.
# ===========================================================================
set -euo pipefail

# --- Fail fast if the read-only role password was not supplied. -------------
if [[ -z "${RO_DB_PASSWORD:-}" ]]; then
    echo "ERROR: RO_DB_PASSWORD is not set. Set the RO_DB_PASSWORD environment variable" >&2
    echo "       to the password for the read-only database role before initializing." >&2
    exit 1
fi

# Resolve paths relative to this script so it works wherever it is mounted
# (e.g. /docker-entrypoint-initdb.d) rather than depending on the CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# schema.sql and seed.sql live in a sql/ subdirectory, NOT next to this script.
# This is deliberate: the postgres entrypoint scans the TOP level of
# /docker-entrypoint-initdb.d and would otherwise run schema.sql itself after
# this wrapper. Because schema.sql begins with DROP TABLE ... CASCADE, that
# second run would recreate the tables and silently wipe the grants we set up
# here. Keeping them one level down means only this wrapper executes them.
SQL_DIR="$SCRIPT_DIR/sql"

# Standard entrypoint-provided values; default to postgres for a bare run.
DB_USER="${POSTGRES_USER:-postgres}"
DB_NAME="${POSTGRES_DB:-postgres}"

# Name of the read-only login role. Chosen to match the task text; the backend
# Read_Only_Datasource authenticates as this role.
RO_ROLE="read_only_role"

# psql against the local socket as the superuser the entrypoint set up.
# ON_ERROR_STOP=1 makes any SQL error abort the whole init.
run_psql() {
    psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$DB_USER" --dbname "$DB_NAME" "$@"
}

echo "[01-init] Applying sql/schema.sql ..."
run_psql --file "$SQL_DIR/schema.sql"

echo "[01-init] Applying sql/seed.sql ..."
run_psql --file "$SQL_DIR/seed.sql"

echo "[01-init] Provisioning read-only role '$RO_ROLE' and grants ..."
# The password is passed as a psql variable (ro_pwd). We never splice it into a
# SQL string here; quote_literal() in the DO block safely quotes/escapes it so
# it cannot break out of the statement and never lands in this file.
run_psql \
    --set=ro_role="$RO_ROLE" \
    --set=db_name="$DB_NAME" \
    --set=ro_pwd="$RO_DB_PASSWORD" <<'SQL'
-- Stash the role name and password in session-local GUCs. psql substitutes
-- :'var' here (plain SQL, outside any dollar-quoting), and set_config keeps the
-- password out of the statement text that the DO block below executes.
SELECT set_config('aisql.ro_role', :'ro_role', false);
SELECT set_config('aisql.ro_pwd',  :'ro_pwd',  false);

-- Idempotent role creation: create on first run, otherwise just keep the
-- password in sync so re-initialization never errors (Req 2.4). The values are
-- read back via current_setting() because psql does NOT substitute :'var'
-- inside a dollar-quoted ($$...$$) body.
DO $$
DECLARE
    v_role text := current_setting('aisql.ro_role');
    v_pwd  text := current_setting('aisql.ro_pwd');
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', v_role, v_pwd);
    ELSE
        EXECUTE format('CREATE ROLE %I WITH LOGIN PASSWORD %L', v_role, v_pwd);
    END IF;
END
$$;

-- Database- and schema-level access. CONNECT lets the role log in to this DB;
-- USAGE lets it resolve objects in the public schema.
GRANT CONNECT ON DATABASE :"db_name" TO :"ro_role";
GRANT USAGE ON SCHEMA public TO :"ro_role";

-- Deny-by-default: strip any table privileges first, then grant SELECT on
-- exactly the four allowlisted tables. interactions is NEVER granted, so it
-- stays unreadable/unwritable by the role (Req 2.5).
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM :"ro_role";

GRANT SELECT ON customers   TO :"ro_role";
GRANT SELECT ON products    TO :"ro_role";
GRANT SELECT ON orders      TO :"ro_role";
GRANT SELECT ON order_items TO :"ro_role";

-- Defensive: explicitly withhold every privilege on interactions and prevent
-- the role from creating objects in the public schema.
REVOKE ALL ON interactions FROM :"ro_role";
REVOKE CREATE ON SCHEMA public FROM :"ro_role";

-- Ensure future tables created by this superuser do not silently grant the
-- read-only role anything.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM :"ro_role";
SQL

echo "[01-init] Done. '$RO_ROLE' can SELECT the four allowlisted tables only."
