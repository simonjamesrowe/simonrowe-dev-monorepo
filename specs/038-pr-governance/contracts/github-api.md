# Contract: GitHub API surface used by 038-pr-governance

This feature exposes **no new HTTP endpoint**. `software-factory` gains no route, and nginx needs
no change — `POST /webhooks/github` remains the only routed path. What follows is the set of
GitHub calls the new gateway code makes, which is the real contract under test.

Base URL: `factory.codereview.github.api-base-url`. Header set matches `GitHubGateway.send`:
`Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2026-03-10`,
`User-Agent: temporal-code-reviewer`, `Authorization: Bearer <token>`.

---

## A. Check runs (REST) — **new permission required**

Token: `GitHubCredentials.accessToken(installationId)`.
Permission: **`checks: write`** — must be granted on the App and the installation update accepted
**before** the image requesting it is deployed. See the rollout hazard in `research.md` R2.

### A1. Create — `POST /repos/{owner}/{repo}/check-runs`

Called once, immediately after `loadPullRequest` returns (the first moment the head SHA is known).

```json
{
  "name": "Code Review",
  "head_sha": "<pullRequest.headSha()>",
  "status": "in_progress",
  "started_at": "<ISO-8601>",
  "details_url": "<temporal UI workflow link, omitted when unconfigured>",
  "output": {
    "title": "Review in progress",
    "summary": "An automated review of these changes is running."
  }
}
```

Response: `201`. `id` (number) is captured and threaded to the completion call.

**Failure is non-fatal to the review.** Like `openStatusComment`, a failure here yields a `null`
check-run id and the review continues — but unlike the status comment, the *absence* of the check
run is itself the blocking signal, so nothing needs to compensate.

### A2. Complete — `PATCH /repos/{owner}/{repo}/check-runs/{check_run_id}`

```json
{
  "status": "completed",
  "conclusion": "success" | "failure",
  "completed_at": "<ISO-8601>",
  "output": {
    "title": "<verdict + finding count>",
    "summary": "<report summary, or the failure reason + Temporal link>"
  }
}
```

**`conclusion` is only ever `success` or `failure`.** `neutral`, `skipped`, `cancelled` and
`action_required` are never sent: whether `neutral` satisfies a ruleset's required check is
version-dependent behaviour the gate must not rest on.

Conclusion mapping (pure function, exhaustively tested):

| Verdict | Any `CRITICAL` finding | Conclusion |
| --- | --- | --- |
| `APPROVE` | no | `success` |
| `APPROVE` | **yes** | **`failure`** |
| `COMMENT` | no | `success` |
| `COMMENT` | **yes** | **`failure`** |
| `REQUEST_CHANGES` | no | `failure` |
| `REQUEST_CHANGES` | yes | `failure` |

### A3. Complete on the failure path

`publishFailure` completes the same check run `failure`, with the Temporal UI link in
`output.summary` and `output.title` naming the failed `ReviewPhase`.

**If no check run was created** (the review died before `loadPullRequest`), nothing is patched.
The required check stays absent and blocks the merge. This is the designed fail-closed path.

---

## B. Review threads (GraphQL) — **no new permission**

Endpoint: `POST /graphql`. Token: `accessToken(installationId)`.
Permission: `pull_requests: write`, already in the minted set.

### B1. Query — existing threads with resolution state

```graphql
query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        nodes {
          id
          isResolved
          comments(first: 50) {
            nodes { body author { login __typename } }
          }
        }
      }
    }
  }
}
```

Mapped to `List<ExistingThread>` by a package-private `static` function, mirroring
`ConversationGateway.toConversation`:

- `nodeId` ← `id`
- `resolved` ← `isResolved`
- `fingerprint` ← parsed from the **first** comment's body via the marker regex
  `<!-- temporal-code-review-finding:([0-9a-f]{64}) -->`; `null` when absent
- `hasNonBotReply` ← any comment after the first with `author.__typename != "Bot"`

A GraphQL `errors` array, or a missing `pullRequest` node, throws the same non-retryable
`ApplicationFailure` shape `ConversationGateway` uses.

### B2. Mutation — resolve

```graphql
mutation($threadId: ID!) {
  resolveReviewThread(input: {threadId: $threadId}) {
    thread { id isResolved }
  }
}
```

Called only after the reply in B3 has been posted, so the resolution never lands without its
explanation.

### B3. Reply before resolving — REST

`POST /repos/{owner}/{repo}/pulls/{number}/comments/{comment_id}/replies`

```json
{ "body": "No longer reported as of `<shortSha>`." }
```

The body deliberately does **not** claim the finding was fixed: it is truthful under both a genuine
fix and a re-worded title.

Requires the root comment's **REST** id, which the GraphQL query does not return in the shape
above. Two options, both acceptable — the plan takes the second:
1. add `fullDatabaseId` to the thread's first comment in B1;
2. reply via the GraphQL `addPullRequestReviewThreadReply` mutation, keeping the whole
   reply-then-resolve pair on one transport.

---

## C. Inline finding comments (REST) — unchanged transport, changed body

`POST /repos/{owner}/{repo}/pulls/{number}/comments` — the existing `findingCommentsPath`, with the
existing `commit_id`/`path`/`line`/`side: RIGHT` payload and the existing `422 ⇒ unanchored
fallback` handling.

**The only change is the body's marker**, from the bare

```
<!-- temporal-code-review-finding -->
```

to the fingerprinted

```
<!-- temporal-code-review-finding:<sha256 hex> -->
```

**`DELETE /repos/{owner}/{repo}/pulls/comments/{id}` is removed entirely.** No code path deletes a
review comment after this change.

---

## D. Ruleset and repository settings — operator only

Not called by any code. Documented in `docs/runbooks/pr-governance.md` and performed by hand.

| Purpose | Command |
| --- | --- |
| Discover the ruleset id | `gh api /repos/{owner}/{repo}/rulesets` |
| Create (first time) | `gh api --method POST /repos/{owner}/{repo}/rulesets --input .github/rulesets/main.json` |
| Update | `gh api --method PUT /repos/{owner}/{repo}/rulesets/{id} --input .github/rulesets/main.json` |
| Drift check | `gh api /repos/{owner}/{repo}/rulesets/{id}` and diff against the committed file |
| Repo settings | `gh api --method PATCH /repos/{owner}/{repo} -F allow_auto_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false` |

**Committing `.github/rulesets/main.json` does not apply it.** Applying it before a `Code Review`
check has been observed makes that required check permanently absent, blocking every pull request
including the one that would fix it. With `bypass_actors: []`, recovery means hand-editing the
ruleset in the GitHub UI.
