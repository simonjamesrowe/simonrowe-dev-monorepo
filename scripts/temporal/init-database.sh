#!/bin/sh
set -eu

: "${TEMPORAL_DB_PASSWORD:?TEMPORAL_DB_PASSWORD is required}"

psql --set=temporal_password="$TEMPORAL_DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE temporal LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'temporal')
\gexec

SELECT format('ALTER ROLE temporal LOGIN PASSWORD %L', :'temporal_password')
\gexec

SELECT 'CREATE DATABASE temporal OWNER temporal'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'temporal')
\gexec

SELECT 'CREATE DATABASE temporal_visibility OWNER temporal'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'temporal_visibility')
\gexec
SQL

echo "Temporal database role and databases are ready"
