# Contract: `scripts/restart-prod.sh` phases

This is the real interface of the feature. The Java side does not talk to Docker;
it runs this script one phase at a time and reads the exit code. The script is
also the documented human deploy path, so the contract has two consumers with
different expectations, and both are pinned here.

## Invocation

```
scripts/restart-prod.sh [PHASE] [TARGET_SHA]
```

`PHASE` defaults to `all`. `TARGET_SHA` is required by `sync-config` and
`rollback-config`, ignored by everything else.

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `SERVICES` | `backend frontend software-factory` | Space-separated services whose images `pull` and `recreate` act on. Never contains `deployer`. |
| `IMAGE_TAG` | `latest` | The tag `pull` fetches and re-tags to `:latest`. The deployer passes the head SHA. |
| `STATE_DIR` | `/var/run/deploy-state` | Where `maintenance.on` and `rollback-images` live. |
| `VERIFY_TIMEOUT` | `420` | Container settle budget, seconds. Unchanged. |
| `VERIFY_POLL` | `10` | Settle poll interval, seconds. Unchanged. |
| `REPO_URL` | the pinned https URL | What `sync-config` fetches from. Never `origin`. |
| `DRY_RUN` | unset | When set to `1`, every `docker`/`git`-mutating command is echoed instead of run. **Tests must set this.** |

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | The phase succeeded. |
| `1` | The phase failed. The workflow treats this as a phase failure and enters the rollback path if the phase was `verify` or `verify-public`. |
| `2` | The phase declined, without failing and without side effects. **Only `sync-config` returns this**, and only for a decision the deploy is meant to survive (dirty tree, not-an-ancestor, held-back service, missing variable). |
| `64` | Usage error — unknown phase, or a phase that needs `TARGET_SHA` without one. |

