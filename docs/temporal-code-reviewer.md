# Temporal Code Reviewer

This is a deliberately small first station in a future software factory. It
reviews GitHub pull requests; it does not fix code, create branches, approve
merges, block merges, manage downstream repositories, or run a panel of agents.

## Why this shape

Temporal owns durable control flow. A provider-neutral `ReviewEngine` owns one
code-aware review attempt. GitHub and the agent runner are Activities, so no
network, filesystem, process, or model call happens inside deterministic
Workflow code.

```text
GitHub webhook / manual API
            |
            v
  CodeReviewWorkflow (Temporal)
      | loadPullRequest    retryable, idempotent
      | runReview          single attempt, heartbeat, cost-bearing
      | publishReview      retryable, marker-based upsert
            |
            v
  one advisory PR comment
```

The full diff stays in a disposable checkout rather than Temporal Event
history. Only compact request metadata, progress, and the structured report
cross Activity boundaries. Duplicate GitHub delivery for the same
`owner/repo/PR/head SHA` resolves to the same Workflow ID.

The working name is simply **Temporal Code Reviewer**. It intentionally does
not reuse the Brunel name or copy Brunel's specialist panel, downstream graph,
control plane, builder, or UI.

## Agent choice

The first adapter calls Claude Code's supported headless CLI from Java. This is
the lightest path when Claude Code is already installed on the host, and it
retains Claude's code-navigation loop without introducing a second service.
The invocation:

- uses validated JSON-schema output;
- loads no repository settings, `CLAUDE.md`, skills, plugins, hooks, or MCP;
- exposes only `Read`, `Glob`, and `Grep`;
- runs in `dontAsk` mode, so anything not explicitly allowed is denied;
- removes unrelated token/secret/password environment variables;
- excludes common credential files from the model-visible diff and checkout;
- removes repository symlinks before review so a relative read cannot escape;
- discards findings outside changed files;
- normalizes the verdict deterministically from accepted findings.

`ReviewEngine` is the important boundary. A later worker can implement it with:

- the Claude Agent SDK in a separately isolated TypeScript/Python process;
- Embabel for typed, provider-neutral multi-step agent planning;
- Spring AI, including Temporal's public-preview Spring AI integration;
- a local model or another code agent.

Embabel is not in the first review path because Temporal already provides the
workflow/state/retry layer and Claude already provides the code agent harness.
Adding Embabel between those two would initially duplicate orchestration. It
becomes useful when the factory needs dynamic typed planning across reusable
business actions, multiple model providers, or non-code agents.

## Run locally

Prerequisites:

- Java 21
- Docker
- Git
- Claude Code 2.1.205 or newer on `PATH` (2.1.220 was verified during
  implementation)

Start Temporal:

```bash
docker compose up -d temporal
```

The Compose service is the official all-in-one development image with a
named-volume SQLite database and UI. It is suitable for local development and
CI, not the final production topology.

Set credentials without committing them:

```bash
export GITHUB_TOKEN=...
export GITHUB_WEBHOOK_SECRET=...
export REVIEWER_TRIGGER_TOKEN=...
```

For public repositories and an unpublished manual review, `GITHUB_TOKEN` can be
empty. Publishing and private clones require it.

Start the API and worker:

```bash
./gradlew :reviewer:bootRun
```

Trigger a review without publishing:

```bash
curl -X POST http://localhost:8090/api/reviews \
  -H "Content-Type: application/json" \
  -H "X-Reviewer-Token: ${REVIEWER_TRIGGER_TOKEN}" \
  -d '{
    "owner": "simonjamesrowe",
    "repository": "simonrowe-dev-monorepo",
    "pullNumber": 1,
    "expectedHeadSha": "",
    "publish": false
  }'
```

The response contains a `workflowId`. Query it with:

```bash
curl http://localhost:8090/api/reviews/WORKFLOW_ID
```

Configure GitHub to send `pull_request` events to:

