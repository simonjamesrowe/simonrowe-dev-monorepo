# Langfuse Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Langfuse into the infrastructure for observability via Docker Compose, Auth0, and Grafana Alloy.

**Architecture:** We are adding Langfuse and its PostgreSQL database to the Docker Compose stack, configuring Auth0 for SSO, and routing traces from the backend through Grafana Alloy to Langfuse.

**Tech Stack:** Docker Compose, PostgreSQL, Auth0, Grafana Alloy.

## Global Constraints

- No backend codebase changes are permitted for telemetry routing.
- The callback URL for Auth0 must be exactly `https://langfuse.simonrowe.dev/api/auth/callback/auth0`.
- The database for Langfuse must be PostgreSQL 15.

---

### Task 1: Update `.env.example`

**Files:**
- Modify: `.env.example`

**Interfaces:**
- Consumes: None
- Produces: Environment variables for Auth0 and Langfuse encryption used by the `langfuse` container.

- [ ] **Step 1: Add Langfuse Auth0 and configuration variables to `.env.example`**

Update `.env.example` to append the following at the end of the file:

```env
# ------------------------------------------------------------------------------
# Langfuse & Auth0 Configuration
# ------------------------------------------------------------------------------
# Required for Langfuse Single Sign-On
AUTH_AUTH0_CLIENT_ID=your_auth0_client_id
AUTH_AUTH0_CLIENT_SECRET=your_auth0_client_secret
AUTH_AUTH0_ISSUER_BASE_URL=https://your-tenant.auth0.com

# Langfuse Security Configuration
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=your_nextauth_secret
SALT=your_salt
ENCRYPTION_KEY=your_encryption_key
```

- [ ] **Step 2: Commit**

```bash
git add .env.example
git commit -m "chore: add langfuse and auth0 env vars to .env.example"
```

### Task 2: Update Local Docker Compose (`docker-compose.yml`)

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: Environment variables from `.env`
- Produces: Running `langfuse-db` and `langfuse` services on the local Docker network.

- [ ] **Step 1: Add `langfuse-db` and `langfuse` services and `langfuse-db-data` volume**

Add the following to `services:` in `docker-compose.yml`:

```yaml
  langfuse-db:
    image: postgres:15
    environment:
      - POSTGRES_USER=postgres
      - POSTGRES_PASSWORD=postgres
      - POSTGRES_DB=langfuse
    volumes:
      - langfuse-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

  langfuse:
    image: langfuse/langfuse:2.82.2
    depends_on:
      langfuse-db:
        condition: service_healthy
    ports:
      - "3000:3000"
    environment:
      - DATABASE_URL=postgresql://postgres:postgres@langfuse-db:5432/langfuse
      - NEXTAUTH_URL=${NEXTAUTH_URL:-http://localhost:3000}
      - NEXTAUTH_SECRET=${NEXTAUTH_SECRET}
      - SALT=${SALT}
      - ENCRYPTION_KEY=${ENCRYPTION_KEY}
      - AUTH_AUTH0_CLIENT_ID=${AUTH_AUTH0_CLIENT_ID}
      - AUTH_AUTH0_CLIENT_SECRET=${AUTH_AUTH0_CLIENT_SECRET}
      - AUTH_AUTH0_ISSUER_BASE_URL=${AUTH_AUTH0_ISSUER_BASE_URL}
```

Add the `langfuse-db-data` volume to the `volumes:` section at the bottom of the file:

```yaml
  langfuse-db-data:
```

- [ ] **Step 2: Verify `docker-compose.yml` syntax**

```bash
docker compose -f docker-compose.yml config > /dev/null
```
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add langfuse services to local docker compose"
```

### Task 3: Update Production Docker Compose (`docker-compose.prod.yml`)

**Files:**
- Modify: `docker-compose.prod.yml`

**Interfaces:**
- Consumes: Environment variables from `.env`
- Produces: Running `langfuse-db` and `langfuse` services on the production Docker network.

- [ ] **Step 1: Add `langfuse-db` and `langfuse` services and `langfuse-db-data` volume**

Add the following to `services:` in `docker-compose.prod.yml`:

```yaml
  langfuse-db:
    image: postgres:15
    restart: unless-stopped
    environment:
      - POSTGRES_USER=postgres
      - POSTGRES_PASSWORD=postgres
      - POSTGRES_DB=langfuse
    volumes:
      - langfuse-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

  langfuse:
    image: langfuse/langfuse:2.82.2
    restart: unless-stopped
    depends_on:
      langfuse-db:
        condition: service_healthy
    env_file:
      - .env
    environment:
      - DATABASE_URL=postgresql://postgres:postgres@langfuse-db:5432/langfuse
