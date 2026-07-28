#!/bin/sh
set -eu

: "${POSTGRES_SEEDS:?POSTGRES_SEEDS is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${SQL_PASSWORD:?SQL_PASSWORD is required}"

database_port=${DB_PORT:-5432}

setup_database() {
  database_name=$1
  schema_directory=$2

  # setup-schema is idempotent: an initialized database reports its newer
  # current version and skips the 0.0 setup.
  temporal-sql-tool \
    --plugin postgres12 \
    --ep "$POSTGRES_SEEDS" \
    -u "$POSTGRES_USER" \
    -p "$database_port" \
    --db "$database_name" \
    setup-schema -v 0.0

  temporal-sql-tool \
    --plugin postgres12 \
    --ep "$POSTGRES_SEEDS" \
    -u "$POSTGRES_USER" \
    -p "$database_port" \
    --db "$database_name" \
    update-schema -d "$schema_directory"
}

echo "Waiting for PostgreSQL..."
nc -z -w 10 "$POSTGRES_SEEDS" "$database_port"

setup_database \
  temporal \
  /etc/temporal/schema/postgresql/v12/temporal/versioned
setup_database \
  temporal_visibility \
  /etc/temporal/schema/postgresql/v12/visibility/versioned

echo "Temporal PostgreSQL schemas are current"