```text
POST https://YOUR_HOST/webhooks/github
```

The webhook handles `opened`, `reopened`, `synchronize`, and
`ready_for_review`, skips drafts, verifies `X-Hub-Signature-256` against the
exact request body, and publishes by default.

## Current operational boundaries

- The published result is a GitHub Review submitted with a `COMMENT` event,
  one inline comment per finding anchored to the diff, with a body fallback
  when a finding cannot be anchored (422). The upsert marker lives in the
  review body rather than an issue comment.
- Production uses short-lived GitHub App installation tokens. `GITHUB_TOKEN`
  remains an optional local-development bridge.
- The agent Activity has one attempt. Temporal does not blindly repeat a
  cost-bearing model run after an ambiguous timeout.
- A checkout is capped at 80 changed files and a 2 MiB diff by default.
- The service runs as a dedicated non-root user (uid/gid 10003) inside the
  container, with the Claude binary root-owned at 0755 so it can be executed but
  not replaced.
- Do not rely on an interactive consumer Claude login as an unattended service
  credential. Use a supported Anthropic API key or workload identity for
  production.

## Raspberry Pi topology

```text
Cloudflare/pinggy -> nginx -> software-factory container
                              (POST /webhooks/github only)
                                      |
                                      v
                            Temporal container/service
                                      |
                               code-review queue
                                      |
                                      v
                        same software-factory container
                                      |
                                      v
                        Claude Code binary in the image
```

The webhook receiver and the Temporal worker are one process. That was not
always so: the worker previously ran as a systemd service on the host, keeping
the internet-facing container free of Claude and GitHub App credentials. The two
were merged to fit a single Raspberry Pi — one JVM instead of two — and to stop
the worker being a host service that no `docker compose up` ever reconciled,
which is exactly how it once sat dead for a day while webhooks were still being
accepted.

The trade accepted in that merge: the process terminating untrusted internet
traffic now also holds long-lived credentials. What makes it defensible is that
the exposed surface is a single exact-match route whose signature check is
tested, and that the agent runs read-only (`--tools Read,Glob,Grep`, no Bash, no
Write, MCP disabled) inside a container rather than on the host. Cloning
contributor branches into a container is stricter isolation than the host
service had, which offsets part of what the merge gave up.

The container declares no `env_file`. It receives only the variables named in
its Compose `environment:` block, and `ClaudeCliReviewEngine` strips everything
outside `SAFE_SECRET_ENVIRONMENT`/`PROCESS_ENVIRONMENT` before spawning Claude,
so a prompt-injection payload in a pull request cannot read unrelated secrets.

Keep the public ingress limited to the signed webhook; the manual endpoint and
the status endpoint stay unrouted and token-protected. Production Compose uses
the pinned `temporalio/server` image with `temporal` and `temporal_visibility`
schemas in Postgres. The SQLite development service remains local-only.

### GitHub App for `simonjamesrowe`

Production should use an organization-owned GitHub App rather than Simon's
personal access token:

- Webhook URL: `https://api.simonrowe.dev/webhooks/github`
- Webhook secret: a new random secret stored only in the App and the Pi env
- Subscribe to: **Pull request**
- Repository permissions: **Contents: read**, **Pull requests: read**,
  **Issues: read and write**, and the implicit **Metadata: read**
- Install on `simonjamesrowe`; start with only the repositories being reviewed
- No user authorization/callback URL or organization permission is required

The API ingress should route only `/webhooks/github` to the reviewer service.
Do not expose its management port, Temporal ports, or manual trigger publicly.
The App webhook payload includes an installation ID. `GitHubCredentials`
exchanges that ID for a repository-scoped installation token and caches it
until five minutes before expiry. The private key and token exist only inside
GitHub/checkout Activities; neither enters Workflow inputs, Event History,
prompts, nor the Claude environment.

### Auth0-protected Temporal UI

