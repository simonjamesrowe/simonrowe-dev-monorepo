# Software Factory Production Runbook

The software factory is a modular monolith: one image
(`Dockerfile.software-factory`), run as two containers with different module
flags — `software-factory`, which terminates the GitHub webhook and holds the
GitHub/Claude/Linear credentials, and `deployer`, which holds
`/var/run/docker.sock` and nothing else. Five modules exist today:

| Module | Temporal queue | Trigger | Role | Runbook |
| --- | --- | --- | --- | --- |
| `codereview` | `code-review` | signed `pull_request` webhook | Clones the branch, runs Claude Code, posts one PR comment plus inline findings | this file, [Entry points](#entry-points) onward |
| `feedback` | `review-feedback` | PR close | Harvests the review conversation, writes `review_learnings`, opens `agent-feedback` guidance PRs | this file, [Review feedback loop](#review-feedback-loop) |
| `cvefix` | `cve-fix` | paused 24h Temporal schedule | Reads Dependency-Track, bumps dependencies, opens a PR, polls CI to green | [cvefix.md](cvefix.md) |
| `deploy` | `deploy` | signed `workflow_run` webhook | Runs `restart-prod.sh` phases on `deployer`, with rollback, triage and reporting | [deploy.md](deploy.md) |
| `linear` | `linear` | none — a sink, not a producer | Files `deploy` and `cvefix` findings into Linear exactly once per distinct problem | [linear.md](linear.md) |

`linear` is the odd one out on purpose: it has no trigger of its own and its
task queue has **no workflow poller at all**, only an activity poller — see
[linear.md](linear.md#what-this-is-and-what-it-is-not) before assuming that
shape is a fault.

It was previously two deployables — a credential-free `reviewer-api` container
and a `temporal-reviewer-worker` host service that owned the GitHub App key and
the Claude token. They were merged deliberately, to fit a single Raspberry Pi
and to stop the worker being a host service that no deploy ever reconciled.

The cost of that merge is explicit and accepted: the process that terminates
untrusted internet traffic now also holds long-lived credentials. What keeps it
defensible is that the attack surface is exactly one route.

Steps that only a human can perform — GitHub App permissions, Grafana Cloud
tokens — are tracked as a checklist in
[software-factory-manual-actions.md](software-factory-manual-actions.md),
including any that are currently outstanding.

## Migrating from the split deployment

**This is the one-time cutover. Run it after merging, not before.**

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
git pull
sudo -v && ./scripts/cutover-software-factory.sh
```

The script is idempotent and every step checks its own end state, so a re-run
after a failure is safe. It installs the GitHub App private key where the
container can read it, repoints `.env`, retires the host service, starts the
container and verifies the result. Delete it once the cutover has stuck.

Two things it exists to stop you getting wrong by hand:

- **Order.** `FACTORY_IMAGE` points at a ghcr image that CI only publishes once
  this change is on `main`. Running the cutover before that leaves the host
  worker disabled with nothing to replace it. The script checks the image is
  obtainable *before* it mutates anything, and tells you how to build locally if
  you want to test ahead of the merge.
- **The PEM.** It is `0640 root:temporal-reviewer` on the host, which uid 10003
  inside the container cannot read. Skip the re-install and everything looks
  fine until the first review fails minting an installation token, with an error
  that does not point back here.

Verification goes past "the container is healthy": it asserts a live poller on
the `code-review` task queue, a `401` for an unsigned webhook, and that
`/api/reviews` is not routable from outside. On failure it prints logs and
rollback steps — rollback works because the old `REVIEWER_*` keys are left in
`.env` on purpose.

Two steps are deliberately left to you, and are printed at the end: a
`publish:false` dry run (the only thing that exercises Claude auth and the
authenticated clone path end to end — see Verification), and the
cleanup — dropping the `REVIEWER_*` keys, removing
`/opt/temporal-reviewer` and the old unit file, and deleting the script.

## Entry points

`software-factory` exposes four things, and only the first is reachable from the
internet:

| Endpoint | Port | Auth | Routed by nginx |
| --- | --- | --- | --- |
| `POST /webhooks/github` | 8090 | HMAC-SHA256 over the raw body | yes, exact-match |
| `POST /api/reviews` | 8090 | `X-Factory-Token` | no |
| `GET /api/reviews/{workflowId}` | 8090 | `X-Factory-Token` | no |
| `/actuator/{health,info,prometheus}` | 8091 | none | no |

nginx uses `location = /webhooks/github` — an exact match, not a prefix — so no
other path on this service is routable from outside. That is defence in depth,
not the boundary: both `/api/reviews` endpoints check the trigger token
themselves, because a proxy rule is a routing decision and not an authorisation
one. `GET /api/reviews/{workflowId}` was unauthenticated until the merge; it is
covered by `ReviewControllerTest` now.

Signature verification lives in `WebhookSignatureVerifier`. It computes
HMAC-SHA256 over the exact request bytes, compares with `MessageDigest.isEqual`
so the comparison is constant-time, and fails closed when the configured secret
is missing or blank — an unset `GITHUB_WEBHOOK_SECRET` resolves to the empty
string, and accepting that would let anyone sign their own payloads. Do not
change this class without keeping `WebhookSignatureVerifierTest` and
`GitHubWebhookControllerTest` green; between them they pin unsigned, wrongly
signed, replayed-onto-a-different-body, non-JSON and malformed deliveries.

## What appears on a pull request

**One comment per pull request**, not per push. It is found by the marker
`<!-- temporal-code-review:{owner}/{repo}#{number} -->`, edited in place through
three states, and always names the commit it describes:

| State | Comment |
| --- | --- |
| Accepted | 🔄 "A review of these changes is in progress" |
| Reviewed | the summary and verdict |
| Failed | a failure notice with phase, reason and a Temporal link |

Findings are posted as **individual inline comments** (`POST /pulls/{n}/comments`),
each stamped with `<!-- temporal-code-review-finding -->`. Every push deletes the
ones the previous run left before posting its own, so a finding that has since
been fixed disappears instead of lingering, and one that still stands is
re-anchored to the current diff rather than duplicated. Comments by anyone else
are never touched.

Nothing is posted to `POST /pulls/{n}/reviews` any more. A submitted review can
be neither deleted nor hidden (`DELETE .../reviews/{id}` only accepts *pending*
ones), so one per push would accumulate on the pull request even after its
comments were pruned — and GitHub rejects a `COMMENT` review with an empty body,
so there is no bodiless review to post instead. That is what the first version
got wrong: the marker was keyed by head SHA, so a push could never match its
predecessor's comment and each one submitted a fresh review. Pull request 102
collected three summaries for three pushes.

A finding that will not anchor to the current diff (GitHub answers `422` for that
one comment) is folded into the summary under a `### Findings` heading; its
siblings still post inline.

**Silence now means exactly one thing: the workflow never started** — the webhook
did not arrive, or nothing is polling the `code-review` task queue. Every other
outcome is visible on the pull request. Before this, silence meant any of five
things and was the *normal* presentation of failure: three of the seven pull
requests opened after the reviewer first worked got no comment at all.

Two caveats:

- `publish: false` runs post nothing at all, including failure notices. A green
  dry run does not prove the publish path works.
- A credential fault severe enough to block minting any token also blocks
  commenting, so it stays visible only in Temporal. Lifecycle comments mint their
  token with no `permissions` override to make this as unlikely as possible — an
  over-broad request from the review path cannot break commenting — but an
  uninstalled App or a bad key will.

The Temporal link is built from `factory.codereview.temporal-ui-base-url`
(`TEMPORAL_UI_URL`, defaulting to `https://temporal.simonrowe.dev`). Blank drops
the link and keeps the bare workflow id.

## One-time external setup

### GitHub App

Create a private, organization-owned GitHub App named `simonrowe-code-reviewer`:

- Homepage URL: `https://simonrowe.dev`
- Webhook URL: `https://api.simonrowe.dev/webhooks/github`
- Webhook secret: the existing `GITHUB_WEBHOOK_SECRET` from the deploy `.env`.
  Generate a new value only if `.env` has none — changing it means restarting
  `software-factory`, which reads it at boot, or every delivery fails signature
  verification with `401`.
- Repository permissions:
  - Contents: read & write — required by the review feedback loop, which opens guidance PRs
    against agent-setup and/or the source repo; see [Review feedback loop](#review-feedback-loop)
    below. `GitHubCredentials` requests `contents: write` on every installation token mint —
    for both the code-review and feedback paths, since they share one method — so this
    permission must be granted before any image that mints tokens is deployed, not after.
  - Issues: read and write
  - Pull requests: read and write — **write is required**, even though the
    advisory comment is posted to the issue comments endpoint. GitHub governs
    comments on a pull request by the pull request permission, not the issue
    one, so `pull_requests: read` reviews cleanly and then fails publishing with
    `403 for POST /repos/{owner}/{repo}/issues/{n}/comments`. `GitHubCredentials`
    requests this scope on every installation token; the App must grant it.
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

There is one secrets file: the production `.env` in the deploy directory.
Compose interpolates from it; `software-factory` receives only the variables its
`environment:` block names.

```dotenv
GITHUB_WEBHOOK_SECRET=...
FACTORY_TRIGGER_TOKEN=...
TEMPORAL_DB_PASSWORD=...
TEMPORAL_AUTH0_CLIENT_ID=...
TEMPORAL_AUTH0_CLIENT_SECRET=...
TEMPORAL_AUTH0_ISSUER=https://YOUR_AUTH0_DOMAIN/
FACTORY_IMAGE=ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-software-factory:COMMIT_SHA

GITHUB_APP_CLIENT_ID=...
# Host path. Compose bind-mounts this file to /run/secrets/github-app.pem and
# overrides the in-container value of this variable to that path.
GITHUB_APP_PRIVATE_KEY_PATH=/opt/software-factory/github-app-private-key.pem
CLAUDE_CODE_OAUTH_TOKEN=...
```

`FACTORY_*` replaced `REVIEWER_*`. The application reads `FACTORY_*` first and
falls back to the old names for one release, so an un-migrated `.env` still
boots; `docker-compose.prod.yml` does **not** carry that fallback, so a deploy
needs the new names present. Delete the `REVIEWER_*` keys once this is verified.

Keep `.env` at mode `0600`. It is the only copy of these credentials.

**`software-factory` must never gain `env_file: .env`.** It would load all ~83
production variables into the process that terminates untrusted internet
traffic. It declares only what it needs, and everything else in `.env` is
invisible to it. This matters more since the merge, not less: the credential
separation that used to come from running the agent in a different process now
comes only from this list.

Never put the PEM contents directly in `.env`, Compose, Temporal inputs, or a
Claude configuration file. Only the *path* belongs in `.env`.

The PEM is bind-mounted read-only. The container runs as uid/gid `10003`, so the
file on the host must be readable by that gid:

```bash
sudo install -o root -g 10003 -m 0640 \
  github-app-private-key.pem /opt/software-factory/github-app-private-key.pem
```

A PEM left at `0640 root:root`, or owned by the retired `temporal-reviewer`
group, is unreadable inside the container and every review fails when it tries
to mint an installation token.

### Claude authentication

Use **either** a Claude Pro/Max subscription token **or** a pay-as-you-go API
key. Setting both is a silent trap: `ANTHROPIC_API_KEY` takes precedence and is
billed per token even though a subscription token is present.

For the subscription route, run `claude setup-token` as a human and put the
result in `CLAUDE_CODE_OAUTH_TOKEN`. Reviews then consume subscription usage
limits shared with your own interactive Claude Code use, so a burst of pull
requests can exhaust the limit that your interactive session depends on.

Both variable names are allowlisted in `ClaudeCliReviewEngine`. That engine
strips **everything** from the agent's environment except `SAFE_SECRET_ENVIRONMENT`
(Claude's own credentials) and `PROCESS_ENVIRONMENT` (`PATH`, `HOME`, proxy
settings and similar). So a Claude credential under any *other* name is silently
removed and the review fails to authenticate. Add new credential variables to
`SAFE_SECRET_ENVIRONMENT`, never to the service environment alone.

The allowlist replaced a blocklist of suspicious-looking name patterns, which
was unsafe once the service started reading production credentials: pattern
matching on `TOKEN`/`SECRET`/`PASSWORD`/`_KEY` misses real secrets such as
`DEPENDENCYTRACK_KEK`, `REDIS_AUTH` and `SALT`. That matters because the agent
reads attacker-authored pull request branches, so anything left in its
environment is reachable by a prompt-injection payload. Do not reintroduce
pattern-based filtering.

Do not attempt to authenticate the service by copying a human's
`~/.claude/.credentials.json` into the container. The service and the
interactive session would refresh the same OAuth credential independently and
can invalidate each other.

## First deployment

For a host that already runs the split deployment, use
[Migrating from the split deployment](#migrating-from-the-split-deployment)
instead — this section is for a clean install.

There are no host prerequisites. Java and Claude Code both ship inside the
image; the Pi needs only Docker and a current checkout. (The retired host path
needed a system-wide JRE at `/usr/bin/java` and a root-owned
`/usr/local/bin/claude`, installed by two scripts that no longer exist.)

Claude Code is pinned by the `CLAUDE_VERSION` build arg in
`Dockerfile.software-factory`, downloaded from the same release endpoint the old
installer used and checked against the published SHA-256. It is installed
root-owned at mode 0755 so the service user executes it but cannot replace it,
which keeps the version tied to an explicit image rebuild rather than a
background self-update.

Place the GitHub App private key where Compose expects it, readable by the
container's gid:

```bash
sudo mkdir -p /opt/software-factory
sudo install -o root -g 10003 -m 0640 \
  github-app-private-key.pem /opt/software-factory/github-app-private-key.pem
chmod 0600 .env
```

Validate Compose before changing running services:

```bash
docker compose -f docker-compose.prod.yml config --quiet
```

Then start the stack:

```bash
docker compose -f docker-compose.prod.yml up -d \
  temporal-db-init \
  temporal-schema-init \
  temporal \
  temporal-create-namespace \
  temporal-ui \
  software-factory
docker compose -f docker-compose.prod.yml restart nginx
```

## Verification

On the Pi:

```bash
docker compose -f docker-compose.prod.yml ps \
  temporal temporal-ui software-factory
docker compose -f docker-compose.prod.yml logs --tail=100 \
  temporal temporal-ui software-factory
ss -ltn | grep 7233
```

The `ss` output must show `127.0.0.1:7233`, never `0.0.0.0:7233`.

The single most useful check is whether the worker half actually joined its task
queue. A healthy container that never registered a poller looks identical from
the outside, and webhooks will be accepted and then sit unprocessed:

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal task-queue describe --address temporal:7233 \
  --namespace default --task-queue code-review
```

Expect one `workflow` and one `activity` poller. Zero pollers means the webhook
is live but nothing will ever review a pull request.

Externally:

```bash
curl -I https://temporal.simonrowe.dev
```

Expect an Auth0 redirect. Sign in with the admin account and confirm the UI is
read-only.

Confirm the internal API is not exposed. Both must fail from outside:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://api.simonrowe.dev/api/reviews/x
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.simonrowe.dev/api/reviews
```

Anything other than a backend `404`/`405` means nginx is routing more than the
webhook and the exact-match `location` has been loosened.

An unsigned delivery must be rejected:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  https://api.simonrowe.dev/webhooks/github \
  -H 'X-GitHub-Event: pull_request' -d '{"action":"opened"}'
```

Expect `401`.

For an end-to-end check without publishing a comment, trigger a dry run from
inside the Docker network (the manual API is deliberately unrouted):

```bash
docker run --rm --network simonrowe-dev-monorepo_default curlimages/curl -s \
  -X POST http://software-factory:8090/api/reviews \
  -H "X-Factory-Token: $(grep '^FACTORY_TRIGGER_TOKEN=' .env | cut -d= -f2-)" \
  -H 'Content-Type: application/json' \
  -d '{"owner":"simonjamesrowe","repository":"simonrowe-dev-monorepo","pullNumber":NN,"publish":false}'
```

`publish:false` exercises the clone, the Claude invocation and the parse without
writing to GitHub. Note it consumes Claude subscription usage shared with your
interactive sessions.

It also proves the *authenticated* clone. `ReviewController` resolves the App
installation for the repository (`GET /repos/{owner}/{repo}/installation`) and
puts it in the `ReviewRequest`, so a dry run mints a real installation token and
clones with the same `http.extraHeader` credential a webhook-triggered review
uses.

It used to send no installation id, so `GitWorkspaceFactory` found a blank
token, set no header, and cloned this public repo anonymously — a credential bug
on the authenticated path passed this check and then failed every real review,
which is exactly how the bearer-token clone bug shipped and broke PRs 88–93. The
dry run is a genuine pre-deploy check now, but it still writes nothing to GitHub,
so keep the webhook delivery below as the test of the publish path. If a clone
step fails with

```text
clone failed: fatal: could not read Username for 'https://github.com':
terminal prompts disabled
```

git was handed credentials GitHub's git-over-HTTPS endpoint rejected (it takes
`Basic base64("x-access-token:<token>")`, not the bearer header the REST API
accepts), got a 401, and fell back to prompting.

Then, in the GitHub App settings, send a webhook test delivery or update a pull
request. Confirm:

1. delivery returns HTTP `202`;
2. a `code-review-*` Workflow appears in Temporal;
3. the Activities complete;
4. one marker-based advisory comment appears on the pull request;
5. redelivery updates/deduplicates rather than creating another Workflow or
   comment;
6. **pushing a second commit rewrites that same comment rather than adding one**,
   and any inline findings from the first push are gone rather than doubled.

## Updating and rollback

The publish workflow pushes both `:latest` and an immutable commit-SHA tag on
every merge to `main`.

Normal updates need no manual step. `FACTORY_IMAGE` tracks `:latest` and the
service carries `pull_policy: always`:

```bash
./scripts/restart-prod.sh
```

That script used to reconcile a host service as well, comparing resolved image
**IDs** against `/opt/temporal-reviewer/installed-image` because a moving
`:latest` tag would otherwise report "unchanged" forever and leave the worker on
a stale jar. None of that is needed now — there is one image, pulled by Compose,
and the API/worker drift it defended against is structurally impossible when
both halves are the same process.

To roll back, or to hold on a known build, pin the commit-SHA tag:

```bash
# in .env
FACTORY_IMAGE=ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-software-factory:COMMIT_SHA
```

then `./scripts/restart-prod.sh`.

Claude Code's version is pinned separately, by `CLAUDE_VERSION` in
`Dockerfile.software-factory`. Bumping it is an image rebuild, so a Claude
upgrade is a reviewable commit rather than something that happens underneath a
running service.

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

## Review size and time limits

Five limits bound one review, and they are coupled — raising one without the
others just moves where a large pull request gets truncated.

| Limit | Where | Default | Bounds |
| --- | --- | --- | --- |
| `FACTORY_MAX_CHANGED_FILES` | `application.yml`, compose | 250 | Files in the diff. Over it, the Activity fails with `Review exceeds changed-file limit`. |
| `FACTORY_MAX_DIFF_BYTES` | `application.yml` | 2 MiB | Diff size. In practice the tighter of the two — 2 MiB already exceeds what the agent can hold, so raise it only with a plan for chunking. |
| `CLAUDE_MAX_TURNS` | `application.yml`, compose | 60 | Agent turns. Exhaustion is a *silent* failure mode — see the turn-exhaustion note below. |
| `CLAUDE_TIMEOUT` | `application.yml`, compose | 25m | The agent process only. |
| `RunReview` start-to-close | `CodeReviewWorkflowImpl` (code) | 35m | Clone + checkout + diff + agent + parse. Must stay comfortably above `CLAUDE_TIMEOUT`. |
| `RunReview` heartbeat | `CodeReviewWorkflowImpl` (code) | 2m | Liveness. See the heartbeat-timeout note below. |

The two Temporal timeouts are deliberately hardcoded rather than configured:
Workflow code must be deterministic across replay, so it cannot read mutable
configuration. Changing them needs a rebuild, not just an `.env` edit.

`CLAUDE_MAX_TURNS`, `CLAUDE_TIMEOUT` and `FACTORY_MAX_CHANGED_FILES` are named in
`docker-compose.prod.yml`'s `environment:` block with defaults that mirror
`application.yml`. The compose value **shadows** the image's, so bumping only
`application.yml` leaves prod on the old limit. Keep the two in step.

History: at 80 files, `FACTORY_MAX_CHANGED_FILES` refused PR #106 (98 files)
outright. That size is normal here once a spec, backend, frontend and docs move
in one change, so the guard was only catching real work. It still exists to
refuse a pull request that is pointless to review — a regenerated lockfile, a
vendored directory, a mass rename — and 250 leaves that intact.

## Failure boundaries

- GitHub webhook unavailable: GitHub records a failed delivery for redelivery.
- `software-factory` unavailable: deliveries fail at the door and Workflows
  already running are suspended, resuming when Compose restarts the container.
  Merging the API and the worker means these two no longer fail independently —
  losing the container now loses both ingress and processing, where previously a
  live API would still queue work for a dead worker.
- Claude/model failure: the cost-bearing Activity is not automatically retried,
  but the reason is now posted to the pull request instead of being swallowed.
- Temporal UI unavailable: review processing continues.
- Temporal/Postgres unavailable: new triggers fail; do not delete the Postgres
  volume while recovering.

A container that is `healthy` but registered no Temporal poller is the quiet
failure to watch for: the webhook returns `202`, Workflows accumulate, and
nothing reviews them. The task-queue check under Verification is what catches
it. The actuator healthcheck on 8091 does not — it reports the web half only.

The near neighbour is a healthy container with a live poller whose every review
dies in the clone Activity on `could not read Username for 'https://github.com'`
— a credential fault, not an outage. Nothing external looks wrong, so check the
Activity failures in Temporal, not the container state. This used to be invisible
to the `publish:false` dry run, which cloned anonymously; the dry run now
resolves an installation id and so fails the same way.

A separate quiet failure is the agent running out of turns. The Claude CLI in
`-p --output-format json` mode reports why it stopped as JSON on **stdout** and
leaves stderr empty, so a stderr-only error message is blank for every agent-side
failure. This bit PR #95 (27 files, 4,194 insertions): the agent exhausted the
then-default 12 turns after 122s, the Activity failed with a bare
`Claude exited with 1:` and, because `RunReview` has `setMaximumAttempts(1)`,
the Workflow failed immediately with nothing posted to the pull request.
`CLAUDE_MAX_TURNS` now defaults to 60, the failure detail now includes the
stdout `subtype`/`terminal_reason`/`errors` fields, and a failed review posts a
notice on the pull request rather than going silent. To confirm a suspected
turn exhaustion by hand:

```bash
docker exec simonrowe-dev-monorepo-software-factory-1 sh -lc \
  'echo "<prompt>" | claude -p --output-format json --max-turns 12 > /tmp/o 2>/tmp/e; \
   echo "EXIT=$?"; grep -o "\"terminal_reason\":\"[^\"]*\"" /tmp/o'
```

A third quiet failure is the heartbeat timeout, which reads as an outage but is
a configuration fault. `RunReview` heartbeats through `ProcessRunner`, which
emits a beat every 10s **only while a child process is running** — a git command
that finishes faster than that emits nothing at all, so the un-heartbeated gap is
the whole run of steps between two explicit `heartbeat.accept` calls, not the
duration of any one process. With the old 30s heartbeat timeout that gap was
routinely wider than the timeout on the Pi: PR #111, a **one-file** docs change,
died after 31s with `activity Heartbeat timeout` and `lastHeartbeatDetails` still
reading `Cloning pull request repository` — the clone, the partial-clone
checkout, the two diffs and the full-tree credential sweep had all run without a
single beat reaching the server. Because `RunReview` is `setMaximumAttempts(1)`,
that ended the review. The old value was doubly wrong: the heartbeat timeout also
sets its own delivery cadence, throttled to
`min(0.8 * heartbeatTimeout, 60s)`, so 30s meant flushing every 24s against a 30s
deadline — 6s of slack, thin enough that a Pi under load tipped over even when
beats *were* being emitted. Two changes fix it: `GitWorkspaceFactory` now beats before
every step and every 2,000 paths of the credential sweep, and the heartbeat
timeout is 2m. Diagnose it from the Timeline tab in Temporal — a
`TIMEOUT_TYPE_HEARTBEAT` whose activity duration is far below the start-to-close
timeout is this, not a slow agent.

The furthest-along variant is a review that clones, reviews and parses, then
fails the *last* Activity on `GitHub API returned 403 for POST
/repos/.../issues/{n}/comments`. That is a token scope fault: comments on a pull
request need `pull_requests: write`, not `issues: write`. Note the dry run cannot
catch this one — `publish:false` never reaches the publish Activity — so a
webhook delivery remains the only test of the publish path.

## Review feedback loop

On PR close, `review-feedback-{owner}-{repo}-{pr}` workflow on the `review-feedback` task queue harvests the conversation (Haiku), writes `software_factory.review_learnings` in Mongo, and — when lessons exist — opens `agent-feedback`-labeled guidance PRs (Sonnet) against agent-setup and/or the source repo. PRs labeled `agent-feedback` are never harvested (loop guard). Master switch `FACTORY_FEEDBACK_ENABLED`.

### One-time GitHub App changes

The existing `simonrowe-code-reviewer` GitHub App must be updated to permit the feedback loop to open guidance PRs:

- Bump the **Contents** permission from **read** to **read and write** in the App settings: org settings → Developer settings → GitHub Apps → simonrowe-code-reviewer → Permissions.
- Re-approve the permission request when GitHub prompts you on the next deployment or workflow run.
- Install the App on the `simonjamesrowe/agent-setup` repository, if not already installed. The App must be installed on both the source repository being reviewed and the agent-setup target to write guidance PRs.

**Accepted risk:** Permitting write access to Contents allows the distillation Workflow to create guidance PRs. All mutations are submitted as PRs (never pushed directly), gated by the allowlist of files the distiller may touch, and guarded by the loop-prevention label.

### Rollout order

1. Perform the one-time GitHub App changes above — **before** deploying the new image, not after.
   `GitHubCredentials.mintInstallationToken` requests `contents: write` on every installation
   token it mints, for both the code-review and feedback paths (they share this one method), as
   soon as the new image starts running. GitHub's access-tokens endpoint 422s that request until
   the App's own Contents permission has actually been bumped to read & write, which fails token
   minting outright — a stale App permission plus the new image means every review AND every
   feedback run fails, a full outage of the review feature, not just the new one.
2. Deploy the new image with `FACTORY_FEEDBACK_ENABLED` unset (off) — this ships the inline-reviews
   change too; verify a new PR still gets an inline review.
3. Dry run from the Pi:
   ```bash
   curl -X POST https://<internal>/api/feedback \
     -H 'X-Factory-Token: …' \
     -d '{"owner":"simonjamesrowe","repository":"simonrowe-dev-monorepo","pullNumber":<a real closed PR>,"dryRun":true}'
   ```
   This command must be run from inside the container network (`docker exec` into the container); the path is not routed by nginx. Check the Mongo record:
   ```bash
   docker exec simonrowe-dev-monorepo-mongodb-1 mongosh software_factory --eval 'db.review_learnings.find().pretty()'
   ```
4. Set `FACTORY_FEEDBACK_ENABLED=true` in `.env` and `./scripts/restart-prod.sh`.

### Verification

The worker half must join the `review-feedback` task queue and register a poller:

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal task-queue describe --address temporal:7233 \
  --namespace default --task-queue review-feedback
```

Expect one `workflow` and one `activity` poller. Zero pollers means the webhook is live but feedback workflows will never run (same quiet-failure warning as `code-review`).

### Failure modes

- Distillation `FAILED` keeps the lessons in Mongo and sets `distillation.status = FAILED` on
  the `review_learnings` record — re-drive with the manual endpoint (`dryRun:false`). The
  workflow id (`review-feedback-{owner}-{repo}-{pr}`) is restartable after a genuine failure
  (`WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY`), so the re-drive actually runs.
- A `409` from `POST /api/feedback` means the workflow id is still running, or it already
  completed successfully — `ALLOW_DUPLICATE_FAILED_ONLY` only permits restarting an id that
  failed. If it's still running, wait and retry. If it already completed, there is nothing to
  re-drive: a successful harvest for a given PR number is final by design (the reopen-then-reclose
  loop guard this workflow id shape exists for).
- `403` on push = Contents permission not bumped or App not installed on the target repo.
- Allowlist violation in logs = the distiller touched files it must not (the push never happened — inspect, adjust prompt, re-drive).
- `distillAndPropose` timing out mid-target. `resolveTargets` yields at most two targets
  (agent-setup, plus the source repo when any lesson is `REPO_SPECIFIC`) and walks them
  **serially** in one Activity call, so the Activity's start-to-close budget is
  `2 * distill.timeout` plus the clone/push/PR-open work around each. That budget was 20m
  against a 30m worst case — a second target using its full 15m would have been cut off, and
  because the Activity is `setMaximumAttempts(1)` the first target's already-pushed PR would
  have been lost from the outcome. Now 40m. Recompute it in
  `ReviewFeedbackWorkflowImpl` whenever `FACTORY_FEEDBACK_DISTILL_TIMEOUT` or the target count
  changes: it is workflow code, so it cannot read the configured value at runtime.
