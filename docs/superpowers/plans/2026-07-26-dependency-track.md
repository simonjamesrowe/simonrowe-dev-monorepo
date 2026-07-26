# Dependency-Track Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run Dependency-Track 5.0.3 at `https://dependency-track.simonrowe.dev`, secured by the existing Auth0 tenant, receiving four SBOMs from GitHub Actions on every merge to `main`.

**Architecture:** Two new containers (`dependencytrack-apiserver`, `dependencytrack-frontend`) in `docker-compose.prod.yml`, backed by a new database inside the existing `langfuse-db` Postgres, fronted by a new nginx server block that splits `/api/*` to the API server and everything else to the frontend. Authentication uses Dependency-Track's native OIDC pointed at the existing Auth0 tenant, reusing the `DEV_PORTAL_ADMIN` role and `https://simonrowe.dev/roles` claim that Langfuse already uses. A new `sbom` job in `publish.yml` generates and uploads SBOMs for backend dependencies, frontend dependencies, and both container images.

**Tech Stack:** Dependency-Track 5.0.3, PostgreSQL 15, nginx:alpine, Auth0 (OIDC), GitHub Actions, CycloneDX Gradle plugin 2.1.0, `@cyclonedx/cyclonedx-npm` 6.0.0, Syft via `anchore/sbom-action`.

**Spec:** `docs/superpowers/specs/2026-07-26-dependency-track-design.md`

## Global Constraints

- Dependency-Track images are pinned to **`5.0.3`**. Never use `latest` — on `dependencytrack/apiserver` it resolves to `4.14.3`, a different major line.
- Dependency-Track v5 configuration uses **`DT_*`** environment variables, not `ALPINE_*`. The `ALPINE_*` names from v4 documentation are silently ignored by v5.
- The database property prefix is **`dt.datasource.*`** (→ `DT_DATASOURCE_*`), not `dt.database.*`.
- The Auth0 issuer value **must include a trailing slash**: `https://<tenant>.auth0.com/`. Dependency-Track does strict string equality against the discovery document; a mismatch silently disables the login button.
- `OIDC_SCOPE` **must be set explicitly** on the frontend container. Unset becomes `null` via the entrypoint's `jq` assignment, which silently removes the login button.
- The Dependency-Track team name must match the claim value **exactly, including case**: `DEV_PORTAL_ADMIN`.
- No credential values ever appear in committed files. All secrets come from `.env` (sourced from `~/workspace/simonjamesrowe/env`) or GitHub Actions secrets.
- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`). No Jira references. No Claude attribution.
- **SSH access to the Pi exists** (`ssh simonrowe@192.168.4.66`, LAN only; credentials in the usual store). This corrects an earlier assumption in this document that there was none. Recovery from a bad deploy is therefore possible directly, which softens — but does not remove — the nginx risk below.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `config/nginx/nginx-proxy.conf` | Modify | Add resolver + variable upstreams; add the Dependency-Track server block |
| `docker-compose.prod.yml` | Modify | Add `dependencytrack-db-init`, `dependencytrack-apiserver`, `dependencytrack-frontend` |
| `.env.example` | Modify | Document the new required variables |
| `frontend/package.json` | Modify | Add `@cyclonedx/cyclonedx-npm` dev dependency and an `sbom` script |
| `.github/workflows/publish.yml` | Modify | Add the `sbom` job |
| `docs/auth0-setup.md` | Modify | Add a Dependency-Track SSO section |
| `docs/runbooks/dependency-track.md` | Create | Operations: key rotation, manual upload, break-glass recovery |
| `CLAUDE.md` | Modify | New hostname, `langfuse-db` coupling, retire the nginx restart gotcha |

---

## Task 1: Make nginx boot-resilient

This task is independently valuable and **should ship as its own PR, merged and verified in production before any Dependency-Track work is deployed.** It removes an existing landmine rather than adding a feature. If it regresses, it takes the entire public site and Portainer offline on a host with no SSH — so it is isolated deliberately.

**Files:**
- Modify: `config/nginx/nginx-proxy.conf:26-110`
- Modify: `docker-compose.prod.yml:139-157` (nginx service `depends_on`)

**Interfaces:**
- Consumes: nothing.
- Produces: an nginx config that starts successfully with any subset of upstreams running. Task 3 adds a fifth server block on the same pattern.

**Background the implementer needs:** nginx resolves hostnames in a static `proxy_pass http://name:port` **once, at startup**, and refuses to start if any fails to resolve. Using a variable in the `proxy_pass` value defers resolution to request time, which requires an explicit `resolver`. Docker's embedded DNS server is always at `127.0.0.11`.

One behavioural caveat: when `proxy_pass` contains a variable and **no** URI component, nginx forwards the original request URI unchanged — which is what we want. All five locations here are `location /`, so no URI rewriting is involved and the change is behaviour-preserving. Do not add a trailing `/` to any `proxy_pass` value.

- [ ] **Step 1: Add the resolver directive**

At the top of `config/nginx/nginx-proxy.conf`, immediately after the `access_log` line (currently line 24), add:

