# Phase 0 Research: Log watch

Resolves the NEEDS CLARIFICATION items from the plan's Technical Context, plus the single Open
Question the spec added with the liveness requirement.

---

## R1. How does the module prove its source is alive? (the spec's open question)

**Decision**: two tiers, checked in order, with the module reporting *which* tier answered.

1. **Alloy component health, when reachable.** Alloy exposes an HTTP server on `:12345`
   (`--server.http.listen-addr=0.0.0.0:12345` in the compose file). Its component API reports the
   health of `loki.write.grafana_cloud` directly, including the last error — which during the
   August outage was the `429 ... limit: 0 bytes/sec` that names the problem exactly. This is
   **evidence**, not inference.
2. **Container-coverage inference, otherwise.** Query the distinct `container` label values present
   in the window and compare against a configured floor. If Loki reports lines from fewer than
   `minimum-containers` distinct containers (default 3) over a window of at least an hour, the
   source is `SILENT`.

**Rationale**: tier 1 is strictly better — it distinguishes `429` (quota), `401` (credential) and
a healthy-but-quiet stack, all of which need different fixes and all of which look identical from
the query side. But it requires a network route to Alloy's port that **does not exist today**:
`alloy` publishes no host port and nginx routes nothing to it. Both containers are on the same
compose network, so `http://alloy:12345` is reachable from `software-factory` with no compose
change at all — this needs verifying on the host before Phase 3, and is the one assumption here
that has not been executed live.

Tier 2 needs nothing new and works even if tier 1 is unavailable or Alloy itself is down (which is
itself a source-health failure). Its weakness is the threshold: too high and a genuinely quiet
night files a false alarm; too low and a partial outage passes. Three containers is defensible
because `config/alloy/config.alloy` already drops the four chattiest services and the remaining
`backend` alone ships ~17 MB/day — but this is exactly the kind of number that should be checked
against a week of real data (Phase 5), not defended on first principles.

**Alternatives considered**:

- *A single always-chatty canary container* (`backend` has lines, or the source is dead). Simplest,
  and rejected only narrowly: it fails wrongly when `backend` alone is restarted or quiet, and a
  one-container quorum makes the check as fragile as the thing it watches.
- *A synthetic heartbeat log line*, emitted by the factory itself once a minute and asserted on.
  Unambiguous — its absence is proof, not inference. Rejected for v1 because it means writing to
  the log pipeline to test the log pipeline, and it would be shipped and stored like any other
  line. Worth revisiting if tier 2's threshold proves unreliable.
- *Querying Grafana Cloud's usage/billing API for the tenant's ingest limit.* This would have
  caught the exact August failure most directly. Rejected: the access-policy token in `.env` is
  scoped `logs:read`/`logs:write` and cannot read org billing, so it needs a second credential of
  a broader kind in the same container — the opposite of the confinement rule NFR-002 exists for.

**Reporting**: `SourceHealth` carries the tier that answered and the evidence string, so a filed
ticket says "Alloy reports loki.write unhealthy: 429 ingestion rate limit exceeded (limit: 0
bytes/sec)" rather than "no logs found". The first is actionable in one read; the second sends
someone to check credentials that are fine.

---

## R2. Which HTTP client for Loki?

**Decision**: Spring `RestClient`, constructed in `LogWatchBeans` with a per-module base URL and
basic auth.

**Rationale**: synchronous, already on the classpath via `spring-boot-starter-web`, no new
dependency, and testable with `MockRestServiceServer`. The activity runs on a Temporal worker
thread that is already blocking, so nothing is gained from a reactive client.

**Alternatives considered**: `WebClient` (drags in webflux for no benefit here); the raw
`java.net.http.HttpClient` (workable, but hand-rolls JSON decoding that `RestClient` does via the
existing `ObjectMapper`).

**Gotcha, verified live on 2026-08-31**: `GRAFANA_CLOUD_LOKI_ENDPOINT` in `.env` **already
includes** `/loki/api/v1` and ends in `/push`. The query base is that value with `/push` stripped —
appending `/api/v1/...` to it yields `/loki/api/v1/api/v1/...` and a bare `404 page not found` with
no JSON body. This cost a round of debugging during diagnosis and belongs in the client, not in
each caller.

Auth is HTTP basic: username `GRAFANA_CLOUD_LOKI_USER` (the numeric tenant id), password
`GRAFANA_CLOUD_API_KEY`. The same key carries `logs:write` (used by Alloy) and `logs:read`.

