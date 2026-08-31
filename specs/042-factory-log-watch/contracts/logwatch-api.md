# Contract: the HTTP surface `logwatch` adds

Two endpoints on `software-factory`, plus two existing endpoints that gain a seventh entry.
**No new nginx route.** `logwatch` is reached only through the backend's admin proxy, exactly as
`cvefix` and `platformbackup` are.

---

## New: `POST /api/logwatch/scan`

Starts a scan. Unrouted by nginx and token-protected (`FactoryTokenAuthenticator`).

**Request**

```json
{
  "windowStart": "2026-09-01T00:00:00Z",
  "windowEnd":   "2026-09-02T00:00:00Z",
  "dryRun": false
}
```

All fields optional. Omitting the window scans `now - factory.logwatch.default-window` to `now`.

**Responses**

| Status | Meaning |
| --- | --- |
| `202` | Accepted; body is `LogWatchScanAccepted{workflowId, runId}` |
| `409` | A scan is already running (`REJECT_DUPLICATE` on the workflow id) |
| `503` | `factory.logwatch.enabled` is false — the module is off |
| `401` | Missing or wrong token |

**Workflow id**: `logwatch-<windowEnd epoch seconds>` for scheduled and deploy-triggered runs, so
`REJECT_DUPLICATE` collapses a duplicate trigger for the same window. **A manual run mints a UUID
instead** — the same mechanism the console uses to make a code review re-runnable, and for the same
reason: an operator asking for a scan of a window that has already been scanned means it, and a
`409` there would be indistinguishable from the module being wedged.

**Dry run creates and comments on nothing in Linear** (FR-014). Its findings are visible only in
run progress, which is what makes offering it reasonable.

---

## New: `GET /api/logwatch/runs/{runId}`

Not strictly new machinery — `GET /api/factory/runs/{id}` already follows every module's runs by
reading the `progress` query as an untyped `JsonNode`. Listed here because `logwatch` must satisfy
that existing contract:

- the workflow exposes a query method named exactly `progress`
- returning `{phase, detail, signaturesFound}` — three fields, one module-specific
- read through an **untyped** stub, because Temporal's `JacksonJsonPayloadConverter` does not
  disable `FAIL_ON_UNKNOWN_PROPERTIES`

Temporal's `executionStatus` and the workflow's `phase` are reported **separately**: a failed
workflow cannot answer a query at all, and "it failed" is the most useful thing the console can
say, so a failing query must not lose the status.

---

## Changed: `GET /api/factory/status`

Gains a seventh module. **Stays unauthenticated** — the backend asks both `software-factory` and
`deployer` for it, and `deployer` holds no `FACTORY_TRIGGER_TOKEN` on purpose.

```json
{
  "key": "logwatch",
  "enabled": true,
  "taskQueue": "logwatch",
  "workflowPollers": 1,
  "activityPollers": 1,
  "scheduleId": "logwatch-daily",
  "schedulePaused": false,
  "nextRunAt": "2026-09-02T03:00:00Z",
  "missingPrerequisites": ["GRAFANA_CLOUD_API_KEY"],
  "ready": false
}
```

`ready` is the conjunction of **flag AND poller AND prerequisites**. The backend refuses an action
whose module is not ready — without that, a workflow started on a queue nothing polls does not
fail, it sits in Temporal looking accepted until an activity timeout.

`missingPrerequisites` draws from `GRAFANA_CLOUD_LOKI_ENDPOINT`, `GRAFANA_CLOUD_LOKI_USER` and
`GRAFANA_CLOUD_API_KEY`. `ModulePrerequisites` never fails startup: a missing prerequisite degrades
one module, never takes the factory down.

**The key `logwatch` is a wire contract** — it appears here, in the backend's aggregation and in
the browser's discriminated union (`frontend/src/services/softwareFactoryApi.ts`). A typo does not
fail; it silently reports the module as unavailable.

---

## Changed: the admin console row

One more row in the existing table, same components:

| Column | Value |
| --- | --- |
| Name / key | Log watch / `LOGWATCH` |
| Trigger | `Schedule` — `Active · next <time>` |
| Manual | **`Scan now` / `Dry run`** |

Two actions rather than one, matching Platform backup's `Dry run / backup` pair. The dry run is the
important one at rollout: the signature rules can only be validated against real production lines,
and a dry run is how they get validated without filing a first round of tickets to clean up.

Status translation follows the established rules — 409 stays 409 ("already in progress"), a
downstream 503 becomes "reports that module as disabled", 401/403 becomes 502, and only a real
outage says "unavailable". Collapsing these into "unavailable" sends an operator looking for a down
container when the answer is a flag.
