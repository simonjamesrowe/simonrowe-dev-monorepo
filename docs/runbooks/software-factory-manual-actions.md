# Software Factory — manual actions

Things only a human with GitHub org admin or Grafana Cloud access can do. Code
and deploys cannot substitute for any of them.

Detail and rationale live in [software-factory.md](software-factory.md); this
file is the short, ordered checklist.

## 1. Bump the App's Contents permission — **outstanding, causing an outage**

**Status: not done. The code reviewer has been failing on every pull request
since 2026-08-11 05:47 UTC.**

PR #99 (`576eeb2`) made `GitHubCredentials.mintInstallationToken` request
`contents: write` on every installation token. GitHub returns `422` for the
*whole* token request when the requested permissions exceed what the App was
granted, so no token is minted, `loadPullRequest` throws, and the review dies
before it can post anything. This is step 1 of the documented
[rollout order](software-factory.md#rollout-order), which was missed.

Evidence: 7 consecutive failed workflows across `simonrowe-dev-monorepo` and
`agent-setup`, all with:

```
GitHub App token endpoint returned 422
  GitHubCredentials.mintInstallationToken(GitHubCredentials.java:199)
  GitHubGateway.loadPullRequest(GitHubGateway.java:62)
```

**Do this:**

1. GitHub → `simonjamesrowe` org settings → Developer settings → GitHub Apps →
   `simonrowe-code-reviewer` → Permissions & events
2. **Repository permissions → Contents: `Read-only` → `Read and write`**
3. Save. GitHub raises a permission request against each installation.
4. Approve it: org settings → GitHub Apps → `simonrowe-code-reviewer` →
   **Review request** → Accept new permissions.

**No redeploy or restart is needed.** Failed mints are never cached
(`mintInstallationToken` throws before touching `installationTokens`), so the
next review picks up the new grant immediately.

### Nothing else on the App needs changing

- `Issues: read and write` and `Pull requests: read and write` are already
  granted — reviews published successfully on #94, #96, #98 and #99, which is
  not possible without them.
- The App is already installed on `simonjamesrowe/agent-setup` — webhooks from
  that repo are reaching the factory and starting workflows.
- Contents is the only permission PR #99 added (`git log -S '"contents", "write"'`).

### Verifying the fix

Push any commit to an open pull request, then check Temporal:

`https://temporal.simonrowe.dev/namespaces/default/workflows` — filter for
`code-review-`. The new run should reach `Completed`, and the pull request
should get an `Automated code review` comment from `simonrowe-code-reviewer[bot]`.

If it still fails with `422`, the installation permission request was saved on
the App but not accepted on the installation — repeat step 4.

## 2. Grafana Cloud log reads — **done 2026-08-11, no action needed**

`GRAFANA_CLOUD_API_KEY` used to be write-only (ingest), so every LogQL query
against `logs-prod-035.grafana.net` returned:

```
401 {"status":"error","error":"authentication error: invalid scope requested"}
```

**Fixed** by adding `logs:read` to the existing `alloy-publisher` access policy
(`https://grafana.com/orgs/simonrowedev/access-policies`), which now carries
`logs:read`, `logs:write`, `traces:write`. Widening the existing policy was
chosen over issuing a second read-only token specifically so that **nothing on
the Pi and nothing in the env repo had to change** — the key already deployed
gained read access in place. Existing tokens pick up scope changes without being
reissued; propagation took about three minutes.

The trade-off accepted: Alloy's ingest credential can now also read logs. For a
single-owner setup where that key already sits in both the env repo and on the
Pi, the isolation a separate token would have bought is marginal.

Verified with a live query — `{service="software-factory"} |= "422"` returned 32
matching lines, which is how the outage in step 1 was confirmed from the
container's own logs.

If a query ever returns `invalid scope requested` again, that scope has been
removed: re-tick **Read** on the `logs` row of the `alloy-publisher` policy.

## What is *not* a manual action

- **Deploying `software-factory`. Fixed in code on 2026-08-11** — it is now in
  `redeploy.services`, so `POST /api/admin/data-operations/redeploy` pulls and
  restarts it like everything else. It is restarted on its own with `--no-deps`,
  because it declares `temporal` and `mongodb` as `service_healthy` dependencies
  and a redeploy must neither restart the database nor be blocked by Temporal's
  health. That restart is best-effort: a failure is reported in the operation's
  completion message rather than aborting the redeploy, since stranding the
  backend on its old image would be the worse outcome.

  **Check `.env` does not pin `FACTORY_IMAGE`.** The compose default is
  `…-software-factory:latest`, but the cutover script set `FACTORY_IMAGE`
  explicitly. If it is pinned to a specific tag or digest, redeploy will keep
  pulling that same image and the reviewer will still never advance — the exact
  problem this change was meant to remove.
