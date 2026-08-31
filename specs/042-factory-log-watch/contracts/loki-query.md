# Contract: Grafana Cloud Loki, as consumed

What `LokiClient` sends and what it must tolerate. Every field below was executed live against the
production tenant on 2026-08-31 unless marked otherwise.

## Base URL — the trap

`GRAFANA_CLOUD_LOKI_ENDPOINT` in `.env` is the **push** URL and **already contains the API path**:

```
https://logs-prod-035.grafana.net/loki/api/v1/push
```

The query base is that value with `/push` stripped. Appending `/api/v1/...` to it produces
`/loki/api/v1/api/v1/...`, which returns a bare `404 page not found` — plain text, no JSON, no hint
that the path is doubled. This is handled once inside `LokiClient`, never by callers.

## Auth

HTTP basic: username `GRAFANA_CLOUD_LOKI_USER` (numeric tenant id, `1539009`), password
`GRAFANA_CLOUD_API_KEY`. The same key carries `logs:write` (Alloy) and `logs:read`.

**Reads and writes are separately gated.** This is the single most important fact in this contract:
during the August 2026 outage the tenant could not ingest a single byte while reads returned `200`
throughout. A working read proves nothing about the pipeline.

## Requests

### Lines in a window

```
GET /query_range
  ?query={container=~".+"} |~ "(?i)(error|warn)"
  &start=<ns>&end=<ns>&limit=<budget>&direction=forward
```

Timestamps are **nanoseconds** since epoch. A seconds value is accepted and silently returns an
empty result for a window ~50 years wide in the wrong place — an empty success, which is precisely
the shape this module must never misread as clean.

The regex pre-filter is a cheap narrowing, not the severity decision; `SeverityDetector` makes that
call per line (R3), because a line matching `error` is not necessarily an error line.

### Distinct containers in a window (liveness tier 2)

```
GET /label/container/values?start=<ns>&end=<ns>
```

### Alloy component health (liveness tier 1)

Not Loki. `GET http://alloy:12345/api/v0/web/components` on the compose network, filtered to
`loki.write.grafana_cloud`. Both containers are on the same network and `alloy` publishes no host
port, so this needs no compose change — **assumption not yet executed live; verify before Phase 3.**

## Responses this client must handle

| Status / shape | Meaning | Handling |
| --- | --- | --- |
| `200` with `data.result` populated | Normal | Parse |
| **`200` with `{"status":"success"}` and no data** | **Query fine, store empty** | **Not an error and not a clean result. Feeds `SourceHealthChecker`.** |
| `401` | Wrong tenant id, or a key lacking `logs:read` | `UNREACHABLE`; evidence names the credential |
| `429` | Read rate limit (distinct from Alloy's write `429`) | Retry via Temporal; then `UNREACHABLE` |
| `404` plain text | Doubled path — a bug in this client, not an outage | Fail loudly; never treat as empty |
| Result truncated at `limit` | Budget exhausted | Record `linesUnread`; **never present as complete** (FR-006) |

The empty-success row is the whole reason FR-017 exists. Its wire shape is indistinguishable from a
genuinely quiet stack, and no status code, error field or log line distinguishes them.

### The control that separates credential from content

```bash
Q="${GRAFANA_CLOUD_LOKI_ENDPOINT%/push}"
curl -s -u "999999:$GRAFANA_CLOUD_API_KEY"                   "$Q/labels"   # expect 401
curl -s -u "$GRAFANA_CLOUD_LOKI_USER:$GRAFANA_CLOUD_API_KEY" "$Q/labels"   # expect data
```

A deliberately wrong tenant returning `401` while the real one returns `200`-empty proves
authentication and read scope are healthy and there is genuinely nothing stored. Worth knowing
while diagnosing, and worth *not* building into the module: it proves the read path is fine, which
is never the question when logs are missing.

## Retry policy

Network retries via Temporal's `RetryOptions` (3 attempts, 1s→10s), matching `cvefix`. A `401` is
non-retryable — retrying a rejected credential three times only delays the true answer.
