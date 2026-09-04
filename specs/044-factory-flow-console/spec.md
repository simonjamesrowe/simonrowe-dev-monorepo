# Feature Specification: Factory Flow — the console as a loop diagram

**Feature Branch**: `044-factory-flow-console`

**Created**: 2026-09-04

**Status**: Draft

**Input**: User description: "This is a diagram I want to take inspiration from for the software
factory. Obviously in the software factory that we're trying to design here, there are many
feedback loops around after something has had a pull request review, around the agent setup, as
well as monitoring the logs and stuff like that. I'm wondering if we could help design something
like this, which could sit in the admin interface for Software Factory. I think the best source for
the data will either be temporal or linear. It would be good to have that visual diagram within the
admin interface, but be able to click through each particular thing."

## Summary

`/admin/software-factory` stops being a stack of seven module cards and becomes a **diagram of the
factory as a set of loops**, with every node clickable. The seven modules and the five artifacts
they exchange are drawn as one ring; counts on each node come from Temporal, Linear and GitHub;
clicking a node opens a drawer holding that node's status, recent runs and — for modules — the
trigger buttons that currently live on the cards.

The reference image the user supplied is a left-to-right pipeline of *work item* states. This spec
deliberately does **not** copy it, for one reason: the factory has no work items. It has runs of
scheduled modules. What it does have, and what nothing currently shows, is a set of feedback loops
that a linear pipeline cannot draw.

## The diagnosis this feature exists to make visible

The factory today is **a closed loop with exactly one gap in it**.

`logwatch` and `cvefix` observe production and file Linear tickets. `codereview` gates pull
requests. `deploy` ships merges and triggers `logwatch` five minutes later. `feedback` harvests
closed reviews and opens guidance pull requests against `agent-setup`, which shapes how the agents
behave next time. Every arrow in the ring exists **except the one that turns a Linear ticket into a
pull request**.

That missing arrow is the build agent (`specs/045-build-agent/`). This console ships the `build`
node as *declared but unstaffed*, which is not a placeholder — an empty node on a ring that is
otherwise complete is the clearest possible statement of what is missing.

## Scope

**In scope**: the seven existing modules, the artifacts between them, node-level counts and health,
per-node drawers, and relocating the existing trigger actions into those drawers.

**Out of scope**, deliberately:

- Any change to the seven modules themselves. This feature reads; it does not edit a module.
- A new persistence layer for run history. See "Data source" below — the whole point of the chosen
  approach is that it needs none.
- Editing the graph. The topology is a property of the code, not of configuration.
- Public visibility. This is behind admin auth; `GET /api/factory/status` stays unauthenticated and
  unchanged.

## The graph

### Nodes

Twelve nodes in five bands. Seven module nodes (`linear` is not one — see below) and five
artifact nodes.

| Band | Nodes | Kind |
| --- | --- | --- |
| OBSERVE | `logwatch`, `cvefix` | module |
| PLAN | `Linear` | artifact |
| BUILD | `build`, `Pull request`, `codereview` | module, artifact, module |
| SHIP | `main`, `deploy`, `Production` | artifact, module, artifact |
| LEARN | `feedback`, `agent-setup` | module, artifact |
| *(utility)* | `platformbackup` | module, attached to Production |

Two resolutions are load-bearing:

**The `linear` module is not a node.** It is a sink with no `@WorkflowInterface` at all — the
factory's only activity-only task queue. Drawing it as a box would put a node on the canvas that
nothing flows *through*. Its health (activity poller count, `LINEAR_API_KEY` /
`FACTORY_LINEAR_TEAM_KEY` prerequisites) renders as a badge **on the Linear artifact node** instead.
One node, two facets: what is in Linear, and whether the thing that writes to Linear is working.

**`platformbackup` is deliberately off the ring.** It participates in no loop. Drawing it on one
would be a lie, so it hangs off `Production` as an unconnected utility node.

### Edges, and the three loops

Every edge carries a `loop` classification, and the three loops are drawn with different weight so
they can be told apart at a glance.

**Fast loop — minutes.**

- `Pull request → codereview` (webhook on push)
- `codereview → Pull request` (findings posted, threads reconciled, `Code Review` check run set)