---

## R3. Severity detection across log formats (FR-002)

**Decision**: an ordered list of format-specific matchers, first match wins, defaulting to
"not a candidate" rather than to `WARN`.

**Rationale**: FR-002 explicitly forbids assuming one format, and the stack proves the point —
nginx access lines have no level at all, the JVM services log `ERROR`/`WARN` tokens, Kafka uses
`[ERROR]`, ClickHouse uses `<Error>`, and Alloy uses `level=error`. Matching a bare uppercase
`ERROR` anywhere in a line would classify an nginx request for `/ERROR` as an error.

Defaulting unmatched lines to *excluded* rather than *warning* is the important half: the
alternative floods the first runs with every unparsed line from every container, which is exactly
the noise the spec warns will make the module something to ignore.

**Alternatives considered**: relying on Loki's `detected_level` label (not present — Alloy's
config sets no such stage); a single permissive regex (fails as above).

---

## R4. Where does the post-deploy trigger hook in? (FR-011, FR-012)

**Decision**: in `DeployWorkflowImpl.finish`, on the success statuses only (`DEPLOYED`, and
`DEPLOYED_IMAGES_ONLY`), scheduling a child workflow with a five-minute start delay, wrapped so no
failure can propagate.

**Rationale**: `finish` is already the single exit point for the deploy — the existing code
comment says as much — and it already holds the status. The `linearFilingEnabled` precedent gives
the exact shape for a flag that must travel on the request because a `@WorkflowImpl` cannot inject
properties.

Three guarantees, each with its own mechanism, because FR-012 is absolute:

1. `factory.deploy.log-watch-trigger-enabled` is read on the **deployer** side and travels on the
   deploy request. With `logwatch` disabled, nothing polls its queue, so an unguarded schedule
   would stall the deploy until schedule-to-close rather than failing immediately.
2. The trigger activity carries a short `scheduleToCloseTimeout` as the backstop.
3. The call is wrapped so any failure is recorded in the deploy's own progress and **not**
   rethrown. A log scan has no business failing a deploy that already verified green.

`DEPLOYED_IMAGES_ONLY` is deliberately included: a held-back config sync still put new images into
production, so it is still a change worth watching. A failed-and-rolled-back deploy schedules
nothing (FR-011 scenario 4).

**Alternatives considered**: a Temporal timer inside the deploy workflow (keeps the deploy
workflow open for five minutes past completion, which muddies its own run history and makes a
deploy look unfinished on the console); an external scheduler (a second scheduling mechanism for
one caller).

---

## R5. The LLM call (FR-009, NFR-001)

**Decision**: one `ClaudeCliRunner.runStructured` invocation per run with a fast model, taking the
already-grouped signatures and returning titles and bodies. On any failure, fall back to
`LogWatchReportRenderer`'s deterministic write-up.

**Rationale**: this is the factory's existing LLM path (`ClaudeCliHarvestEngine` uses it with a
cheap model for exactly this "turn structured data into prose" job), so no new dependency and no
second mechanism. One call per run satisfies NFR-001 by construction rather than by discipline.

**The model writes; it does not decide.** Reading, grouping, the minimum-occurrence filter, the
cap and the filing decision are all deterministic and unit-tested. The fingerprint is computed
from the **signature**, never from the generated title — the `Fingerprint` javadoc already
records why: the same problem phrased differently on two runs would file twice.

**Alternatives considered**: Spring AI (`backend` uses it, `software-factory` does not — adding it
here introduces a second LLM path into a module that already has one); one call per signature
(violates NFR-001 and scales cost with log volume).

---

## R6. Testing without live Loki

**Decision**: every test stubs the HTTP layer; the signature and liveness logic are pure functions
tested from checked-in fixtures.

**Rationale**: forced by the outage, correct regardless. `SignatureExtractor` and
`SourceHealthChecker` take strings and records and return records — no clients, no Spring context,
no clock. `LokiClientTest` uses `MockRestServiceServer` against recorded response bodies.

**Fixture honesty**: NFR-004 requires fixtures drawn from *real* production log lines, and Loki
holds none for the affected period. Phase 2 therefore proceeds with lines captured directly from
the host via `docker logs` (which still work — the containers log fine; it is only shipping that
is broken), and Phase 5 replaces and extends them from Loki once ingest resumes. The fixture file
records which source each line came from, so the provisional ones are visible as such and do not
quietly become the permanent basis for a tuned threshold.
