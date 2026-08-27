# Research: Linear issue sink

**Date**: 2026-08-27
**Design**: `docs/superpowers/specs/2026-08-27-linear-issue-sink-design.md`
**Plan**: `docs/superpowers/plans/2026-08-27-linear-issue-sink.md`

Probed against the live Linear API with a team-scoped personal key, team `SIM`
(`Simonrowedev`). Two throwaway probe issues (SIM-5, SIM-6) were created and deleted.

## Item 1 — does `attachmentsForURL` return issues in `canceled` state? **YES**

This was the item that could invalidate the design. It holds.

```graphql
query($u:String!){attachmentsForURL(url:$u){nodes{issue{identifier state{type} canceledAt}}}}
```

After moving SIM-5 to the `Canceled` state, the same query still returned it:

```json
{"id":"SIM-5","type":"canceled","completedAt":null,"canceledAt":"2026-08-27T12:54:38.280Z"}
```

**Decision: the primary design stands.** Linear owns issue identity *and* state; the
Mongo-as-index fallback is not needed. Suppression works: a declined issue stays
findable by fingerprint forever, so the sink can keep quiet about it.

## Item 2 — may one attachment URL be attached to two issues? **YES**

The regression path deliberately reuses one fingerprint URL across a completed issue
and its successor. `attachmentCreate` accepted the same URL on both SIM-5 and SIM-6,
and `attachmentsForURL` returned **both** attachments with their distinct issues.

**Decision: the regression path is viable as designed.** `FilingDecider`'s
precedence rules are what disambiguate a multi-issue result, which is why they are
defined rather than assumed.

## Item 3 — does `attachmentCreate` require a resolvable URL? **NO**

`https://factory.simonrowe.dev/fingerprint/probe0827` was accepted, and that host does
not resolve. **Decision: the synthetic key URL works.** It is a key, not a link.

## Item 4 — API key scopes

A key with **Read + Write + Create issues + Create comments**, limited to one team,
successfully performed `issueCreate`, `attachmentCreate`, `commentCreate`,
`issueUpdate`, `issueLabelCreate`, `issueRelationCreate` and `issueDelete`.

**`teamUpdate` also succeeded**, which means the key carries Admin scope — more than
the sink needs at runtime. **Recommendation: mint a second, narrower key for
production** (Read + Write + Create issues + Create comments, team-limited, no Admin)
and use that one in the prod `.env`. Nothing in the sink calls `teamUpdate`.

## Item 5 — filing into Triage

Triage is an `issueCreate` with the team's `triage`-type state id. There is no separate
mechanism.

`Triage` had to be **enabled on the team first** — `triageEnabled` was `false` and the
team had no `triage`-type state at all, so an `issueCreate` naming one was impossible.
Enabled via `teamUpdate(input:{triageEnabled:true})`, after which the state appeared.

**Decision: resolve the triage state id at runtime by `type == "triage"`, and fail
loudly and non-retryably when the team has none** — that is a configuration error a
human must fix, and silently filing into the backlog instead would strand tickets
outside the inbox the whole design depends on.

## Item 6 — priority scale

```json
[{"priority":0,"label":"No priority"},{"priority":1,"label":"Urgent"},
 {"priority":2,"label":"High"},{"priority":3,"label":"Medium"},{"priority":4,"label":"Low"}]
```

**Decision: unchanged.** `deploy` files at `1` (Urgent), `cvefix` at `3`. Note the label
is "Medium", not "Normal" as the design's prose called it.

## Item 7 — does an activity-only Temporal task queue get a worker? **YES**

`io.temporal:temporal-spring-boot-starter:1.36.0`. In
`WorkersTemplate.configureActivityBeansByTaskQueue`, for each `@ActivityImpl` bean and
each task queue it names, the template calls `workerFactory.tryGetWorker(taskQueue)`
and, when that returns null, creates one:

> "Creating a worker with default settings for a task queue '{}' caused by an
> auto-discovered activity class {}"

Activity discovery is `beanFactory.getBeansWithAnnotation(ActivityImpl.class)`, gated by
`register-activity-beans` — **already `true`** in `application.yml` — and is
independent of `workflow-packages`.

**Decisions:**
- **No `FileIssueWorkflow` stub is needed.** The Task 9 fallback is not required.
- **Do NOT add `com.simonrowe.factory.linear.workflow` to `workflow-packages`.** That
  list drives `@WorkflowImpl` scanning only; an entry for a package containing no
  workflow does nothing and falsely implies one lives there. Task 9 carries an
  explanatory comment instead.
- The `temporal task-queue describe --task-queue linear` check still expects **one
  activity poller and zero workflow pollers**.

## Unplanned finding — Linear ships a `duplicate` state type, and it is cancelled-family

Not in the plan, and it changes committed code. Team `SIM` has a `Duplicate` state of
`type: "duplicate"` out of the box, alongside `canceled`.

Moving an issue there requires a duplicate relation first
(`"Issues can only be moved to a duplicate state when a duplicate issue relation
exists."`). Once moved, Linear sets **`canceledAt`, not `completedAt`**:

```json
{"id":"SIM-6","type":"duplicate","completedAt":null,"canceledAt":"2026-08-27T12:54:56.633Z"}
```

`IssueStateType` as committed in `e1c739d` has no `DUPLICATE` constant, so `"duplicate"`
maps to `UNKNOWN`, which classifies as **open** — the sink would keep appending
occurrences to a ticket someone had closed as a duplicate, where nobody would read them.

**Decision: add `DUPLICATE(false)` and place it in the same precedence band as
`CANCELED` (suppress).** Linear's own `canceledAt` is the authority for that grouping,
and marking a machine-filed ticket a duplicate means "already tracked elsewhere",
which is a decline.

This is precisely the failure mode the Task 4 review's third finding predicted —
verbatim, it warned about "adding a closed constant that is not CANCELED
(`DUPLICATE(false)`, `ARCHIVED(false)`)". The `everyStateIsOpenOrCompletedOrCanceled`
guard test it produced will fail the moment `DUPLICATE` is added, which is exactly its
job. It found a real defect within hours of being written.

## Provisioned during this spike

- Triage enabled on team `SIM`.
- Labels `factory:deploy` and `factory:cvefix` created.
- Probe issues SIM-5 and SIM-6 created and deleted; `attachmentsForURL` for the probe
  fingerprint now returns zero nodes.

**No ids are recorded above, deliberately — and nothing hardcodes them anyway.** The gateway
resolves the team, its triage state and its label ids at runtime from the team key, so the only
configuration production needs is the human team key (`SIM`) and the API key; writing UUIDs into
a spec would be both useless and a small secret-hygiene liability.