**Main loop — hours.**

- `Production → logwatch` (reads Grafana Cloud Loki)
- `logwatch → Linear` (files a signature)
- `main → cvefix` (Publish uploads image and manifest SBOMs to Dependency-Track, which `cvefix`
  reads — without this edge `cvefix` is drawn as a source with no input, which is wrong: it is
  downstream of a merge just as `logwatch` is downstream of a deploy)
- `cvefix → Linear` (files the consolidated current-vulnerabilities ticket)
- `Linear → build` (**the approve edge** — a human applies `factory:build`)
- `build → Pull request`
- `Pull request → main` (merge)
- `main → deploy` (Publish `workflow_run` webhook)
- `deploy → Production`
- `deploy → logwatch` (post-deploy scan, +5 minutes)
- `deploy → Linear` (failed deploy files a ticket)

**Slow loop — days, drawn dashed and outermost.**

- `Pull request → feedback` (on PR close)
- `feedback → agent-setup` (guidance pull request)
- `agent-setup ⇢ build`, `agent-setup ⇢ codereview` (shapes agent behaviour next run)

The slow loop is the one no existing view shows and the one the user named first. It is why the
graph is a ring rather than a pipeline.

## Data source

### Approaches considered

**(a) A unified `factory_runs` collection** written by every module. Best long-term queryability,
but it edits all seven modules for the sake of a dashboard. **Rejected on blast radius.**

**(b) Read the five existing collections** — `logwatch_runs`, `cve_fix_runs`, `deploy_runs`,
`linear_issues`, `review_learnings`. Looks cheap, but **`codereview` has no run collection**: the
busiest module on the page would need one invented plus a Mongock change unit. **Rejected.**

**(c) Chosen — Temporal `ListWorkflowExecutions`.**

Six of the seven modules have exactly one `@WorkflowInterface` each: `CodeReviewWorkflow`,
`ReviewFeedbackWorkflow`, `CveFixWorkflow`, `DeployWorkflow`, `PlatformBackupWorkflow`,
`LogWatchWorkflow`. Querying by `WorkflowType` + `ExecutionStatus` + `StartTime` yields in-flight,
succeeded-24h and failed-24h counts uniformly across all six — **including `codereview`, which has
no persistence of its own** — with zero new collections and zero edits to any module.

Namespace retention is 30 days (`scripts/temporal/create-namespace.sh:6`), ample for a live board.
Temporal's visibility store is Postgres 15, so advanced-visibility filtering is available.

The leftovers under (c) are small and each has an obvious home:

| Node | Source |
| --- | --- |
| six module nodes | Temporal `ListWorkflowExecutions` by `WorkflowType` |
| `Linear` node | existing `linear_issues` collection + the `linear` module's poller status |
| `build` node | Linear + GitHub only — see `specs/045-build-agent/` |
| `Pull request`, `main` | GitHub API |
| `Production` | the existing platform status the console already renders |
| `agent-setup` | GitHub API, open `agent-feedback`-labelled pull requests |

### Risk to retire on day one

Confirm `WorkflowType` filtering works against this deployment's visibility store **before**
building on it. If it does not, the fallback is approach (b) plus inventing a `codereview` run
collection, which is materially more work. This is a spike, not a late discovery.

## API

One new endpoint on `software-factory`:

```
GET /api/factory/flow          (unauthenticated, same terms as /api/factory/status)
```

**Revised 2026-09-04, during implementation.** The original design said token-protected, on the
premise that `/flow` "carries Linear ticket titles and pull request subjects". That premise is
wrong for this endpoint: it returns node keys and labels from a fixed topology, integer counts, and
`diagnostic` strings of exactly the same kind `/api/factory/status` already serves openly from both
containers. Titles and identifiers appear only at `GET /api/factory/flow/{nodeKey}`, which **is**
token-protected.

Leaving `/flow` open resolves a problem the token would otherwise create. Task 4 established that
`FactoryTokenAuthenticator` is not a Spring Security filter at all — it is a plain `@Component`, and
each protected controller calls `authenticate(token)` as the first line of its handler. The
`deployer` deliberately holds no `FACTORY_TRIGGER_TOKEN`, and it is the authority on the `deploy`
and `platformbackup` nodes. Token-protecting `/flow` would therefore have forced either a
role-conditional authentication bypass, or handing the socket-holding container a credential that
also authorises `/api/reviews` — the same trap documented on `FactoryStatusController`. Neither is
worth paying for information the adjacent endpoint already gives away. Both endpoints are unrouted
by nginx regardless; only `POST /webhooks/github` is routed.