Expose the browser UI at `https://temporal.simonrowe.dev`, but never expose the
Temporal gRPC port (`7233`). The wildcard Pinggy route can reach nginx, which
should proxy this hostname only to a dedicated `temporalio/ui` container.
Temporal UI supports OIDC directly, so an additional OAuth proxy is not
required.

Create a dedicated **Regular Web Application** in the existing Auth0 tenant:

- Name: `Temporal UI`
- Callback URL:
  `https://temporal.simonrowe.dev/auth/sso/callback`
- Logout URL: `https://temporal.simonrowe.dev`
- Web origin: `https://temporal.simonrowe.dev`
- Restrict access to Simon's admin identity or a dedicated Auth0 Organization
- Do not enable public sign-up for this application

Do not reuse the public portfolio application's Auth0 client. Authentication
without an application-level access restriction could otherwise let any
visitor account in the tenant see workflow inputs and results.

Production Compose configures the Temporal UI container with:

```yaml
temporal-ui:
  image: temporalio/ui:2.52.1
  restart: unless-stopped
  environment:
    TEMPORAL_ADDRESS: temporal:7233
    TEMPORAL_AUTH_ENABLED: "true"
    TEMPORAL_AUTH_TYPE: oidc
    TEMPORAL_AUTH_PROVIDER_URL: ${TEMPORAL_AUTH0_ISSUER}
    TEMPORAL_AUTH_ISSUER_URL: ${TEMPORAL_AUTH0_ISSUER}
    TEMPORAL_AUTH_CLIENT_ID: ${TEMPORAL_AUTH0_CLIENT_ID}
    TEMPORAL_AUTH_CLIENT_SECRET: ${TEMPORAL_AUTH0_CLIENT_SECRET}
    TEMPORAL_AUTH_CALLBACK_URL: https://temporal.simonrowe.dev/auth/sso/callback
    TEMPORAL_AUTH_SCOPES: openid,profile,email
    TEMPORAL_AUTH_REDIRECT_TO_PROVIDER: "true"
    TEMPORAL_CORS_ORIGINS: https://temporal.simonrowe.dev
    TEMPORAL_DISABLE_WRITE_ACTIONS: "true"
```

Start read-only with `TEMPORAL_DISABLE_WRITE_ACTIONS=true`. Enable mutations
only after Temporal Server authorization is configured; UI login protects the
browser route but is not a substitute for authorization on the underlying
Temporal API.

The nginx virtual host is:

```nginx
server {
    listen 80;
    server_name temporal.simonrowe.dev;

    location / {
        set $upstream_temporal_ui temporal-ui;
        proxy_pass http://$upstream_temporal_ui:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

Nginx uses Docker's runtime DNS resolver, so it starts even while Temporal UI
is unavailable and returns a scoped 502 only for this hostname.

At higher scale, split the API and workers. Route `code-review-claude`,
`code-review-embabel`, `verify-java`, `verify-frontend`, and similar
capabilities to separate Task Queues. Each worker can then have its own image,
permissions, concurrency, model credentials, and hardware.

## Software-factory path

Build the harness before adding autonomy:

1. **Review station** — current slice: immutable input, exact-SHA checkout,
   structured output, evidence filtering, observable workflow.
2. **Evaluation station** — golden PR fixtures, false-positive labels, prompt
   and model version ledger, cost/duration metrics, replayable episode bundle.
3. **Verification station** — repository-owned deterministic commands
   (`test`, `checkstyle`, lint, build) with captured artifacts and explicit
   failure attribution.
4. **Planning/build station** — child Workflows for plan, isolated patch,
   verification, and human approval. No write token reaches the reasoning
   worker.
5. **Factory graph** — reusable typed inputs/outputs, Task Queues per
   capability, policy gates, artifact storage, human interventions, and evals
   across every station.

The invariant is that agents propose or assess; deterministic code owns
permissions, state transitions, evidence, budgets, and completion.
