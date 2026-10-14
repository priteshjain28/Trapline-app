#!/usr/bin/env bash
# Runs every migration and test against a throwaway local Postgres (no Supabase, no Docker).
#   PGHOST/PGPORT/PGUSER pick the server; defaults suit a local socket at /var/tmp/sitepg:54329.
set -euo pipefail
cd "$(dirname "$0")"
export PGHOST="${PGHOST:-/var/tmp/sitepg}" PGPORT="${PGPORT:-54329}" PGUSER="${PGUSER:-postgres}"
DB="site_test_$$"
psql -q -v ON_ERROR_STOP=1 -d postgres -c "create database $DB" >/dev/null
trap 'psql -q -d postgres -c "drop database if exists $DB" >/dev/null' EXIT
run() { psql -q -At -v ON_ERROR_STOP=1 -d "$DB" "$@"; }
run -f tests/local_auth_stub.sql
for m in migrations/*.sql; do run -f "$m"; done
run -f tests/smoke.sql
run -f tests/server_rules.sql
run -f tests/org_assets.sql
# seed goes into its own fresh database so it can't hide a test problem
psql -q -v ON_ERROR_STOP=1 -d postgres -c "drop database $DB" >/dev/null
psql -q -v ON_ERROR_STOP=1 -d postgres -c "create database $DB" >/dev/null
run -f tests/local_auth_stub.sql
for m in migrations/*.sql; do run -f "$m"; done
run -f seed.sql
run -c "select 'seed: ' || (select count(*) from projects) || ' projects, ' || (select count(*) from tasks) || ' tasks, ' || (select count(*) from attendance) || ' attendance records'"
