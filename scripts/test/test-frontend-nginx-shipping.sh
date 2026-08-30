#!/usr/bin/env bash
#
# Guards how the frontend's own nginx configuration reaches production.
#
# The frontend image already bakes frontend/nginx.conf (Dockerfile.frontend), so
# the routing config and the bundle it routes for are built from one commit and
# ship together. docker-compose.prod.yml used to ALSO bind-mount that same file
# from the deploy checkout, which silently won: the container ran whatever the
# host's git working tree happened to contain, regardless of the image.
#
# That is not a theoretical hazard, it has shipped twice. PR #65 added
# `location = /mcp` and every POST to it returned 405 in production. PR #132
# added `location /s/` and every share link - including ones already pasted into
# other people's Slack channels - fell through to the SPA and rendered its 404.
# Both times the image was correct and the mount masked it, and both times the
# deploy reported success, because a deploy whose config sync declines still
# pulls and recreates images (SyncDecision.deployImagesAnyway).
#
# So the assertions here are: the image must carry the config, and nothing may
# mount over it. A shell test rather than a Java one because the subject is two
# infrastructure files owned by neither Gradle module, and this suite already
# owns nginx and compose assertions (test-nginx-maintenance.sh).
#
# Note there is deliberately no `nginx -t` here: frontend/nginx.conf resolves
# `proxy_pass http://backend:8080` at config-load time, so validating it without
# a running backend on the network fails with "host not found in upstream" -
# a false failure that says nothing about the config.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"
DOCKERFILE="$PROJECT_DIR/Dockerfile.frontend"
NGINX_CONF="$PROJECT_DIR/frontend/nginx.conf"

# The in-container path nginx actually reads. Anything mounted here shadows the
# image's copy.
CONF_TARGET="/etc/nginx/conf.d/default.conf"

failures=0
checks=0

check() {
  local description="$1" condition="$2"
  checks=$((checks + 1))
  if eval "$condition"; then
    echo "    ok: $description"
  else
    failures=$((failures + 1))
    echo "    FAIL: $description"
  fi
}

# Extracts one top-level compose service's mapping: every line after its
# "  <name>:" header, up to the next header at the same indentation.
#
# Comment lines are dropped, and that is load-bearing rather than tidy. The
# comment explaining why the frontend has no mount naturally names the very path
# the assertion below greps for, so a scan over raw lines fails on its own
# rationale - which reads as "the mount is back" and teaches the next person to
# delete the explanation. Same reason DeployerLinearCredentialTest skips them.
service_block() {
  local name="$1"
  awk -v svc="  $name:" '
    $0 == svc { inside = 1; next }
    inside && /^  [a-zA-Z0-9_-]+:[[:space:]]*$/ { exit }
    inside && $0 ~ /^[[:space:]]*#/ { next }
    inside { print }
  ' "$COMPOSE_FILE"
}

# ---------------------------------------------------------------------------
echo "  the subjects exist"
# ---------------------------------------------------------------------------
# Without these, every assertion below would pass by reading nothing.
check "docker-compose.prod.yml exists" "[[ -f '$COMPOSE_FILE' ]]"
check "Dockerfile.frontend exists" "[[ -f '$DOCKERFILE' ]]"
check "frontend/nginx.conf exists" "[[ -f '$NGINX_CONF' ]]"

FRONTEND_BLOCK="$(service_block frontend)"
check "the compose file has a non-empty \`frontend:\` service block" \
  "[[ -n \"\$FRONTEND_BLOCK\" ]]"
check "the extracted block is really frontend's, not a neighbour's" \
  "grep -q 'simonrowe-dev-monorepo-frontend' <<<\"\$FRONTEND_BLOCK\""

# ---------------------------------------------------------------------------
echo "  the image carries the routing config"
# ---------------------------------------------------------------------------
check "Dockerfile.frontend copies frontend/nginx.conf to $CONF_TARGET" \
  "grep -qE '^COPY[[:space:]]+frontend/nginx\.conf[[:space:]]+$CONF_TARGET[[:space:]]*\$' '$DOCKERFILE'"

# ---------------------------------------------------------------------------
echo "  nothing shadows it in production"
# ---------------------------------------------------------------------------
# The negative assertion this file exists for. A bind mount here means the
# running container's routing comes from the deploy host's git tree instead of
# from the image, and a stale tree then breaks routes that CI proved correct.
check "the \`frontend\` service mounts nothing over $CONF_TARGET" \
  "! grep -q '$CONF_TARGET' <<<\"\$FRONTEND_BLOCK\""

# The proxy is stock nginx:alpine with no image of its own, so its conf HAS to be
# bind-mounted. Asserting that keeps the check above honest: it is specifically
# about the config that has an image to live in, not a blanket ban on mounts.
NGINX_BLOCK="$(service_block nginx)"
check "the \`nginx\` proxy still bind-mounts its own conf, which has no image" \
  "grep -q 'nginx-proxy.conf:$CONF_TARGET' <<<\"\$NGINX_BLOCK\""

# ---------------------------------------------------------------------------
echo "  every backend path the SPA fronts is routed"
# ---------------------------------------------------------------------------
# A route missing from this file does not error anywhere: it falls through
# `location /` to try_files /index.html, so the SPA renders and then 404s (GET)
# or nginx returns 405 (POST to a static file). Both read as an application bug.
for location in '/api/' '/s/' '/uploads/' '/ws/'; do
  check "frontend/nginx.conf routes $location to the backend" \
    "grep -qF 'location $location' '$NGINX_CONF'"
done
check "frontend/nginx.conf routes the exact path /mcp to the backend" \
  "grep -qF 'location = /mcp' '$NGINX_CONF'"

# ---------------------------------------------------------------------------
echo
if [[ "$failures" -gt 0 ]]; then
  echo "  $failures of $checks checks failed"
  exit 1
fi
echo "  all $checks checks passed"
