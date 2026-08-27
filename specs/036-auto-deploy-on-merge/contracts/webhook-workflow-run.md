# Contract: the `workflow_run` trigger

No new HTTP surface. `POST /webhooks/github` — the existing exact-match route,
the only path on `software-factory` reachable from the internet — gains a branch.

## Request

Unchanged headers: `X-Hub-Signature-256`, `X-GitHub-Event`, and the raw body.
`WebhookSignatureVerifier.isValid` runs first and unchanged, so an unsigned or
mis-signed delivery still gets `401 {"status":"invalid"}` before any of this is
evaluated.

## Accepted only when all of these hold

| Condition | Source in payload |
| --- | --- |
| event is `workflow_run` | `X-GitHub-Event` header |
| `factory.deploy.trigger-enabled` is true | configuration, not payload |
| workflow name is `Publish` | `workflow_run.name` |
| conclusion is `success` | `workflow_run.conclusion` |
| head branch is `main` | `workflow_run.head_branch` |
| repository is the allowlisted one | `repository.owner.login` + `repository.name` vs `factory.deploy.owner`/`.repository` |
| head SHA is non-blank | `workflow_run.head_sha` |

`installation.id` is read when present and passed through; absent or `0` becomes
`null`, and the activity resolves the installation at run time.

Note `workflow_run` deliveries also arrive with `action: requested` and
`action: in_progress`, whose `conclusion` is `null`. Those are filtered by the
`conclusion == "success"` check alone — there is no need to test `action`, and
testing it as well would be a second condition to keep in step with GitHub.

## Responses

| Outcome | Status | Body |
| --- | --- | --- |
| Accepted | `202` | `{"workflowId":"deploy-prod","runId":"…","sha":"…"}` |
| Any condition above fails | `202` | `{"status":"ignored"}` |
| Body is not valid JSON | `400` | `{"status":"malformed"}` |
| Signature invalid | `401` | `{"status":"invalid"}` |

`ignored` rather than an error for a failed conclusion or a non-`main` branch:
GitHub retries non-2xx, and there is nothing to retry.

## Why not `pull_request` merge

Merge fires `pull_request closed` immediately, while `Publish` then spends minutes
building three ARM images. Deploying on merge would pull the *previous* `:latest`
and report success — the worst available failure mode, because it looks like it
worked. `workflow_run` completion is the only event that means the images exist.

`pull_request closed` remains routed to the feedback flow, unchanged. The two
branches are independent.

## Starting the workflow

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setWorkflowId("deploy-prod")                 // fixed, not per-SHA
    .setTaskQueue(DeployTaskQueues.DEPLOY)
    .setWorkflowIdReusePolicy(ALLOW_DUPLICATE)    // a later merge starts a new run
    .build();
BatchRequest batch = client.newSignalWithStartRequest();
batch.add(stub::run, request);
batch.add(stub::deployRequested, sha);
WorkflowExecution execution = client.signalWithStart(batch);
```

**Signal-with-start on a fixed workflow id** gives both required properties at
once:

- A duplicate delivery is inherently idempotent — the second call signals the
  running workflow instead of starting a second one.
- Two merges a few minutes apart produce **one** deploy, of the newer SHA, rather
  than two racing `docker compose up -d` runs. The cost is that a fast follow-up
  merge does not get its own run in Temporal; on a single-node Pi that is the
  right trade.

A per-SHA workflow id was rejected: it makes duplicate deliveries free but does
nothing about concurrency, which is the failure that actually matters.

## The signal handler and the drain loop

```java
@SignalMethod void deployRequested(String sha);
```

The handler records the SHA in a field. `run` deploys the SHA it was started
with, then — before returning — checks whether a newer SHA has been signalled and
loops if so. Without that drain the design's "absorbed into the one in flight
**or the one that starts next**" is only half true: a merge landing mid-deploy
would signal a workflow that never reads the field again, and its commit would
never deploy.

The loop is bounded by `Workflow.getInfo().getHistoryLength()`-style continue-as-
new hygiene only in principle — at a handful of deploys a week a single execution
will never approach the history limit, and adding continue-as-new for a case that
cannot occur would be complexity for its own sake (Principle V). The loop is
bounded in practice by there being nothing left to drain.

## Manual trigger

No HTTP endpoint. Rollout step 6 starts a run from the Temporal UI on
`temporal.simonrowe.dev` — start `DeployWorkflow` on the `deploy` task queue with
a `DeployRequest` whose `trigger` is `manual`. This is deliberate: an endpoint
would be a second way in to protect, and the UI is already behind the proxy and
already the place an operator watches a deploy from.