```nginx

# -----------------------------------------------------------------------------
# Runtime upstream resolution
# -----------------------------------------------------------------------------
# nginx resolves static `proxy_pass http://name:port` targets once at startup and
# ABORTS if any fails to resolve. With six upstreams that made a single stopped
# container prevent nginx booting at all — which also took Portainer offline,
# since it is only reachable through this proxy.
#
# Using a variable in proxy_pass defers resolution to request time, so nginx
# always starts and returns 502 for whichever upstream is actually down.
# 127.0.0.11 is Docker's embedded DNS server.
resolver 127.0.0.11 valid=10s ipv6=off;
```

- [ ] **Step 2: Convert the frontend block**

Replace `config/nginx/nginx-proxy.conf:30-36` with:

```nginx
    location / {
        set $upstream_frontend frontend;
        proxy_pass http://$upstream_frontend:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
```

- [ ] **Step 3: Convert the backend block**

Replace `config/nginx/nginx-proxy.conf:43-54` with:

```nginx
    location / {
        set $upstream_backend backend;
        proxy_pass http://$upstream_backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket upgrade support (for /ws/chat STOMP endpoint)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
```

- [ ] **Step 4: Convert the Portainer block**

This block carries CORS handling, an `Origin` reset and WebSocket upgrade headers. Every one of them must survive. Replace only the `proxy_pass` line at `config/nginx/nginx-proxy.conf:74`, leaving lines 62-72 and 75-90 untouched:

```nginx
        set $upstream_portainer portainer;
        proxy_pass http://$upstream_portainer:9000;
```

- [ ] **Step 5: Convert the Langfuse block**

Replace `config/nginx/nginx-proxy.conf:98-109` with:

```nginx
    location / {
        set $upstream_langfuse langfuse;
        proxy_pass http://$upstream_langfuse:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket upgrade support
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
```

- [ ] **Step 6: Relax the nginx `depends_on` conditions**

The resolver fix handles a lone `docker restart nginx`, but `docker compose up -d` still refuses to start nginx while any upstream is unhealthy — the same landmine by a different route. In `docker-compose.prod.yml:145-154`, change every `condition: service_healthy` under the nginx service to `condition: service_started`:

```yaml
    depends_on:
      frontend:
        condition: service_started
      backend:
        condition: service_started
      portainer:
        condition: service_started
      langfuse:
        condition: service_started
```

Ordering is preserved; the hard health gate is not. This is the intended trade — nginx serving 502 for one host beats nginx not serving at all.

- [ ] **Step 7: Validate the config syntactically**

```bash
docker run --rm -v "$PWD/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t
```

Expected: `syntax is ok` and `test is successful`.

Note this validates syntax only — it does not resolve upstreams, which is precisely the property being added.

- [ ] **Step 8: Prove the actual behaviour change locally**

This is the real test of this task, and it must not be skipped. Bring the production compose up on OrbStack, stop an upstream, restart nginx, and confirm nginx still boots.

Per `CLAUDE.md`, running the prod compose on macOS requires `DOCKER_BINARY_PATH=/opt/homebrew/bin/docker` and `DOCKER_PLUGINS_PATH=~/.docker/cli-plugins` in `.env`. Do not set a real `PINGGY_TOKEN` — a second tunnel with the same token will fight production. Comment out the `pinggy` service or leave the token empty for this test.

```bash
docker compose -f docker-compose.prod.yml up -d frontend backend portainer langfuse nginx
docker compose -f docker-compose.prod.yml stop langfuse
docker compose -f docker-compose.prod.yml restart nginx
sleep 3
docker compose -f docker-compose.prod.yml ps nginx
```

Expected: nginx is `running`, **not** `restarting` or `exited`.

Before this change the same sequence produced `host not found in upstream "langfuse"` and a boot failure. Confirm the old behaviour is genuinely gone by checking the logs:

```bash
docker compose -f docker-compose.prod.yml logs nginx | grep -i "host not found" || echo "PASS: no resolution failure"
```

Expected: `PASS: no resolution failure`.

- [ ] **Step 9: Confirm the surviving hosts still serve, and the stopped one 502s**

```bash
docker compose -f docker-compose.prod.yml exec nginx \
  curl -s -o /dev/null -w "frontend=%{http_code}\n" -H "Host: simonrowe.dev" http://localhost/
docker compose -f docker-compose.prod.yml exec nginx \
  curl -s -o /dev/null -w "langfuse=%{http_code}\n" -H "Host: langfuse.simonrowe.dev" http://localhost/
```

Expected: `frontend=200` and `langfuse=502`. A 502 for the stopped upstream is the success condition, not a failure.

- [ ] **Step 10: Restore and tear down**

```bash
docker compose -f docker-compose.prod.yml start langfuse
docker compose -f docker-compose.prod.yml down
```

- [ ] **Step 11: Commit**

```bash
git add config/nginx/nginx-proxy.conf docker-compose.prod.yml
git commit -m "fix: make nginx boot when upstreams are down

nginx resolved all upstream hostnames statically at startup and aborted if any
failed, so one stopped container prevented the proxy booting entirely - taking
Portainer, and therefore the only management UI, offline with it.

Use Docker's embedded DNS resolver with variable proxy_pass targets so
resolution happens per-request, and relax the nginx depends_on conditions from
service_healthy to service_started. nginx now always starts and returns 502 for
whichever upstream is actually down."
```

**Stop here and ship this PR to production before starting Task 2.** Verify all four existing hostnames respond in production before continuing.

---

## Task 2: Add the Dependency-Track containers

**Files:**
- Modify: `docker-compose.prod.yml` (add three services after the `langfuse` service block, before the `volumes:` key at line 356)
- Modify: `.env.example`

**Interfaces:**
- Consumes: the existing `langfuse-db` service and its `LANGFUSE_DB_USER` / `LANGFUSE_DB_PASSWORD` variables.
- Produces: `dependencytrack-apiserver` reachable at `dependencytrack-apiserver:8080` and `dependencytrack-frontend` at `dependencytrack-frontend:8080` on the compose network. Task 3 proxies to both.

**Background the implementer needs:** Dependency-Track v5 is PostgreSQL-only and requires PG 14+; `langfuse-db` is postgres:15, which satisfies this. The API server runs its own schema migrations at startup (`dt.init-task.database-migration.enabled` defaults to `true`), so it only needs an empty database to exist. The image already sets `-XX:MaxRAMPercentage=90.0`, so `mem_limit` alone constrains the heap — do not use `EXTRA_JAVA_OPTIONS`, which has a history of parsing bugs. The API server exposes health on a separate management port, `9000`.

- [ ] **Step 1: Add the database bootstrap service**

The `langfuse-db-data` volume already exists, so Postgres' first-run init scripts will never execute again. The database must therefore be created by an explicit one-shot service. Add to `docker-compose.prod.yml`:

```yaml
  # One-shot: create the Dependency-Track database and role inside the existing
  # langfuse Postgres. The langfuse-db-data volume predates this service, so
  # Postgres' own /docker-entrypoint-initdb.d scripts will never run again -
  # this is the only way to add a database to an initialised cluster.
  # Idempotent: safe to re-run on every `docker compose up`.
  dependencytrack-db-init:
    image: postgres:15
    restart: "no"
    depends_on:
      langfuse-db:
        condition: service_healthy
    environment:
      PGHOST: langfuse-db
      PGUSER: ${LANGFUSE_DB_USER:-postgres}
      PGPASSWORD: ${LANGFUSE_DB_PASSWORD:-postgres}
      DT_DB_PASSWORD: ${DEPENDENCYTRACK_DB_PASSWORD}
    entrypoint:
      - /bin/sh
      - -c
      - |
        set -e
        psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='dtrack'" | grep -q 1 || \
          psql -c "CREATE ROLE dtrack LOGIN PASSWORD '$$DT_DB_PASSWORD'"
        psql -tAc "SELECT 1 FROM pg_database WHERE datname='dtrack'" | grep -q 1 || \
          psql -c "CREATE DATABASE dtrack OWNER dtrack"
        echo "dependency-track database ready"
```

Note the `$$DT_DB_PASSWORD` — the doubled `$` escapes Compose's own variable interpolation so the shell inside the container expands it instead.

- [ ] **Step 2: Add the API server service**

```yaml
  dependencytrack-apiserver:
    image: dependencytrack/apiserver:5.0.3
    restart: unless-stopped
    depends_on:
      dependencytrack-db-init:
        condition: service_completed_successfully
    # The image sets -XX:MaxRAMPercentage=80.0 with no fixed -Xmx, so the heap
    # tracks this limit (~1.6GB here) and the remaining 20% covers off-heap
    # memory, thread stacks and the OS. Do not use EXTRA_JAVA_OPTIONS to set
    # -Xmx: it has a history of argument-parsing bugs, and a fixed heap equal to
    # the cgroup limit is an OOMKill recipe.
    #
    # 2g matches the upstream Helm chart default for v5. The old 4GB startup
    # gate was removed in 4.14.0; v5 documents 2GB as the starting point with a
    # 1GB floor, and the project's own quickstart runs at 1Gi.
    mem_limit: 2g
    environment:
      DT_DATASOURCE_URL: jdbc:postgresql://langfuse-db:5432/dtrack
      DT_DATASOURCE_USERNAME: dtrack
      DT_DATASOURCE_PASSWORD: ${DEPENDENCYTRACK_DB_PASSWORD}
      DT_OIDC_ENABLED: "true"
      DT_OIDC_ISSUER: ${DEPENDENCYTRACK_OIDC_ISSUER}
      DT_OIDC_CLIENT_ID: ${DEPENDENCYTRACK_OIDC_CLIENT_ID}
      DT_OIDC_USERNAME_CLAIM: email
      DT_OIDC_TEAMS_CLAIM: https://simonrowe.dev/roles
      DT_OIDC_USER_PROVISIONING: "true"
      DT_OIDC_TEAM_SYNCHRONIZATION: "true"
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:9000/health/ready || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 10
      start_period: 300s
```

The `start_period: 300s` is generous on purpose: first boot runs schema migrations and begins mirroring vulnerability data on slow ARM cores.

⚠️ The health path `/health/ready` on management port 9000 is inferred from `dt.management.port` in the 5.0.3 reference config. Step 6 verifies it against the running container and this step is corrected if it differs.

- [ ] **Step 3: Add the frontend service**

```yaml
  dependencytrack-frontend:
    image: dependencytrack/frontend:5.0.3
    restart: unless-stopped
    depends_on:
      dependencytrack-apiserver:
        condition: service_started
    environment:
      API_BASE_URL: https://dependency-track.simonrowe.dev
      OIDC_ISSUER: ${DEPENDENCYTRACK_OIDC_ISSUER}
      OIDC_CLIENT_ID: ${DEPENDENCYTRACK_OIDC_CLIENT_ID}
      # MUST be set explicitly. The entrypoint assigns config via jq
      # unconditionally, and an unset variable becomes null - which silently
      # removes the OpenID login button with no error anywhere.
      OIDC_SCOPE: "openid profile email"
      OIDC_FLOW: "code"
      OIDC_LOGIN_BUTTON_TEXT: "Login with Auth0"
```

- [ ] **Step 4: Document the new environment variables**

Append to `.env.example`:

```bash
# --- Dependency-Track -------------------------------------------------------
# Password for the `dtrack` Postgres role created inside the langfuse-db cluster.
DEPENDENCYTRACK_DB_PASSWORD=
# Auth0 issuer. MUST include the trailing slash - Dependency-Track does strict
# string equality against the discovery document, and a mismatch silently
# disables the login button with no error.
DEPENDENCYTRACK_OIDC_ISSUER=https://your-tenant.auth0.com/
# Client ID of the "Dependency-Track" Auth0 Single Page Application.
DEPENDENCYTRACK_OIDC_CLIENT_ID=
```

- [ ] **Step 5: Validate the compose file parses**

```bash
docker compose -f docker-compose.prod.yml config --quiet && echo "PASS: compose valid"
```

Expected: `PASS: compose valid`. If it reports missing variables, add the three new keys to your local `.env` with placeholder values first.

- [ ] **Step 6: Bring the stack up locally and verify boot, then confirm the health path**

```bash
docker compose -f docker-compose.prod.yml up -d langfuse-db dependencytrack-db-init dependencytrack-apiserver dependencytrack-frontend
docker compose -f docker-compose.prod.yml logs dependencytrack-db-init
```

Expected: `dependency-track database ready`, and the service exits 0.

Then wait for the API server and confirm the health endpoint actually exists at the assumed path:

```bash
docker compose -f docker-compose.prod.yml exec dependencytrack-apiserver \
  curl -s -o /dev/null -w "ready=%{http_code}\n" http://localhost:9000/health/ready
```

Expected: `ready=200`.

If this returns 404, discover the real path and correct the healthcheck in Step 2 before continuing:

```bash
docker compose -f docker-compose.prod.yml exec dependencytrack-apiserver \
  curl -s http://localhost:9000/health
```

- [ ] **Step 7: Verify the API server is serving and reports its version**

```bash
docker compose -f docker-compose.prod.yml exec dependencytrack-apiserver \
  curl -s http://localhost:8080/api/version
```

Expected: JSON containing `"version":"5.0.3"`.

- [ ] **Step 8: Verify the schema landed in the right database**

```bash
docker compose -f docker-compose.prod.yml exec langfuse-db \
  psql -U postgres -d dtrack -c "\dt" | head -20
```

Expected: a list of Dependency-Track tables. Confirms the migrations ran against `dtrack` and did not touch the `langfuse` database.

- [ ] **Step 9: Tear down**

```bash
docker compose -f docker-compose.prod.yml down
```

- [ ] **Step 10: Commit**

```bash
git add docker-compose.prod.yml .env.example
git commit -m "feat: add Dependency-Track 5.0.3 to the production stack

Two containers (apiserver + frontend) backed by a new dtrack database inside
the existing langfuse Postgres, created by an idempotent one-shot init service
because the langfuse-db volume predates it and Postgres init scripts no longer
run.

Pinned to 5.0.3 explicitly: the latest tag resolves to 4.14.3, a different
major line. Memory is capped via mem_limit rather than EXTRA_JAVA_OPTIONS,
relying on the image's -XX:MaxRAMPercentage default."
```

---

## Task 3: Route the new hostname through nginx

**Files:**
- Modify: `config/nginx/nginx-proxy.conf` (append after line 110)

**Interfaces:**
- Consumes: `dependencytrack-apiserver:8080` and `dependencytrack-frontend:8080` from Task 2; the `resolver` directive from Task 1.
- Produces: `https://dependency-track.simonrowe.dev` serving the SPA, with `/api/*` reaching the API server.

**Background the implementer needs:** the Dependency-Track SPA and its API share one hostname, split by path. The SPA calls `API_BASE_URL` (set in Task 2 to the same origin), so `/api/*` must reach the API server. The OIDC callback is a **static asset** served by the frontend at `/static/oidc-callback.html`, so it must fall through to the frontend, not the API server.

- [ ] **Step 1: Append the server block**

```nginx

server {
    listen 80;
    server_name dependency-track.simonrowe.dev;

    # Single hostname split by path: the SPA is configured with
    # API_BASE_URL=https://dependency-track.simonrowe.dev, so its API calls come
    # back to this same origin and must be routed to the apiserver.
    #
    # Note /static/* deliberately falls through to the frontend below - the OIDC
    # callback is a static asset at /static/oidc-callback.html, not an API route.
    location /api/ {
        set $upstream_dt_api dependencytrack-apiserver;
        proxy_pass http://$upstream_dt_api:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;

        # SBOM uploads from CI are multipart and can exceed nginx's 1m default.
        client_max_body_size 32m;
    }

    location / {
        set $upstream_dt_web dependencytrack-frontend;
        proxy_pass http://$upstream_dt_web:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

- [ ] **Step 2: Validate syntax**

```bash
docker run --rm -v "$PWD/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t
```

Expected: `syntax is ok` / `test is successful`.

- [ ] **Step 3: Verify routing end to end locally**

```bash
docker compose -f docker-compose.prod.yml up -d
sleep 20
docker compose -f docker-compose.prod.yml exec nginx \
  curl -s -H "Host: dependency-track.simonrowe.dev" http://localhost/api/version
```

Expected: JSON containing `"version":"5.0.3"` — proving the `/api/` split reaches the API server through the proxy.

- [ ] **Step 4: Verify the SPA and the OIDC callback asset both come from the frontend**

```bash
docker compose -f docker-compose.prod.yml exec nginx \
  curl -s -o /dev/null -w "spa=%{http_code}\n" \
  -H "Host: dependency-track.simonrowe.dev" http://localhost/
docker compose -f docker-compose.prod.yml exec nginx \
  curl -s -o /dev/null -w "callback=%{http_code}\n" \
  -H "Host: dependency-track.simonrowe.dev" http://localhost/static/oidc-callback.html
```

Expected: `spa=200` and `callback=200`. A 404 on the callback means the path split is wrong and OIDC login will fail after Auth0 redirects back.

- [ ] **Step 5: Confirm the existing hostnames are unaffected**

```bash
for h in simonrowe.dev api.simonrowe.dev console.simonrowe.dev langfuse.simonrowe.dev; do
  docker compose -f docker-compose.prod.yml exec nginx \
    curl -s -o /dev/null -w "$h=%{http_code}\n" -H "Host: $h" http://localhost/
done
docker compose -f docker-compose.prod.yml down
```

Expected: no 404s and no 502s. Exact codes vary by service (a redirect is fine); what matters is that adding a fifth block did not disturb the other four.

- [ ] **Step 6: Commit**

```bash
git add config/nginx/nginx-proxy.conf
git commit -m "feat: route dependency-track.simonrowe.dev through nginx

Single hostname split by path: /api/ to the apiserver, everything else to the
frontend SPA. /static/ deliberately falls through to the frontend because the
OIDC callback is a static asset, not an API route."
```

---

## Task 4: Wire up Auth0

Most of this task is Auth0 dashboard configuration, which cannot be scripted from this repo. The deliverable in git is the documentation; the verification is a real login.

**Files:**
- Modify: `docs/auth0-setup.md` (append a new section after the Langfuse SSO section, which currently ends at line 238)

**Interfaces:**
- Consumes: the existing `DEV_PORTAL_ADMIN` role, the `https://simonrowe.dev/roles` claim, and the `Add roles to tokens` Post-Login Action documented at `docs/auth0-setup.md:72-105`.
- Produces: values for `DEPENDENCYTRACK_OIDC_ISSUER` and `DEPENDENCYTRACK_OIDC_CLIENT_ID` consumed by Task 2.

**Background the implementer needs:** since v4.3.0 Dependency-Track validates the **ID token** and prefers it over the `/userinfo` endpoint. The existing Action already sets the custom claim on both the ID and access tokens, so Auth0's opaque access tokens are not an obstacle and no new Action is required — only a new client ID added to the existing deny-list.

- [ ] **Step 1: Create the Auth0 application**

In the Auth0 dashboard: **Applications → Create Application**, name `Dependency-Track`, type **Single Page Application**.

In its **Settings**:

| Setting | Value |
|---|---|
| Allowed Callback URLs | `https://dependency-track.simonrowe.dev/static/oidc-callback.html` |
| Allowed Logout URLs | `https://dependency-track.simonrowe.dev` |
| Allowed Web Origins | `https://dependency-track.simonrowe.dev` |

The callback must be the **full path**, not the bare origin. Save, then copy the **Client ID** and **Domain**.

- [ ] **Step 2: Add the client to the existing deny-list Action**

**Actions → Library → `Add roles to tokens` → Secrets** tab: add key `DEPENDENCY_TRACK_CLIENT_ID` with the Client ID from Step 1.

Then edit the Action body, changing only the `protectedClientIds` array:

```js
const protectedClientIds = [
  event.secrets.LANGFUSE_CLIENT_ID, // Langfuse application
  event.secrets.DEPENDENCY_TRACK_CLIENT_ID, // Dependency-Track application
];
```

Click **Deploy**. Users without `DEV_PORTAL_ADMIN` are now rejected by Auth0 before ever reaching Dependency-Track.

- [ ] **Step 3: Set the environment values on the Pi**

Add to the `.env` in the deploy directory. The issuer **must** have a trailing slash:

```bash
DEPENDENCYTRACK_OIDC_ISSUER=https://<tenant>.auth0.com/
DEPENDENCYTRACK_OIDC_CLIENT_ID=<client-id-from-step-1>
DEPENDENCYTRACK_DB_PASSWORD=<generate a strong password>
```

- [ ] **Step 4: Confirm the API server advertises OIDC as available**

This single check catches both the trailing-slash trap and a wrong client ID, and it is much faster than discovering the problem through a browser:

```bash
curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
```

Expected: `true`.

If `false`, the cause is almost always the issuer string not exactly matching Auth0's discovery document. Compare directly:

```bash
curl -s https://<tenant>.auth0.com/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
```

The value in `DEPENDENCYTRACK_OIDC_ISSUER` must match that byte for byte, trailing slash included.

- [ ] **Step 5: Create the Dependency-Track team**

Log in with the local `admin` account. On first login Dependency-Track forces a password change — set a strong password and record it as the break-glass credential.

Go to **Administration → Access Management → Teams → Create Team**, named exactly `DEV_PORTAL_ADMIN`. The name must match the claim value exactly, including case, or team synchronisation silently maps nobody.

Grant it the permissions needed for full portfolio access, including `VIEW_PORTFOLIO`, `VIEW_VULNERABILITY`, `PORTFOLIO_MANAGEMENT` and `SYSTEM_CONFIGURATION`.

- [ ] **Step 6: Verify a real OIDC login**

In a private browser window, open `https://dependency-track.simonrowe.dev`.

Expected: a **Login with Auth0** button is visible. If it is missing, `OIDC_SCOPE` is unset or `/api/v1/oidc/available` is returning false — recheck Step 4.

Log in with your Auth0 identity. Expected: you land in Dependency-Track, and under **Administration → Access Management → Users** your OIDC user exists and is a member of `DEV_PORTAL_ADMIN`.

- [ ] **Step 7: Document it**

Append a `## Dependency-Track Single Sign-On (SSO)` section to `docs/auth0-setup.md`, mirroring the structure of the Langfuse section. It must record: the SPA application type, all three URL settings with the full callback path, the `DEPENDENCY_TRACK_CLIENT_ID` secret and deny-list edit, the three environment variables, and — prominently — the trailing-slash requirement on the issuer, the `OIDC_SCOPE` trap, and the exact-case team name requirement. Include the `/api/v1/oidc/available` check as the quickest diagnostic.

- [ ] **Step 8: Commit**

```bash
git add docs/auth0-setup.md
git commit -m "docs: add Dependency-Track SSO setup

Reuses the existing DEV_PORTAL_ADMIN role and roles claim; no new Action is
needed, only an extra client ID in the existing deny-list. Documents the three
silent-failure traps: issuer trailing slash, unset OIDC_SCOPE, and exact-case
team naming."
```

---

## Task 5: Generate the frontend SBOM

**Files:**
- Modify: `frontend/package.json`

**Interfaces:**
- Consumes: `frontend/package-lock.json`.
- Produces: `npm run sbom` in `frontend/`, writing CycloneDX 1.6 JSON to `frontend/bom.json`. Task 6 uploads this file.

**Background the implementer needs:** `--package-lock-only` generates the SBOM from the lockfile without needing `node_modules` installed, which keeps the CI job fast. `--omit dev` is not applied automatically unless `NODE_ENV=production`, so it is set explicitly — production vulnerability tracking should reflect what ships, not the build toolchain.

- [ ] **Step 1: Add the dev dependency**

```bash
cd frontend && npm install --save-dev @cyclonedx/cyclonedx-npm@6.0.0
```

- [ ] **Step 2: Add the script**

In `frontend/package.json`, add to the `scripts` block after `"lint": "eslint ."`:

```json
    "sbom": "cyclonedx-npm --package-lock-only --omit dev --spec-version 1.6 --output-format JSON --output-file bom.json"
```

Remember to add a comma to the preceding line.

- [ ] **Step 3: Run it**

```bash
cd frontend && npm run sbom
```

Expected: `bom.json` is created with no errors.

- [ ] **Step 4: Verify it is a valid, populated CycloneDX document**

```bash
cd frontend && python3 -c "
import json
b = json.load(open('bom.json'))
assert b['bomFormat'] == 'CycloneDX', b.get('bomFormat')
assert b['specVersion'] == '1.6', b['specVersion']
n = len(b.get('components', []))
assert n > 0, 'no components'
print(f'PASS: CycloneDX {b[\"specVersion\"]}, {n} components')
"
```

Expected: `PASS: CycloneDX 1.6, <n> components` with a plausible count (dozens or more).

- [ ] **Step 5: Ensure the generated SBOM is not committed**

```bash
grep -q "^bom.json$" frontend/.gitignore || echo "bom.json" >> frontend/.gitignore
git check-ignore frontend/bom.json && echo "PASS: ignored"
```

Expected: `PASS: ignored`. The SBOM is a build artifact; it is generated in CI, never committed.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/.gitignore
git commit -m "feat: generate a CycloneDX SBOM for frontend dependencies

Adds an npm run sbom script using cyclonedx-npm against the lockfile.
--omit dev is explicit because it only defaults on when NODE_ENV=production."
```

---

## Task 6: Upload SBOMs from CI

**Files:**
- Modify: `.github/workflows/publish.yml`

**Interfaces:**
- Consumes: `npm run sbom` from Task 5; the existing root `cyclonedxBom` Gradle task; the images pushed by the existing `publish-backend` and `publish-frontend` jobs; a `DEPENDENCYTRACK_API_KEY` repository secret.
- Produces: four projects in Dependency-Track, all at version `main`.

**Background the implementer needs:** the CycloneDX Gradle plugin is already applied at the root project and `ci.yml` already runs `cyclonedxBom`, writing to `build/reports/bom.json` — that output is currently uploaded as a build artifact that nothing consumes. This task does not change `ci.yml`; it adds the delivery step that was missing.

`anchore/sbom-action` runs Syft against an already-pushed registry image, so the container SBOMs describe exactly what ships, including Paketo buildpack layers and the `nginx:alpine` base.

- [ ] **Step 1: Create the API key in Dependency-Track**

**Administration → Access Management → Teams → Create Team**, named `CI Upload`. Grant it exactly three permissions and no more:

- `BOM_UPLOAD`
- `PROJECT_CREATION_UPLOAD`
- `VIEW_PORTFOLIO`

Generate an API key for the team and copy it.

- [ ] **Step 2: Store it as a GitHub secret**

```bash
gh secret set DEPENDENCYTRACK_API_KEY --repo simonjamesrowe/simonrowe-dev-monorepo
```

Paste the key when prompted. Verify:

```bash
gh secret list --repo simonjamesrowe/simonrowe-dev-monorepo | grep DEPENDENCYTRACK_API_KEY
```

- [ ] **Step 3: Verify the key works before wiring CI**

Debugging a bad key through a workflow run is slow. Test it directly:

```bash
curl -s -H "X-Api-Key: <the-key>" https://dependency-track.simonrowe.dev/api/v1/project | head -c 200
```

Expected: a JSON array (`[]` on an empty portfolio). A `401` means the key is wrong; a `403` means the permissions are wrong.

- [ ] **Step 4: Add the sbom job**

Append to `.github/workflows/publish.yml`:

```yaml
  sbom:
    name: Publish SBOMs to Dependency-Track
    runs-on: ubuntu-latest
    needs: [publish-backend, publish-frontend]
    # Never block a deploy on Dependency-Track being reachable. It runs on a
    # Raspberry Pi behind a tunnel and may be down, rebooting, or saturated
    # mid-analysis; none of that should fail a production release.
    continue-on-error: true
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Generate backend SBOM
        run: ./gradlew cyclonedxBom

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json

      - name: Install frontend dependencies
        working-directory: frontend
        run: npm ci

      - name: Generate frontend SBOM
        working-directory: frontend
        run: npm run sbom

      # Registry credentials are required because the ghcr packages are not
      # necessarily public. format must be set explicitly - the action defaults
      # to spdx-json, which Dependency-Track will reject.
      - name: Generate backend image SBOM
        uses: anchore/sbom-action@v0.24.0
        with:
          image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:${{ github.sha }}
          registry-username: ${{ github.actor }}
          registry-password: ${{ secrets.GITHUB_TOKEN }}
          format: cyclonedx-json
          output-file: backend-image-bom.json
          upload-artifact: false
          upload-release-assets: false

      - name: Generate frontend image SBOM
        uses: anchore/sbom-action@v0.24.0
        with:
          image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend:${{ github.sha }}
          registry-username: ${{ github.actor }}
          registry-password: ${{ secrets.GITHUB_TOKEN }}
          format: cyclonedx-json
          output-file: frontend-image-bom.json
          upload-artifact: false
          upload-release-assets: false

      - name: Upload backend dependency SBOM
        uses: DependencyTrack/gh-upload-sbom@v4.1.0
        with:
          serverhostname: dependency-track.simonrowe.dev
          apikey: ${{ secrets.DEPENDENCYTRACK_API_KEY }}
          projectname: simonrowe-dev/backend
          projectversion: main
          bomfilename: build/reports/bom.json
          autocreate: true

      - name: Upload frontend dependency SBOM
        uses: DependencyTrack/gh-upload-sbom@v4.1.0
        with:
          serverhostname: dependency-track.simonrowe.dev
          apikey: ${{ secrets.DEPENDENCYTRACK_API_KEY }}
          projectname: simonrowe-dev/frontend
          projectversion: main
          bomfilename: frontend/bom.json
          autocreate: true

      - name: Upload backend image SBOM
        uses: DependencyTrack/gh-upload-sbom@v4.1.0
        with:
          serverhostname: dependency-track.simonrowe.dev
          apikey: ${{ secrets.DEPENDENCYTRACK_API_KEY }}
          projectname: simonrowe-dev/backend-image
          projectversion: main
          bomfilename: backend-image-bom.json
          autocreate: true

      - name: Upload frontend image SBOM
        uses: DependencyTrack/gh-upload-sbom@v4.1.0
        with:
          serverhostname: dependency-track.simonrowe.dev
          apikey: ${{ secrets.DEPENDENCYTRACK_API_KEY }}
          projectname: simonrowe-dev/frontend-image
          projectversion: main
          bomfilename: frontend-image-bom.json
          autocreate: true
```

- [ ] **Step 5: Confirm the backend SBOM path is correct**

The upload references `build/reports/bom.json`. Verify that is genuinely where the root Gradle task writes:

```bash
./gradlew cyclonedxBom && ls -la build/reports/bom.json
```

Expected: the file exists. If the plugin writes elsewhere, correct `bomfilename` in the backend upload step to match.

- [ ] **Step 6: Validate the workflow syntax**

```bash
gh workflow view publish.yml --repo simonjamesrowe/simonrowe-dev-monorepo >/dev/null 2>&1
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/publish.yml')); print('PASS: valid YAML')"
```

Expected: `PASS: valid YAML`.

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/publish.yml
git commit -m "feat: publish SBOMs to Dependency-Track on merge to main

Four SBOMs per merge: backend and frontend dependencies plus Syft scans of both
published container images, so base-image CVEs are tracked alongside our own
dependencies. All upload to fixed 'main' versions so the portfolio stays at four
projects rather than growing per commit.

The job is continue-on-error: Dependency-Track runs on a Pi behind a tunnel and
must never block a production deploy."
```

- [ ] **Step 8: Verify after the merge lands**

Once merged, watch the run and then confirm all four projects exist with components:

```bash
gh run watch --repo simonjamesrowe/simonrowe-dev-monorepo
curl -s -H "X-Api-Key: <the-key>" \
  https://dependency-track.simonrowe.dev/api/v1/project | \
  python3 -c "
import json,sys
for p in json.load(sys.stdin):
    print(f\"{p['name']:35} {p.get('version'):6} components={p.get('metrics',{}).get('components','?')}\")
"
```

Expected: four projects, each at version `main`, each with a non-zero component count.

A zero component count means the SBOM uploaded but was empty — check the corresponding generation step's logs. Because the job is `continue-on-error`, a failed upload will **not** show as a red build, so this check is the only thing that confirms success.

---

## Task 7: Operational documentation

**Files:**
- Create: `docs/runbooks/dependency-track.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Write the runbook**

Create `docs/runbooks/dependency-track.md` covering, with real commands rather than prose:

- **Architecture**: the two containers, the shared `langfuse-db` Postgres and the `dtrack` database, the nginx path split.
- **Break-glass access**: logging in with the local `admin` account when OIDC is broken, and where that password lives.
- **Diagnosing a missing login button**: check `/api/v1/oidc/available`, then the issuer trailing slash, then `OIDC_SCOPE`.
- **Rotating the CI API key**: regenerate in the UI, `gh secret set DEPENDENCYTRACK_API_KEY`, re-run the last `publish` workflow.
- **Manual SBOM upload** for when CI has silently failed.
- **Restoring after data loss**: DT state is a derived cache — recreate the `dtrack` database and re-run the `publish` workflow. It is deliberately excluded from `scripts/backup.sh`.
- **Memory pressure**: how to spot Dependency-Track causing OOM kills elsewhere (`docker stats`, `dmesg | grep -i oom`) and that `mem_limit` in `docker-compose.prod.yml` is the lever.

- [ ] **Step 2: Update CLAUDE.md**

Three edits:

1. Add `dependency-track.simonrowe.dev → dependencytrack-frontend` (with `/api/` to the apiserver) to the nginx hostname list.
2. **Replace the "⚠️ nginx restart gotcha" paragraph.** It is no longer true after Task 1. Say instead that nginx now uses Docker's DNS resolver with variable upstreams and boots regardless of which upstreams are running, returning 502 for any that are down. Note the historical failure mode briefly so old incident reports still make sense.
3. Note that `langfuse-db` now hosts both the `langfuse` and `dtrack` databases, so it is a shared dependency of two tools.

Also remove the duplicated nginx-restart warning under `# Manual additions` — that file section is maintained in `simonjamesrowe/agent-setup`, so flag to Simon that the upstream copy needs the same correction rather than editing it silently here.

- [ ] **Step 3: Commit**

```bash
git add docs/runbooks/dependency-track.md CLAUDE.md
git commit -m "docs: add Dependency-Track runbook and update stack docs

Retires the nginx restart gotcha, which no longer applies after the resolver
change, and records the langfuse-db coupling."
```

---

## Deployment sequence

Deployment is pull-based: the Pi runs `docker compose` from its own checkout at `~/workspace/simonjamesrowe/simonrowe-dev-monorepo`. SSH access exists (`ssh simonrowe@192.168.4.66`, LAN only), so these can be run directly rather than handed over as a copy-paste block.

Task 1 ships and is verified in production **first, on its own**. Then Tasks 2–4 together, then 5–6.

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
git pull
# Add DEPENDENCYTRACK_DB_PASSWORD, DEPENDENCYTRACK_OIDC_ISSUER and
# DEPENDENCYTRACK_OIDC_CLIENT_ID to .env before this point.
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
curl -s -o /dev/null -w "dt=%{http_code}\n" https://dependency-track.simonrowe.dev/
curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
```

Expected: all containers `running` (with `dependencytrack-db-init` `exited (0)`), `dt=200`, and `true`.

The API server's first boot runs schema migrations and begins mirroring vulnerability data — expect high CPU for an extended period on ARM, and expect the site to feel slower while it runs. Deploy at a quiet time and watch memory:

```bash
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}"
dmesg | grep -i "out of memory" | tail -5
```

Any OOM kill in that output means `mem_limit` on `dependencytrack-apiserver` is too high for this host and must come down.

---

## Open risk carried into implementation

**The heap risk is largely resolved.** The 4 GB figure that shaped the original design was a hard startup gate in the API server, removed in 4.14.0 by [PR #5058](https://github.com/DependencyTrack/dependency-track/pull/5058) on the grounds that "the previous system requirements are no longer accurate." v5's production guide gives **2 GB memory / 4 CPU cores as the starting point** and states that below 1 GB an instance is unlikely to sustain load. The upstream Helm chart defaults to 2Gi and the quickstart runs at 1Gi. This plan's `mem_limit: 2g` is therefore at the documented starting point, not below a minimum.

**PostgreSQL is now the binding constraint, and the plan knowingly violates upstream guidance.** The v5 production guide says to run the database on 8 GB / 4 cores, "do not go below 4 GB and 2 cores even for evaluation workloads," to use a **dedicated host**, and to prefer NVMe. This plan co-locates it on a shared Pi Postgres instance that also serves Langfuse — explicitly the thing the guide warns against.

That is an accepted trade for a four-project portfolio, but two things must be watched after deployment:

1. **Disk growth.** `DEPENDENCYMETRICS_*` uses daily partitions. One operator reported Postgres growing from ~50 GB to ~500 GB in a month on a large portfolio ([discussion #6711](https://github.com/DependencyTrack/dependency-track/discussions/6711)). Four projects should be negligible by comparison, but the growth is unbounded by default and the Pi's disk is not. Check `df -h` and the `dtrack` database size a week after deployment, not just on day one.
2. **CPU contention.** Postgres competing with Elasticsearch and the API server on four ARM cores is the likeliest cause of a slow-feeling site.

**Honest caveat:** nobody outside the project has published a v5 result on Raspberry Pi–class hardware. The 1Gi/2Gi figures are maintainer defaults, not independently validated, and the Docker Hub overview page still shows stale v4-era requirements ("4.5GB minimum") — do not be alarmed by that page, but do not trust it either.

If the API server is OOM-killed during the initial vulnerability mirror, the levers in order of preference are: raise `mem_limit` if the host allows it, disable vulnerability sources that are not needed, then move Dependency-Track off the Pi.
