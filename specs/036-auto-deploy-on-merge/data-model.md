# Phase 1 Data Model: Auto-deploy on merge

Java records in `com.simonrowe.factory.deploy`, plus one MongoDB collection.
Everything crossing an activity or workflow boundary must be JSON-serialisable,
which is why nothing here holds a `Path`, a `Duration` that is not a field of a
properties record, or a nested Java type Temporal's default Jackson converter
cannot round-trip.

## MongoDB collection: `deploy_runs`

Database `software_factory` (the factory's own, not the backend's). Not part of
`BackupService.BACKUP_COLLECTIONS` — that list covers the backend database only.

```java
@Document(collection = "deploy_runs")
public record DeployRunRecord(
    @Id String id,                 // Temporal run id — see "Id strategy" below
    String workflowId,             // always "deploy-prod"
    String sha,                    // the commit deployed
    String trigger,                // "workflow_run" | "manual"
    Instant startedAt,
    Instant finishedAt,
    DeployStatus status,
    List<PhaseOutcome> phases,
    SyncOutcome configSync,
    boolean rollbackTaken,
    DeployStatus rollbackStatus,   // null when no rollback was attempted
    boolean maintenancePageLeftUp,
    String issueUrl,
    String commitCommentUrl,
    String detail)
```

**Id strategy — a deliberate divergence from `CveFixRunRecord`.**
`CveFixRunRecord.idFor(workflowId)` returns the workflow id, so a re-drive
overwrites its own row. Deploy cannot do that: the workflow id is the fixed
constant `deploy-prod`, so every deploy in history would collide on one document.
Deploy keys on the Temporal **run id** (`Workflow.getInfo().getRunId()`), which is
unique per execution and stable across replays, giving one row per deploy and
still idempotent under retry.

**Index**: `startedAt` descending, named `startedAt`, created by
`DeployIndexInitializer` (an `ApplicationRunner` gated on
`factory.deploy.enabled`, matching `CveFixIndexInitializer`). Index creation with
the same name and options is idempotent, which matters because it runs on every
restart.

## Domain records

### `DeployRequest`

Everything the workflow needs, passed as an argument rather than injected — a
`@WorkflowImpl` is instantiated by the Temporal SDK, not Spring, so it can hold
no properties bean.

```java
public record DeployRequest(
    String sha,
    String trigger,               // "workflow_run" | "manual"
    Long installationId,          // null → the activity resolves it at run time
    boolean syncConfig,           // factory.deploy.sync-config
    boolean rollbackEnabled,      // factory.deploy.rollback-enabled
    List<String> services,        // factory.deploy.services
    boolean dryRun)
```

`installationId` is nullable on purpose and for the same reason `cvefix` omits it
entirely: a configured-but-empty value would make `accessToken(null)` fall back to
the static `GITHUB_TOKEN`, which this service does not set — an anonymous call and
a 403.

### `DeployPhase`

The ordered phases, matching `scripts/restart-prod.sh` one-for-one so the enum
name is the script argument:

```java
public enum DeployPhase {
  SYNC_CONFIG, MAINTENANCE_ON, PULL, RECREATE, VERIFY, MAINTENANCE_OFF,
  VERIFY_PUBLIC, ROLLBACK, TRIAGE, REPORT
}
```

`argument()` returns the kebab-case script argument (`SYNC_CONFIG` →
`sync-config`). `TRIAGE` and `REPORT` have no script argument and return null —
they are Java-side phases, and the enum carries them so the run record's phase
list is the whole story rather than only the shell part.

### `PhaseOutcome`

```java
public record PhaseOutcome(
    DeployPhase phase,
    boolean succeeded,
    int exitCode,
    String detail,          // trimmed tail of the phase's output
    long durationMillis)
```

`detail` is bounded to the last 4000 characters. The full output goes to the
container log and, for a failure, into the triage evidence directory — the record
is for answering "which phase failed", not for holding logs.

### `SyncOutcome`

The configuration-sync decision, recorded whether or not it moved `HEAD`, because
"images only, and here is why" is a first-class result rather than an error.

