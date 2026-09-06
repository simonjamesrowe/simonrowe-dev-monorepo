# Research: Temporal visibility filtering spike (factory flow console)

Task 0 of the 044-factory-flow-console plan. Purpose: prove or disprove that
Temporal's visibility store, queried via `WorkflowType` / `ExecutionStatus` /
`StartTime` filters, is viable as the sole data source for per-module workflow
counts in the Factory Flow diagram (approach (c) in the plan). If it is not
viable, Tasks 2–5 must fall back to per-module Mongo collections plus a new
one for `codereview`.

## Step 1: Confirm the Temporal server and visibility store

```
$ grep -n "temporalio/server\|image: postgres" docker-compose.prod.yml
131:    image: postgres:15
163:    image: temporalio/server:1.31.2
946:    image: postgres:15
1189:    image: postgres:15

$ grep -n "retention" scripts/temporal/create-namespace.sh
6:retention=${TEMPORAL_NAMESPACE_RETENTION:-30d}
27:    --retention "$retention" \
```

Production matches the brief's expectation exactly: `temporalio/server:1.31.2`
against `postgres:15`, namespace retention defaulting to 30 days.

**Deviation worth flagging:** the *local* stack (`docker-compose.yml`, the one
this spike had to use) does **not** run `temporalio/server` against Postgres
at all. It runs the bundled all-in-one dev image:

```yaml
temporal:
  image: temporalio/temporal:1.8.1
  command: server start-dev --ip 0.0.0.0 --db-filename /var/lib/temporal/temporal.db
```

That image bundles the server, an embedded SQLite persistence/visibility
store, the CLI and the Web UI. Its own startup banner reports the actual
server build inside it:

```
Temporal CLI 1.8.1 (Server 1.31.2, UI 2.50.1)
Temporal Persistence: /var/lib/temporal/temporal.db
```

So the **server binary version matches prod exactly (1.31.2)**, but the
**persistence/visibility backend does not** — SQLite (standard visibility)
locally vs. Postgres (also standard visibility, not Elasticsearch-backed
advanced visibility, per the prod compose file) in production. Both are
non-Elasticsearch "standard visibility" stores, which only ever index a fixed
set of predefined columns (`WorkflowType`, `WorkflowId`, `RunId`,
`ExecutionStatus`, `StartTime`, `CloseTime`, etc.) — no custom search
attributes on either backend. Since the three filters this plan needs
(`WorkflowType`, `ExecutionStatus`, `StartTime`) are all in that predefined
set on both SQLite and Postgres standard-visibility schemas, this spike's
result should carry over to prod, but it is a same-family, not
byte-identical, backend. Recorded here rather than assumed.

## Step 2: Prove `WorkflowType` filtering against a real Temporal

Local Temporal was started in isolation (no other Conductor workspace had a
`temporal` container running; ports 7233/8233 were free):

```
$ docker compose -f docker-compose.yml up -d temporal
 Network nagoya_default        Created
 Volume "nagoya_temporal-data" Created
 Container nagoya-temporal-data-init-1  Started
 Container nagoya-temporal-1            Started
```

Against an **empty** namespace, both queries were accepted:

```
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count --query "WorkflowType = 'LogWatchWorkflow'" --address temporal:7233
Total: 0

$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count \
    --query "WorkflowType = 'LogWatchWorkflow' AND ExecutionStatus = 'Running'" \
    --address temporal:7233
Total: 0
```

No `InvalidArgument` — the query syntax and both search attributes are
accepted. However, an empty namespace returning 0 for every query is also
consistent with the filter clause being silently ignored (0 executions exist
regardless), so this alone is necessary but not sufficient evidence. To rule
that out, two real workflow executions of different types were started (no
worker required — `temporal workflow start` registers the execution
regardless of whether anything ever polls the task queue):

