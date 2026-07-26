# Temporal Reviewer Production Runbook

The production reviewer has three deliberately separate trust zones:

1. `reviewer-api` container: accepts signed GitHub webhooks and starts
   Workflows. It has no Claude or GitHub App private key.
2. Temporal/Postgres containers: durable state and the Auth0-protected Web UI.
3. `temporal-reviewer-worker` host service: clones repositories and invokes the
   host-installed Claude binary. It alone has GitHub and Anthropic credentials.

## One-time external setup

### GitHub App

Create a private, organization-owned GitHub App named `simonrowe-code-reviewer`:

- Homepage URL: `https://simonrowe.dev`
- Webhook URL: `https://api.simonrowe.dev/webhooks/github`
- Webhook secret: generate a new random value
- Repository permissions:
  - Contents: read
  - Issues: read and write
  - Pull requests: read
  - Metadata: read (implicit)
- Subscribe to: Pull request
- User authorization: disabled

Install it on the `simonjamesrowe` organization, initially selecting only the
repositories to review. Generate a private key and record the App **Client ID**
(GitHub recommends the client ID as the JWT issuer).

The webhook receiver handles `opened`, `reopened`, `synchronize`, and
`ready_for_review`. Draft pull requests are ignored.

### Auth0

Complete [Temporal UI Single Sign-On](../auth0-setup.md#temporal-ui-single-sign-on-sso).
The Auth0 Post-Login Action must deny the Temporal client unless the user has
`DEV_PORTAL_ADMIN`.

## Secrets

Populate the production `.env`:

```dotenv
GITHUB_WEBHOOK_SECRET=...
REVIEWER_TRIGGER_TOKEN=...
TEMPORAL_DB_PASSWORD=...
TEMPORAL_AUTH0_CLIENT_ID=...
TEMPORAL_AUTH0_CLIENT_SECRET=...
TEMPORAL_AUTH0_ISSUER=https://YOUR_AUTH0_DOMAIN/
REVIEWER_IMAGE=ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-reviewer:COMMIT_SHA
```

The API container does not need `GITHUB_APP_PRIVATE_KEY_PATH`,
`GITHUB_APP_CLIENT_ID`, or `ANTHROPIC_API_KEY`.

After the installer in the next section has created the service account, copy
`config/systemd/reviewer.env.example` to
`/opt/temporal-reviewer/reviewer.env` and populate:

```dotenv
GITHUB_APP_CLIENT_ID=...
GITHUB_APP_PRIVATE_KEY_PATH=/opt/temporal-reviewer/github-app-private-key.pem
ANTHROPIC_API_KEY=...
CLAUDE_COMMAND=/usr/local/bin/claude
```

Never put the PEM contents directly in `.env`, Compose, Temporal inputs, or a
Claude configuration file.

## First deployment

The repository must be current on the Pi and Java 21 plus Claude Code must be
installed on the host.

Validate Compose before changing running services:

```bash
docker compose -f docker-compose.prod.yml config --quiet
```

Start the database initialization, Temporal server, UI, and API:

```bash
docker compose -f docker-compose.prod.yml up -d \
  temporal-db-init \
  temporal-schema-init \
  temporal \
  temporal-create-namespace \
  temporal-ui \
  reviewer-api
docker compose -f docker-compose.prod.yml restart nginx
```

Install the exact same reviewer image as the host worker:

```bash
sudo REVIEWER_IMAGE="$REVIEWER_IMAGE" ./scripts/install-reviewer-worker.sh
sudo install \
  -o root \
  -g temporal-reviewer \
  -m 0640 \
  config/systemd/reviewer.env.example \
  /opt/temporal-reviewer/reviewer.env
sudo install \
  -o root \
  -g temporal-reviewer \
  -m 0640 \
  github-app-private-key.pem \
  /opt/temporal-reviewer/github-app-private-key.pem
sudo editor /opt/temporal-reviewer/reviewer.env
sudo systemctl enable --now temporal-reviewer-worker
```

If the installer created `reviewer.env` before the example copy, edit the
existing file rather than overwriting populated secrets.

## Verification

On the Pi:

```bash
docker compose -f docker-compose.prod.yml ps \
  temporal temporal-ui reviewer-api
docker compose -f docker-compose.prod.yml logs --tail=100 \
  temporal temporal-ui reviewer-api
systemctl status temporal-reviewer-worker --no-pager
journalctl -u temporal-reviewer-worker -n 100 --no-pager
ss -ltn | grep 7233
```

The `ss` output must show `127.0.0.1:7233`, never `0.0.0.0:7233`.

Externally:

```bash
curl -I https://temporal.simonrowe.dev
```

Expect an Auth0 redirect. Sign in with the admin account and confirm the UI is
read-only.

In the GitHub App settings, send a webhook test delivery or update a pull
request. Confirm:

1. delivery returns HTTP `202`;
2. a `code-review-*` Workflow appears in Temporal;
3. the host worker completes the Activities;
4. one marker-based advisory comment appears on the pull request;
5. redelivery updates/deduplicates rather than creating another Workflow or
   comment.

## Updating and rollback

The publish workflow creates immutable reviewer image tags using the Git commit
SHA. Set `REVIEWER_IMAGE` to that tag so the API and host worker are identical.

After changing the tag:

```bash
docker compose -f docker-compose.prod.yml pull reviewer-api
docker compose -f docker-compose.prod.yml up -d reviewer-api
sudo REVIEWER_IMAGE="$REVIEWER_IMAGE" ./scripts/install-reviewer-worker.sh
sudo systemctl restart temporal-reviewer-worker
```

Rollback uses the same commands with the previous commit-SHA image.

Temporal schema migrations are forward-moving. Before upgrading the pinned
Temporal server/admin-tools version, take logical dumps:

```bash
docker exec simonrowe-dev-monorepo-langfuse-db-1 \
  pg_dump -U temporal -Fc temporal > temporal.dump
docker exec simonrowe-dev-monorepo-langfuse-db-1 \
  pg_dump -U temporal -Fc temporal_visibility > temporal_visibility.dump
```

Treat those dumps as sensitive because Workflow inputs and review summaries
are stored in Event History.

## Failure boundaries

- GitHub webhook unavailable: GitHub records a failed delivery for redelivery.
- API container unavailable: Temporal workers and existing Workflows continue.
- Host worker unavailable: Workflows remain queued and resume after systemd
  restarts the worker.
- Claude/model failure: the cost-bearing Activity is not automatically retried.
- Temporal UI unavailable: review processing continues.
- Temporal/Postgres unavailable: new triggers fail; do not delete the Postgres
  volume while recovering.
