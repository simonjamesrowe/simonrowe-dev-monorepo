# Review Lifecycle Visibility — Design

**Date:** 2026-08-11
**Status:** Approved for planning
**Module:** `software-factory` (`codereview`)

## Problem

A failed code review usually posts nothing to the pull request, so a broken
reviewer is indistinguishable from one that was never triggered.

Measured on 2026-08-11 across the seven pull requests opened since the reviewer
first worked (#94, 2026-08-02): **three received nothing at all** (#95, #97,
#100), and **not one failure notice has ever been posted** despite the
`publishFailure` path existing since #98. Temporal told the real story — 21
workflows, 5 completed, 16 failed:

| Pull request | Failure | Posted? |
| --- | --- | --- |
| #95, #97 (Aug 9) | `Claude exited with 1: ` (blank) at `ClaudeCliReviewEngine:111` | no — the notice did not exist yet |
| #100 ×3, `agent-setup` ×4 (Aug 11) | `GitHub App token endpoint returned 422` in `GitHubCredentials.mintInstallationToken` | no — see below |

The second group is the instructive one. `CodeReviewWorkflowImpl.reportFailure`
runs only when `request.publish()` **and `pullRequest != null`**. That 422 is
thrown inside `loadPullRequest`, so `pullRequest` is still null and the entire
failure-reporting path is skipped. The most common real failure mode is
structurally unreportable.

Three further gaps follow from the same shape:

- **In-progress and never-started look identical.** A quiet PR could mean the
  agent is 15 minutes into a review, or that no poller was ever registered.
- **Failure messages are undiagnosable.** `safeFailureMessage` yields a bare
  string with no phase and no route to the workflow history.
- **Credential faults discard the useful part.** `mintInstallationToken` throws
  `IllegalStateException("GitHub App token endpoint returned " + statusCode)`,
  dropping GitHub's response body, which names the offending permission — and
  as an `IllegalStateException` it retried three times before failing.

## Goals

- Every pull request the reviewer accepts gets a visible acknowledgement, before
  anything can fail.
- Every terminal outcome — success or failure — is visible on the pull request.
- A failure comment carries enough to diagnose without shell access: phase,
  reason, and a link to the Temporal history.
- Total silence on a PR narrows to exactly one meaning: the workflow never
  started.

## Non-goals

- **Detecting that the workflow never started.** The ack makes this
  *diagnosable* rather than covered. A poller pre-check in the webhook receiver
  is deliberately deferred — Temporal proves workflows are starting, so it has
  never been the problem.
- **A non-GitHub alerting channel.** See Accepted limitation.
- **Changing what the reviewer reviews**, its prompt, or its verdicts.
- **A self-imposed workflow deadline.** Considered and rejected: the agent
  activity already carries `StartToCloseTimeout(20m)` with `maxAttempts(1)`, so
  a hung agent surfaces as an `ActivityFailure` inside the workflow and the
  catch block runs. No `WorkflowExecutionTimeout` is configured, so Temporal
  never terminates the execution from outside. An `Async`/`Promise.anyOf` race
  would add complexity and cover only manual termination.

## Decisions (from brainstorming)

1. **The ack is posted by the workflow, as its first activity** — not by the
   webhook receiver. Keeps all GitHub I/O behind activities and off the webhook
   request path.
2. **One comment, edited in place.** Success posts the review and deletes the
   ack; failure `PATCH`es the ack into the failure notice. Two separate comments
   were rejected as too noisy — four pushes would leave eight bot comments.
3. **Failure detail includes phase, workflow id and a Temporal deep link**,
   with `https://temporal.simonrowe.dev` as the configured default.

## Accepted limitation

**A credential fault still cannot be reported on the pull request** if it
prevents minting any token at all — commenting needs a token too. This is not
hypothetical: it is exactly the 422 outage above.

Mitigated, not solved, by requesting **no `permissions` override** when minting
the token used for lifecycle comments. Omitting the block yields a token
carrying the installation's full granted set, which cannot 422 on
over-request. So the ack and failure paths survive the review path asking for
more than the App was granted — which is the realistic failure — but not the App
being uninstalled or its key being wrong. Those remain Temporal-only.

## 1. Ack activity (new)

```java
@ActivityMethod
String publishAck(ReviewRequest request);   // returns the issue comment id
```

`POST /repos/{owner}/{repo}/issues/{pullNumber}/comments`, body rendered by
`ReviewMarkdownRenderer.renderAck`, carrying the existing marker so a later run
for the same head SHA is identifiable.

- Uses `ReviewRequest` only — `owner`, `repository`, `pullNumber`,
  `installationId`, `expectedHeadSha` are all present. No `PullRequestContext`,
  which is what makes it possible to post *before* `loadPullRequest`.
- Skipped entirely when `!request.publish()`.
- **A failure to ack must not fail the review.** The workflow wraps it the way
  `reportFailure` is wrapped today: catch, log, carry a null comment id.

Comments land on the *issue* comments resource deliberately. That is where the
reviewer's output already appears in practice — GitHub normalises a `COMMENT`
review with no anchored inline comments into a conversation comment — and it is
the only resource that supports `PATCH` and `DELETE`.

## 2. Credential path for lifecycle comments

`GitHubCredentials` gains a second mint path that sends **no `permissions`
object**, cached separately from the review token:

```java
public String commentToken(Long installationId);   // installation's full grant
```

Used by the three lifecycle-comment calls only — `publishAck`'s `POST`, the ack
`PATCH` inside `publishFailure`, and the ack `DELETE` in `resolveAck`.
`accessToken` is unchanged for everything else, including `publishReview`.

Also in `mintInstallationToken`, for both paths:

- Include GitHub's response body, truncated, in the failure message.
- Throw `ApplicationFailure.newNonRetryableFailure(..., "GITHUB_TOKEN_REJECTED")`
  for `4xx`. A 422 from an over-broad permission request is not transient and
  retrying it three times only delays the report. `5xx` stays retryable.

## 3. Workflow changes

`CodeReviewWorkflowImpl.review` gains `ackCommentId` as local state:

```
publishAck (best-effort)  →  ackCommentId
loadPullRequest
runReview
publish:  publishReview(pullRequest, report)      then  resolveAck(request, ackCommentId)
failure:  publishFailure(request, ackCommentId, ReviewFailure)
```

- `resolveAck` = `DELETE /repos/{o}/{r}/issues/comments/{id}`, best-effort. Post
  the review *first*: a failed delete leaves a stale ack beside a real review,
  which is obvious and harmless, whereas deleting first and then failing to post
  loses both.
- `publishFailure` changes signature from `(PullRequestContext, String)` to
  `(ReviewRequest, String ackCommentId, ReviewFailure)`. It `PATCH`es the ack
  when `ackCommentId != null`, otherwise `POST`s a fresh comment — so a review
  whose ack never landed still reports.
- The `pullRequest != null` guard on `reportFailure` is **removed**. That guard
  is the defect.

New record, so the failure payload is not a bare string:

```java
public record ReviewFailure(ReviewPhase phase, String reason, String workflowId) { }
```

`safeFailureMessage` keeps its unwrap-to-innermost-cause behaviour; `phase` comes
from the `current` `ReviewProgress` the workflow already maintains.

## 4. Rendering

`ReviewMarkdownRenderer` gains:

- `renderAck(String marker)` — "🔄 Automated code review in progress…" plus the
  advisory-only line.
- `renderFailure(ReviewFailure, String marker, String temporalUiBaseUrl)` —
  replaces the current `renderFailure(String, String)`. Keeps the fenced,
  fence-safe reason; adds a **Phase:** line and a link:

```
[Workflow history](<base>/namespaces/default/workflows/<workflowId>) · `<workflowId>`
```

The link is omitted entirely when the base URL is blank, so the reason is never
lost to a formatting concern.

## 5. Configuration

```yaml
factory:
  codereview:
    temporal-ui-base-url: ${TEMPORAL_UI_URL:https://temporal.simonrowe.dev}
```

New `String temporalUiBaseUrl` on `CodeReviewProperties`. No new
`docker-compose.prod.yml` entry — the default is correct for production, and
`software-factory` deliberately has no `env_file`.

## 6. Testing

TDD throughout, following the module's existing patterns.

`CodeReviewWorkflowTest` (`TestWorkflowEnvironment`):

- ack posted before `loadPullRequest`
- ack deleted on success, after the review is published
- ack `PATCH`ed on failure
- **a `loadPullRequest` failure still publishes a failure notice** — the
  regression test for the #100 outage
- a failure with no ack id posts a fresh comment
- an ack activity that throws does not fail the review
- `publish: false` posts, patches and deletes nothing

`GitHubGatewayTest`: ack `POST`, `PATCH` and `DELETE` paths and payloads;
failure payload shape.

`GitHubCredentialsTest`: `commentToken` sends no `permissions` object;
`accessToken` still does; a 422 is non-retryable and its message carries the
response body.

`ReviewMarkdownRendererTest`: ack body; failure body with and without the
Temporal link; fence-safety preserved.

## 7. Rollout

No GitHub App permission change. GitHub governs comments on a pull request by the
**`pull_requests`** permission, not the `issues` one, even though they are posted
to the issue comments endpoint (see `docs/runbooks/software-factory.md`) —
`pull_requests: write` is already granted and is what `POST`, `PATCH` and
`DELETE` here require. Nothing else is needed beyond deploying the image, which
`redeploy.services` now covers.

This also means the no-`permissions`-override token in §2 is sufficient for every
lifecycle comment: the installation's full grant already includes
`pull_requests: write`.

Verification: push to any open PR and expect an ack within seconds, then either a
review with the ack gone, or the ack replaced by a failure naming its phase.

## 8. Out of scope, worth tracking separately

- **A non-GitHub failure signal.** The one class of failure this design cannot
  surface is a credential fault severe enough to block commenting. Today the only
  detector is a human opening Temporal.
- **A poller pre-check** in the webhook receiver, if silence recurs after this
  ships.