The backend proxies it at:

```
GET /api/admin/software-factory/flow      (admin auth)
```

The backend **must ask both containers**, exactly as `FactoryAdminService` already does, because
`deploy` and `platformbackup` are deployer-owned and the deployer is the authority on them.

Response shape:

```json
{
  "fetchedAt": "...",
  "nodes": [
    {
      "key": "logwatch",
      "kind": "module",
      "band": "OBSERVE",
      "label": "Log watch",
      "counts": { "inFlight": 0, "ok24h": 2, "failed24h": 0 },
      "health": "READY",
      "diagnostic": null
    }
  ],
  "edges": [
    { "from": "logwatch", "to": "linear", "label": "files signature", "loop": "main" }
  ]
}
```

`health` is one of `READY`, `DEGRADED`, `DISABLED`, `UNAVAILABLE`, `OFFLINE`, `IDLE` — reusing the
existing per-module conjunction of flag, poller and prerequisites, and adding `OFFLINE`/`IDLE` for
the `build` node.

## Interaction

**Clicking a node opens a right-side drawer**, matching the Media Library pattern already used in
the admin CMS.

- **Module node** — flag state, task queue, poller counts, missing prerequisites, schedule summary,
  the last ~10 Temporal runs (id, status, phase, started, duration), and **the module's trigger
  buttons, moved in from the current cards**.
- **Artifact node** — the list. Open Linear tickets by state; open pull requests with their three
  signals; recent merges to `main`.

**The seven module cards are removed in the same change.** Keeping both would put two
representations of the same fact on one page, which is the failure mode that made the "cards stay"
option unattractive.

The diagram gains the refresh control from the reference image — `Off / 15s / 1m / 5m` — which earns
its place during a deploy.

## Rendering and accessibility

Plain CSS with BEM naming and hand-laid-out SVG on a fixed grid. **No graph library and no force
layout.** The topology is fixed and known, and nodes that shift position between renders defeat the
entire purpose of a diagram you are learning to read at a glance.

**Accessibility is the part most easily got wrong here.** An SVG graph is not navigable. So:

- Every node **also** renders as a real `<button>`, in DOM order following the main loop.
- The SVG is `aria-hidden` decoration layered over those buttons.
- Keyboard and screen-reader users therefore traverse the ring in sequence and reach the same
  drawers.
- Below ~50rem the SVG is dropped entirely and the buttons stand alone as the five bands — which is
  the mobile layout for free.

## Testing

- **A test pinning the node and edge set**, so adding an eighth module without adding it to the
  graph fails the build. Same spirit as `FactoryAdminService.ORDER` being authoritative today: a
  module missing from that list is silently dropped from the console, and this graph must not
  acquire the same failure mode.
- `FactoryFlowService` unit tests over a stubbed Temporal client, a stubbed Linear client and a
  stubbed GitHub client. Note `software-factory` HTTP stub tests are known to flake on port and
  connection reuse — re-run isolated three times before attributing a failure to a change.
- Backend proxy tests covering the both-containers fan-out and the deployer-unreachable case, where
  `deploy` and `platformbackup` must report unavailable rather than being taken from
  `software-factory`'s own switched-off view of them.
- Frontend Vitest: node counts render, drawer opens per node, **keyboard traversal order follows the
  main loop**, the sub-50rem collapse, and **every node has a unique accessible name** — the
  precedent being the "Dry run" / "Scan now" label collision that had to be fixed in 042.

## Risks

1. **Temporal `WorkflowType` visibility filtering** must be confirmed before the design rests on it.
   Day-one spike.
2. **`platformbackup` off the ring** may read as an oversight rather than a statement. The drawer
   and a short caption need to say why.
3. **The `build` node ships empty.** Intended, but it must render as `IDLE`/`OFFLINE` with an
   explanatory drawer, never as an error.
