<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
specs/036-auto-deploy-on-merge/plan.md
<!-- SPECKIT END -->

# simonrowe-dev-monorepo — agent guidelines

## Read these first

| File | What it holds |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | **The authoritative guide.** Commands, code style, key design decisions, and a long list of hard-won production facts (failure modes, gotchas, why things are the way they are). Read it before changing anything operational. |
| [README.md](README.md) | What the project is, its modules, and an index of every doc |
| [docs/architecture.md](docs/architecture.md) | System map, data stores, request paths, async flows |
| [docs/software-factory.md](docs/software-factory.md) | The agents that review, patch and deploy this repo |
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | Project constitution — binding principles |

This file exists so agents that read `AGENTS.md` rather than `CLAUDE.md` find
their way. It deliberately does not duplicate CLAUDE.md; where the two disagree,
CLAUDE.md wins.

## Stack

- **Backend** — Java 21, Spring Boot 3.5.16, Spring Data MongoDB, Spring Data
  Elasticsearch, Spring Kafka, Spring Security (OAuth2 resource server),
  Spring AI 1.1.8, Embabel, Mongock. Gradle, versions in
  `gradle/libs.versions.toml`.
- **Frontend** — React 19, TypeScript 5.7, Vite 6, React Router 7, Vitest,
  Playwright. Plain CSS with BEM in a single `styles.css`.
- **Software factory** — Spring Boot + Temporal, agents driven by the Claude
  Code CLI.
- **Infrastructure** — MongoDB 8, Elasticsearch 8.17, Kafka 7.8 (KRaft),
  Temporal, Langfuse, all on one Raspberry Pi behind Cloudflare and a tunnel.

## Project structure

```text
backend/            Spring Boot API — content, agents, chat, MCP, admin
frontend/           React + Vite SPA (public site and /admin CMS)
software-factory/   Temporal-backed agents: code review, cvefix, deploy, feedback
scripts/            Local dev, backup/restore, production operations, monitoring
config/             nginx, checkstyle, Grafana Alloy, OTel, SearXNG, Temporal
docs/               Architecture, setup guides, production runbooks
specs/              Spec-driven feature folders (spec, plan, tasks, research)
```

## Commands

```bash
# Local environment (infrastructure in Docker, apps on the host)
docker compose up -d                    # Mongo, Kafka, Elasticsearch, Temporal, Langfuse
./scripts/start.sh                      # backend :8080, frontend :5173
./scripts/stop.sh

# Tests
./gradlew :backend:test                 # Testcontainers — needs Docker, not compose
./gradlew :software-factory:test
cd frontend && npm test                 # Vitest
./gradlew check                         # Checkstyle + tests + JaCoCo (0.78 floor)

# Data
./scripts/backup.sh                     # MongoDB + uploads -> ~/backups
./scripts/restore.sh                    # restore the newest tarball
```

Actuator is on **8082** locally and **8081** in production.

## Working rules

- **Conventional commits and branch prefixes** (`feat/`, `fix/`, `chore/`). No
  Jira tickets in this repo. Do not attribute agents in commits or PRs.
- **Features are spec-first.** Work under `specs/<nnn>-<slug>/`; keep
  `.specify/feature.json` pointing at the active feature.
- **Data changes ship as Mongock change units** in `com.simonrowe.migration`,
  never as ad-hoc scripts. Automatic index creation is off, so indexes must come
  from a change unit — `@CompoundIndex` alone does nothing.
- **The backend must not launch host processes.** `NoHostProcessLaunchTest`
  fails the build if a `ProcessBuilder` appears in `backend/src/main/java`, and
  Constitution Principle II prohibits it. Deploys are the `deployer` container's
  job.
- **Credentials come from `.env` files**, sourced from
  `~/workspace/simonjamesrowe/env`. Never echo a credential value.
- **Never test `scripts/monitor-prod.sh` or `scripts/restart-prod.sh` by just
  running them** — every remediation path shells out to `docker compose` against
  real production. Use `DRY_RUN=1` and a throwaway `STATE_DIR`.
- **CI must be green and the automated reviewer satisfied before merge.** A red
  `Static Analysis` check means a broken scanner, not a cosmetic advisory
  failure.

## Before touching production

Read the relevant runbook in [docs/runbooks/](docs/runbooks/) first, and the
production section of [CLAUDE.md](CLAUDE.md). This stack has several failure
modes that are non-obvious and expensive to rediscover — a `healthy` container
that serves nothing, a single-node Kafka broker that silently accepts writes no
consumer can ever read, healthchecks whose cold-start budget strands dependent
containers in `created`.
