# Phase 1 Data Model: Log watch

Records and value types. Java records throughout unless a document is mutated in place.

## Persisted

### `logwatch_runs` (MongoDB, `software_factory` database)

The only new collection. Exists so the console can follow a run; **not** a dedup store — Linear is
the source of truth for what has been filed, per 039.

| Field | Type | Notes |
| --- | --- | --- |
| `_id` | String | The Temporal **run** id. **Not the workflow id**: the scheduled workflow id is stable, so keying on it would collapse all history into one document — the `deploy_runs` lesson. |
| `workflowId` | String | For correlation. |
| `startedAt` / `completedAt` | Instant | |
| `status` | String | `RUNNING` \| `COMPLETED` \| `NO_FINDINGS` \| `SOURCE_UNHEALTHY` \| `FAILED` |
| `trigger` | String | `SCHEDULE` \| `DEPLOY` \| `MANUAL` \| `DRY_RUN` |
| `windowStart` / `windowEnd` | Instant | The scanned window. |
| `linesRead` | int | FR-020. |
| `linesUnread` | int | Budget exhaustion (FR-006). `0` means a complete read. |
| `containersSeen` | int | FR-020 — distinct `container` label values. |
| `signaturesFound` | int | After the minimum-occurrence filter. |
| `signaturesDropped` | int | Lost to the per-run cap (FR-006). |
| `sourceHealth` | String | `ALIVE` \| `SILENT` \| `UNREACHABLE` |
| `sourceEvidence` | String | Nullable. e.g. the Alloy `429` text. |
| `filedIssues` | List\<String\> | Linear issue identifiers. Empty on a dry run. |
| `detail` | String | Free-text diagnostics. **Token-protected on read** — `GET /api/factory/runs/{id}` already requires the token for exactly this reason. |

Index: `{status: 1, startedAt: -1}` via `LogWatchIndexInitializer` (the `CveFixIndexInitializer`
pattern) — `auto-index-creation` is off, so `@Indexed` alone is decorative.

**`status` distinguishes `NO_FINDINGS` from `SOURCE_UNHEALTHY`**, which is FR-019 made structural.
Collapsing them into one "nothing to report" status is the failure this whole requirement exists
to prevent.

## Domain (not persisted)

### `LogLine`

```java
record LogLine(String container, Instant timestamp, Severity severity, String raw) {}
```

One line as read from Loki, after severity detection. `severity` is nullable-free: lines that match
no detector are filtered out before this type is constructed (R3 — default to excluded, not to
`WARN`).

### `LogSignature`

```java
record LogSignature(
    String signature,      // the normalised form; the dedup key
    Severity severity,     // highest seen across occurrences
    String container,      // the container, or a marker when several share it
    int occurrences,
    Instant firstSeen,
    Instant lastSeen,
    String exampleLine) {} // one real line, for the ticket body
```

**`signature` is what the fingerprint is computed from — never the LLM-generated title.** The
`Fingerprint` javadoc already records why: the same problem phrased differently on two runs would
file twice.

Ordering for the cap (FR-005): severity descending (`ERROR` before `WARN`), then `occurrences`
descending. Implemented as a `Comparator` constant so the ordering is testable on its own.

### `SourceHealth`

```java
record SourceHealth(Status status, Tier tier, String evidence) {
  enum Status { ALIVE, SILENT, UNREACHABLE }
  enum Tier   { ALLOY_COMPONENT, CONTAINER_COVERAGE }
}
```

`tier` records *how* the answer was reached, because "Alloy reports `loki.write` unhealthy: 429
ingestion rate limit exceeded (limit: 0 bytes/sec)" and "fewer than 3 containers produced lines in
24h" are different qualities of evidence and lead to different first moves.

- `ALIVE` — the scan's results mean what they say.
- `SILENT` — the query succeeded and returned nothing (or near nothing) where lines were expected.
  **The dangerous state**: nothing errors, so this is the one that reads as a clean bill of health
  if the module does not check. This is the August 2026 state.
- `UNREACHABLE` — Loki could not be queried at all. Loud and self-announcing; the easy case.

### `LogWatchRequest`

```java
record LogWatchRequest(
    Instant windowStart,          // null → now minus the configured default window
    Instant windowEnd,
    Trigger trigger,
    boolean dryRun,
    boolean linearFilingEnabled)  // travels on the request; a @WorkflowImpl cannot inject properties
{}
```

### `LogWatchProgress`

```java
record LogWatchProgress(LogWatchPhase phase, String detail, Integer signaturesFound) {}
```

Exactly three fields, matching every other factory module's `progress` query shape
`{phase, detail, <one module-specific field>}` — the console reads it as an untyped `JsonNode`
because Temporal's `JacksonJsonPayloadConverter` does **not** disable
`FAIL_ON_UNKNOWN_PROPERTIES`, so a typed read of one module's record throws on another's.

Phases: `ACCEPTED` → `CHECKING_SOURCE` → `READING` → `GROUPING` → `WRITING_UP` → `FILING` → `DONE`.

`CHECKING_SOURCE` comes **first**, before `READING`. A scan that cannot trust its source should
not spend an LLM call or file a signature ticket that may be an artefact of partial data.

## Filing shape

Both finding kinds go through the same `IssueFiling`, so both inherit dedup, cancel-to-suppress
and reopen-to-re-arm with no special-casing.

| | Ordinary signature | Source-health failure |
| --- | --- | --- |
| `producer` | `logwatch` | `logwatch` |
| `keyParts` | `[signature]` | `["source-health", status.name()]` |
| `title` | LLM-written, deterministic on fallback | Deterministic — never LLM-written; the module cannot trust its own inputs here |
| `commentOnly` | `false` | `false` |

**`Fingerprint.VERSION` stays `v1`** (FR-008). Bumping it orphans every ticket already filed by
`deploy` and `cvefix`.

The `keyParts` for a source-health failure deliberately exclude the evidence string: a `429` whose
byte counts differ every run must be *one* recurring problem, not a new ticket per scan. Including
`status` but not `evidence` is what makes "quota exhausted" and "credential rejected" separate
tickets while keeping each one stable across runs.