```

Add the `langfuse-db-data` volume to the `volumes:` section at the bottom of the file:

```yaml
  langfuse-db-data:
```

- [ ] **Step 2: Verify `docker-compose.prod.yml` syntax**

```bash
docker compose -f docker-compose.prod.yml config > /dev/null
```
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "feat: add langfuse services to prod docker compose"
```

### Task 4: Configure OpenTelemetry Routing in Grafana Alloy

**Files:**
- Modify: `config/alloy/config.alloy`

**Interfaces:**
- Consumes: OTLP traces from the backend.
- Produces: Traces exported to both `tempo` and `langfuse`.

- [ ] **Step 1: Add Langfuse OTLP HTTP exporter**

Add the following to the end of `config/alloy/config.alloy`:

```alloy
// ---------------------
// Langfuse Trace Exporter
// ---------------------
otelcol.exporter.otlphttp "langfuse" {
  client {
    endpoint = "http://langfuse:3000/api/public/otel"
  }
}
```

- [ ] **Step 2: Update Batch Processor to fork traces**

Modify the `otelcol.processor.batch "default"` block in `config/alloy/config.alloy` to include the langfuse exporter. Change it from:

```alloy
// ---------------------
// Batch Processor
// ---------------------
otelcol.processor.batch "default" {
  output {
    traces = [otelcol.exporter.otlphttp.tempo.input]
  }
}
```

To:

```alloy
// ---------------------
// Batch Processor
// ---------------------
otelcol.processor.batch "default" {
  output {
    traces = [otelcol.exporter.otlphttp.tempo.input, otelcol.exporter.otlphttp.langfuse.input]
  }
}
```

- [ ] **Step 3: Verify Alloy configuration**

```bash
docker run --rm -v $(pwd)/config/alloy/config.alloy:/etc/alloy/config.alloy grafana/alloy fmt /etc/alloy/config.alloy > /dev/null
```
Expected: No errors.

- [ ] **Step 4: Commit**

```bash
git add config/alloy/config.alloy
git commit -m "feat: route opentelemetry traces to langfuse via alloy"
```

### Task 5: Update Auth0 Documentation

**Files:**
- Modify: `docs/auth0-setup.md`

**Interfaces:**
- Consumes: None
- Produces: Updated documentation for setting up the Langfuse application in Auth0.

- [ ] **Step 1: Add Langfuse Auth0 setup instructions**

Append the following content to `docs/auth0-setup.md`:

```markdown
## Langfuse Single Sign-On (SSO)

Langfuse requires an Auth0 Application to manage user access via SSO.

1. In the Auth0 Dashboard, go to **Applications** > **Applications**.
2. Click **Create Application**.
3. Set the name to **Langfuse** and select **Regular Web Applications**.
4. Click **Create**.
5. Go to the **Settings** tab.
6. In **Allowed Callback URLs**, add:
   - `http://localhost:3000/api/auth/callback/auth0`
   - `https://langfuse.simonrowe.dev/api/auth/callback/auth0`
7. Click **Save Changes** at the bottom.
8. Copy the **Client ID**, **Client Secret**, and **Domain** from the top of the Settings tab.
9. Add these to your `.env` file as:
   - `AUTH_AUTH0_CLIENT_ID`
   - `AUTH_AUTH0_CLIENT_SECRET`
   - `AUTH_AUTH0_ISSUER_BASE_URL` (format: `https://YOUR_DOMAIN`)
```

- [ ] **Step 2: Commit**

```bash
git add docs/auth0-setup.md
git commit -m "docs: add auth0 setup instructions for langfuse"
```