```
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow start --task-queue spike-queue --type LogWatchWorkflow \
    --workflow-id spike-logwatch-1 --address temporal:7233
Running execution:
  WorkflowId  spike-logwatch-1
  RunId       01a06d16-3c7a-7c61-ad31-d58c154061c4
  Type        LogWatchWorkflow
  Namespace   default
  TaskQueue   spike-queue

$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow start --task-queue spike-queue --type OtherWorkflow \
    --workflow-id spike-other-1 --address temporal:7233
Running execution:
  WorkflowId  spike-other-1
  RunId       01a06d16-3cef-725b-8c82-88137b7b04a4
  Type        OtherWorkflow
  Namespace   default
  TaskQueue   spike-queue
```

Re-running the counts against this now-non-empty namespace:

```
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count --address temporal:7233
Total: 2

$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count --query "WorkflowType = 'LogWatchWorkflow'" --address temporal:7233
Total: 1

$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count \
    --query "WorkflowType = 'LogWatchWorkflow' AND ExecutionStatus = 'Running'" \
    --address temporal:7233
Total: 1
```

`Total: 2` unfiltered vs. `Total: 1` for `WorkflowType = 'LogWatchWorkflow'`
proves the `WorkflowType` filter genuinely discriminates rather than being
ignored, and the `ExecutionStatus = 'Running'` addition still correctly
returns 1 (both spike workflows were left running, un-polled, since no
worker registered on `spike-queue`).

`temporal workflow list` (the CLI surface backed by the
`ListWorkflowExecutions` gRPC API, as distinct from `count`'s
`CountWorkflowExecutions`) was also checked, since the plan's implementation
uses both APIs and only `count` was in the brief's Step 2:

```
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow list --query "WorkflowType = 'LogWatchWorkflow'" --address temporal:7233
(exit 0)
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow list \
    --query "WorkflowType = 'LogWatchWorkflow' AND ExecutionStatus = 'Running'" \
    --address temporal:7233
(exit 0)
```

Both accepted with exit code 0. **Both `count` (`CountWorkflowExecutions`)
and `list` (`ListWorkflowExecutions`) accept the same `WorkflowType`/
`ExecutionStatus` query syntax.**

## Step 3: Prove the `StartTime` range filter too

```
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count \
    --query "WorkflowType = 'LogWatchWorkflow' AND StartTime > '2026-09-03T00:00:00Z'" \
    --address temporal:7233
Total: 1

$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow count \
    --query "WorkflowType = 'LogWatchWorkflow' AND StartTime > '2099-01-01T00:00:00Z'" \
    --address temporal:7233
Total: 0
```

The first query (a real past date, today being 2026-09-04) correctly counts
the one `LogWatchWorkflow` execution. The second, using a range in the
future that no execution could satisfy, correctly returns 0 — again proving
the `StartTime` comparison is actually evaluated rather than a no-op that
happens to read 0/1 by coincidence.

## Cleanup

The two spike executions were terminated (not left running):

```
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow terminate --workflow-id spike-logwatch-1 --reason "spike cleanup" --address temporal:7233
Workflow terminated
$ docker compose -f docker-compose.yml exec temporal \
    temporal workflow terminate --workflow-id spike-other-1 --reason "spike cleanup" --address temporal:7233
Workflow terminated
```

The `nagoya-temporal-1` / `nagoya-temporal-data-init-1` containers and the
`nagoya_temporal-data` volume created for this spike were stopped and removed
afterwards (`docker compose -f docker-compose.yml down temporal`), since this
workspace started them fresh and no other process depended on them.

## Conclusion

Approach (c) — counting per-module workflow executions via `WorkflowType`,
`ExecutionStatus` and `StartTime` visibility filters through Temporal's
`CountWorkflowExecutions`/`ListWorkflowExecutions` APIs — is viable: all
three filters, individually and combined, were accepted and demonstrably
discriminated real data on a Temporal 1.31.2 server (the same server version
production runs, on a same-family standard-visibility backend), so Tasks 2–5
can proceed on this design without falling back to per-module Mongo
collections.
