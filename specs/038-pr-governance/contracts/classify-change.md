# Contract: `scripts/classify-change.sh`

## Invocation

```bash
scripts/classify-change.sh [<base-ref>]
```

- With no argument: classifies the changes in `git diff --name-only <base-ref>...HEAD`, defaulting
  `<base-ref>` to `origin/main`.
- With paths on stdin (one per line): classifies exactly those paths and touches git not at all.
  This is the mode the test suite uses — it must not need a repository state to exercise.

Constitution Principle IX applies: `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR` and
`PROJECT_DIR` resolved with `$(cd "$(dirname "$0")" && pwd)`.

## Output

Two `GITHUB_OUTPUT`-shaped lines on stdout, in this order:

```
category=auto-merge|ux-review|manual
ux_affecting=true|false
```

The shape is kept `GITHUB_OUTPUT`-compatible so the script stays usable if CI ever consumes it
directly. **There is no `classify` job and no `ci.yml` change in this feature** — the only consumer
is the `pr-review-loop` skill.

## Exit status

`0` in all classification outcomes, including `manual`. A non-zero exit is reserved for the script
failing (bad ref, unreadable input). "Needs a human" is an answer, not an error.

## Classification rules

First matching rule wins. Every changed path is tested against rule 1, then 2, then 3; a path
matching none falls to rule 4. The **whole change** takes the highest-precedence category any of
its paths matched.

### Rule 1 — `manual` (highest precedence)

```
docker-compose*.yml
scripts/**
config/**
.github/**
gradle*            (gradlew, gradlew.bat, gradle/**, gradle.properties)
settings.gradle*   build.gradle*    (root only)
frontend/*.config.*
frontend/package.json
frontend/package-lock.json
```

**Rule 1 outranks rule 3 deliberately.** Auto-merge to `main` triggers Publish, which triggers
auto-deploy — an unattended infrastructure deploy against the Pi. `036-auto-deploy-rollout-fixes`
is a nine-item catalogue of why those fail in ways no test catches.

### Rule 2 — `ux-review` (sets `ux_affecting=true`)

```
frontend/src/**
frontend/index.html
frontend/public/**
```

### Rule 3 — `auto-merge`

```
backend/**
software-factory/**
docs/**
specs/**
frontend/tests/**
frontend/e2e/**
*.md               (repository root only)
```

`frontend/tests/**` and `frontend/e2e/**` are auto-merge because they change no shipped pixel.
`frontend/vite.config.ts` is caught by rule 1 because it changes the shipped bundle.

### Rule 4 — `manual` (default)

Anything not matched above. **Load-bearing**: an unrecognised path is `manual`, never
`auto-merge`, so a new top-level directory added later defaults to needing a human rather than
inheriting merge rights.

## Test cases — `scripts/test/test-classify-change.sh`

Discovered automatically by `scripts/test/run-tests.sh` (globs `test-*.sh`). The classifier shells
out to nothing, so it neither needs nor honours the suite's exported `DRY_RUN=1`; the test must not
depend on it.

| # | Input paths | `category` | `ux_affecting` |
| --- | --- | --- | --- |
| 1 | `backend/src/main/java/A.java` | `auto-merge` | `false` |
| 2 | `software-factory/src/main/java/B.java`, `docs/runbooks/x.md` | `auto-merge` | `false` |
| 3 | `frontend/src/App.tsx` | `ux-review` | `true` |
| 4 | `frontend/public/logo.svg` | `ux-review` | `true` |
| 5 | `frontend/tests/foo.test.ts` | `auto-merge` | `false` |
| 6 | `frontend/e2e/chat.spec.ts` | `auto-merge` | `false` |
| 7 | `frontend/vite.config.ts` | `manual` | `false` |
| 8 | `frontend/package.json` | `manual` | `false` |
| 9 | `docker-compose.prod.yml` | `manual` | `false` |
| 10 | `scripts/monitor-prod.sh` | `manual` | `false` |
| 11 | `.github/workflows/ci.yml` | `manual` | `false` |
| 12 | `config/nginx/nginx-proxy.conf` | `manual` | `false` |
| 13 | `gradlew` | `manual` | `false` |
| 14 | `README.md` | `auto-merge` | `false` |
| 15 | **`backend/src/A.java` + `docker-compose.prod.yml`** — precedence: rule 1 beats rule 3 | `manual` | `false` |
| 16 | **`backend/src/A.java` + `frontend/src/App.tsx`** — precedence: rule 2 beats rule 3 | `ux-review` | `true` |
| 17 | **`frontend/src/App.tsx` + `scripts/x.sh`** — precedence: rule 1 beats rule 2 | `manual` | `false` |
| 18 | **`newtoplevel/thing.txt`** — the unrecognised-path default | `manual` | `false` |
| 19 | **`specs/038-pr-governance/spec.md` + `newtoplevel/x`** — one unrecognised path is enough | `manual` | `false` |
| 20 | empty input | `manual` | `false` |

Case 18 is the one the design calls out as load-bearing; case 15 is the one that protects
production. Case 20 fails closed on a no-op diff rather than arming a merge for a change nobody
can see.
