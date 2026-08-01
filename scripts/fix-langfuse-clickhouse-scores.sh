#!/usr/bin/env bash
set -euo pipefail

# One-off remediation for the Langfuse "Scores" page 500ing in production.
#
# Root cause: langfuse-clickhouse ran the unpinned `clickhouse/clickhouse-server` image,
# which resolved to 26.7.1.1315 on its last recreation. That version's lazy-materialization
# behavior breaks Langfuse 3.212.0's scores.all query (langfuse/langfuse#14065, confirmed
# workaround in #13809). Scoring itself (LangfuseScoreClient) was unaffected — scores were
# being written the whole time, only the Scores list view in the UI could not read them back.
#
# This script is idempotent: safe to re-run, and it no-ops if already applied. It patches
# docker-compose.prod.yml directly (rather than requiring a `git pull` first) so the fix can
# land immediately; the same two edits are already committed to the repo, so a later
# `git pull` in this directory will find no diff and merge cleanly.
#
# Run this ON THE PI, from the deploy checkout.

DEPLOY_DIR="${DEPLOY_DIR:-$HOME/workspace/simonjamesrowe/simonrowe-dev-monorepo}"
COMPOSE_FILE="docker-compose.prod.yml"
CLICKHOUSE_PIN="clickhouse/clickhouse-server:26.7.1.1315"

cd "$DEPLOY_DIR"
echo "==> Working in $(pwd)"

if grep -qF "image: clickhouse/clickhouse-server" "$COMPOSE_FILE" && ! grep -qF "$CLICKHOUSE_PIN" "$COMPOSE_FILE"; then
  echo "==> Pinning langfuse-clickhouse image to ${CLICKHOUSE_PIN}"
  sed -i.bak "s|image: clickhouse/clickhouse-server\$|image: ${CLICKHOUSE_PIN}|" "$COMPOSE_FILE"
else
  echo "==> ClickHouse image already pinned — skipping"
fi

if ! grep -qF "CLICKHOUSE_DISABLE_LAZY_MATERIALIZATION" "$COMPOSE_FILE"; then
  echo "==> Adding CLICKHOUSE_DISABLE_LAZY_MATERIALIZATION=true to the langfuse-env anchor"
  sed -i.bak 's|^      REDIS_AUTH: \${REDIS_AUTH}$|      REDIS_AUTH: ${REDIS_AUTH}\n      CLICKHOUSE_DISABLE_LAZY_MATERIALIZATION: "true"|' "$COMPOSE_FILE"
else
  echo "==> CLICKHOUSE_DISABLE_LAZY_MATERIALIZATION already present — skipping"
fi

echo "==> Recreating langfuse-clickhouse, langfuse-worker, langfuse"
docker compose -f "$COMPOSE_FILE" up -d langfuse-clickhouse langfuse-worker langfuse

echo "==> Giving the stack 15s to come back up"
sleep 15
docker compose -f "$COMPOSE_FILE" ps langfuse-clickhouse langfuse-worker langfuse

echo "==> Verifying the Langfuse public scores API still responds"
set -a
# shellcheck disable=SC1091
. ./.env
set +a
curl -sS -u "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" \
  "https://langfuse.simonrowe.dev/api/public/scores?limit=1"
echo
echo "==> Done. Confirm in the browser: https://langfuse.simonrowe.dev/project/simonrowe-dev/scores should load rows instead of 500ing."