```java
public record SyncOutcome(
    SyncDecision decision,
    String previousSha,             // the rollback target; null when nothing moved
    String targetSha,
    List<String> affectedServices,
    List<String> heldBackServices,  // affected ∖ allowlist
    String missingVariable,         // best-effort, from a failed `config --hash`
    String manualCommand,           // what a human should run, when held back
    String detail)

public enum SyncDecision {
  APPLIED,            // HEAD fast-forwarded to targetSha
  ALREADY_CURRENT,    // HEAD was already targetSha — a no-op, still a success
  DISABLED,           // factory.deploy.sync-config is false
  DIRTY_TREE,         // a tracked file is modified
  NOT_AN_ANCESTOR,    // targetSha is not on origin/main
  HELD_BACK,          // an affected service is outside the allowlist
  MISSING_VARIABLE,   // the candidate compose file needs a variable .env lacks
  FAILED              // fetch or git error
}
```

Only `APPLIED` sets a non-null `previousSha`, and only `APPLIED` makes the
rollback path restore the commit. That single invariant is what keeps the design's
rule — "skipped if `sync-config` declined to move `HEAD` in the first place" —
expressible as one null check rather than a second flag that can disagree.

`ALREADY_CURRENT` exists for rollout step 6: triggering a deploy of the SHA
already in production must read as a clean no-op, not as a failure and not as
`APPLIED` with `previousSha == targetSha`.

### `DeployStatus`

```java
public enum DeployStatus {
  DEPLOYED,                  // every phase passed
  DEPLOYED_IMAGES_ONLY,      // passed, but config sync declined
  ROLLED_BACK,               // deploy failed, rollback verified clean
  ROLLBACK_FAILED,           // rollback also failed — maintenance page left up
  ROLLBACK_DISABLED,         // deploy failed, rollback-enabled false
  FAILED,                    // failed before anything could be rolled back
  SKIPPED                    // nothing to do (already current, dry run)
}
```

`ROLLED_BACK` and `ROLLBACK_FAILED` must be distinguishable at a glance: the
second means a human is needed *now* and the site is showing the maintenance
page.

### `DeployProgress`

The queryable snapshot, matching `CveFixProgress`:

```java
public record DeployProgress(DeployPhase phase, String detail, String sha) {
  public static DeployProgress accepted() { ... }
}
```

### `DeployResult`

What `run` returns: `status`, `sha`, `configSync.decision()`, `issueUrl`,
`detail`. Deliberately small — the full story is the persisted record, and a
workflow result is retained only as long as the history is.

## Configuration record

```java
@ConfigurationProperties("factory.deploy")
public record DeployProperties(
    boolean enabled,              // registers the activities bean → this JVM executes deploys
    boolean triggerEnabled,       // the webhook branch starts workflows
    String owner,                 // "simonjamesrowe"  — the repository allowlist
    String repository,            // "simonrowe-dev-monorepo"
    String workflowName,          // "Publish"
    String branch,                // "main"
    String composeFile,           // "/workspace/docker-compose.prod.yml"
    String script,                // "/workspace/scripts/restart-prod.sh"
    String repoDir,               // "/workspace/repo"
    String repoUrl,               // pinned https URL, not the checkout's remote
    List<String> services,        // backend, frontend, software-factory
    List<String> recreatable,     // the eight-service allowlist
    boolean rollbackEnabled,
    boolean syncConfig,
    String stateDir,              // "/var/run/deploy-state"
    Duration phaseTimeout,        // per-phase ceiling for the shell process
    Agent agent) { ... }
```

Every field is defaulted in the compact constructor, `CveFixProperties`-style, so
a partially-configured deployment boots with sane values rather than nulls.

`enabled` and `triggerEnabled` are two fields on one record on purpose: they are
read in the same place and must be seen together, and the design's requirement is
that they move independently, not that they live apart.

**The `recreatable` default is the design's allowlist verbatim**:
`backend, frontend, software-factory, nginx, alloy, searxng, temporal-ui,
dependencytrack-frontend`. `deployer` is absent — it excludes itself.

## Non-persisted values

- **The rollback point** (previous image ID per service) lives in the
  `deploy-state` volume as `rollback-images` (`service<TAB>image-id` per line),
  written by `pull` and read by `rollback`. It is *not* held in workflow state:
  the design requires each phase to be a separate `bash` process, and the shell
  is what knows the image IDs. Keeping it on disk beside the maintenance flag
  means a re-run of `rollback` after a worker restart still has it.
- **The triage evidence** is a scratch directory under the workspace volume
  holding `phase-output.txt`, `compose-ps.txt`, `container-logs.txt` and
  `commit-range.txt`. The agent gets `Read` against that directory and nothing
  else, and it is deleted after the run.