The distinction between `1` and `2` is what makes FR-030 ("continue with images
only") expressible without parsing output. Anything the workflow must survive
exits `2`; anything that must stop the deploy exits `1`.

## Phases

### `all` (default, the human path)

Runs `pull` → `recreate` → `verify` → `verify-public`, in that order, with no
flag file created and **without `sync-config`**.

**This must be behaviourally identical to today's script.** A human typing
`./scripts/restart-prod.sh` after their own `git pull` must not have the script
decide to move `HEAD` for them. Specifically preserved:

- `docker compose pull` for the whole file, then `up -d` for the whole stack —
  not the per-service `--no-deps` form the `recreate` phase uses.
- The `up -d` failure is recorded and **not fatal**, so verification still runs
  and reports what is actually broken. (This is the behaviour that stopped a
  deploy from silently leaving a 502 on www behind a wall of "Container …
  Running" lines.)
- `restart nginx` afterwards.
- The settle loop and the same six public hostnames, with the same
  `000|502|503|504` → failure classification.
- The same final "Production services refreshed and verified." / "Production
  refresh INCOMPLETE" messages and the same exit code.

### `sync-config <sha>`

Fast-forwards the deploy directory to `<sha>` if and only if it is provably safe.
Runs **first**, before `pull`, so every later phase uses the newly-synced compose
file and script.

Order matters and each step abandons without side effects on failure:

1. `git -C "$repo" rev-parse HEAD` → print `previous-sha=<sha>` on stdout. This
   is the rollback target and the workflow parses it.
2. If `HEAD` already equals `<sha>` → print `decision=already-current`, exit `0`.
3. `git -C "$repo" status --porcelain --untracked-files=no` must be empty.
   Non-empty → `decision=dirty-tree`, exit `2`. Untracked and ignored files
   (including a hand-edited `.env`) do not block.
4. `git -C "$repo" fetch --no-tags "$REPO_URL" main`. Anonymous, read-only, and
   from the pinned URL rather than the checkout's configured remote, so a
   tampered remote cannot redirect it. Failure → `decision=failed`, exit `1`.
5. `git -C "$repo" merge-base --is-ancestor "$sha" FETCH_HEAD`. Failure →
   `decision=not-an-ancestor`, exit `2`. This is the assertion that bounds the
   whole capability to commits genuinely on `origin/main`.
6. Compute the affected services **without moving `HEAD`**:
   `git show "$sha:docker-compose.prod.yml" > "$tmp"`, then
   `docker compose -f "$tmp" config --hash='*'` against the same on the current
   file. Affected = every service whose hash differs, plus every service present
   in one list and not the other.
   - If the command fails on the candidate file but succeeds on the current one,
     the new file needs a variable `.env` does not define →
     `decision=missing-variable`, `missing-variable=<name>` (best effort), exit
     `2`. Declining here is what stops the box being left in a state where every
     later `docker compose` command fails.
7. If any affected service is outside `RECREATABLE` → print
   `decision=held-back`, `held-back=<space-separated>`, and the manual command,
   exit `2`. **`HEAD` does not move.** Deciding first and moving second is the
   whole point: a fast-forward followed by a refusal to recreate would leave the
   directory ahead of what is running, and `monitor-prod.sh`'s next bare `up -d`
   would apply the held-back change within the minute.
8. `git -C "$repo" merge --ff-only "$sha"` → `decision=applied`, exit `0`.

Machine-readable output is `key=value` lines on stdout; human narration goes to
stderr. Keys: `previous-sha`, `decision`, `affected`, `held-back`,
`missing-variable`, `manual-command`.

Idempotent: re-running after success takes the `already-current` path at step 2.

### `rollback-config <sha>`

`git -C "$repo" reset --hard <sha>`. Used only by the rollback path and only when
`sync-config` reported `decision=applied`. `reset --hard`, not `merge --ff-only`,
because the recorded SHA is an ancestor of the current `HEAD` and a fast-forward
cannot go backwards — safe precisely because step 3 above proved the tree was
clean before anything moved.

Because this restores the previous commit, everything after it runs the
*previous* version of `restart-prod.sh` — which is what matters when the thing
that broke the deploy was a change to the script itself.

### `maintenance-on` / `maintenance-off`

`mkdir -p "$STATE_DIR"` then create or remove `"$STATE_DIR/maintenance.on"`.
Idempotent in both directions (`touch`, and `rm -f`). Exit `0` regardless of the
prior state.

### `pull`

For each service in `SERVICES`:

1. Record the current image ID:
   `docker image inspect --format '{{.Id}}' "$image:latest"`, appended to
   `"$STATE_DIR/rollback-images"` as `service<TAB>image-id`. A service with no
   local image yet records nothing and is simply not rollback-able — not an
   error.
2. `docker pull "$image:$IMAGE_TAG"`.
3. When `IMAGE_TAG` is not `latest`:
   `docker tag "$image:$IMAGE_TAG" "$image:latest"`.

The rollback file is **truncated at the start of the phase**, not appended
across runs, so a retry records the same pre-deploy state rather than the
half-updated one. This is the single most important idempotency detail in the
script: an append would make a retried `pull` record the newly-pulled image as
the rollback target, and rollback would then restore the broken version.

### `recreate`

For each service in `SERVICES`:
`docker compose -f "$COMPOSE_FILE" up -d --no-deps --pull never "$service"`.

Then `docker compose restart nginx`, then a full
`docker compose -f "$COMPOSE_FILE" up -d` reconcile (non-fatal, as in `all`).

`--pull never` is what makes the local re-tag authoritative for this invocation.
`--no-deps` is what stops recreating a service from restarting the database
underneath it.

The full reconcile can evaluate the whole compose file and therefore recreate
anything — which is safe only because `sync-config` moved `HEAD` only when every
affected service was allowlisted. The two are a pair; neither is safe without the
other.

`nginx` restarts here, and `nginx` is what serves the maintenance page, so for a
second or two there is nothing serving at all. Accepted: it is unavoidable while
the page lives in the same nginx that proxies the site, and a couple of seconds
inside an already-degraded window does not justify a second proxy container.

### `verify`

The container settle loop (unchanged, including the `created`-state explanation)
plus the four ops hostnames: `console`, `langfuse`, `temporal`,
`dependency-track`. **Not** `www` or `api` — they are behind the flag and return
`503` by design, which the check correctly treats as a failure.

### `verify-public`

`www.simonrowe.dev` and `api.simonrowe.dev`, with the existing classification.
Runs only after `maintenance-off`.

### `rollback`

For each `service<TAB>image-id` in `"$STATE_DIR/rollback-images"`:
`docker tag "$image_id" "$image:latest"`, then
`docker compose up -d --no-deps --pull never "$service"`.

Then `restart nginx` and the full reconcile, as `recreate` does. Exit `1` if any
step fails; the workflow turns that into `ROLLBACK_FAILED` and leaves the
maintenance page up.

## Constraints on every phase

- **Idempotent.** Temporal retries activities, so a second identical run must be
  a no-op or reach the same state. `pull`'s truncate-then-record is the one place
  this needs active care.
- **`set -euo pipefail`**, `#!/usr/bin/env bash`, `SCRIPT_DIR`/`PROJECT_DIR`
  resolution — Constitution Principle IX, already satisfied today.
- **`jq`, not `python3`.** The settle-loop parser must still cover: a
  healthcheck-carrying container that is not `healthy`; an `exited` container with
  a non-zero code (a clean one-shot is fine); a container neither `running` nor
  `exited` (this catches `created`); and a malformed line skipped rather than
  fatal.
- **`DRY_RUN=1` must reach every mutating command.** A test that runs the script
  without it performs real restarts and can recreate containers if the compose
  file has been edited since the last deploy — the same warning `CLAUDE.md`
  already records for `monitor-prod.sh`.
