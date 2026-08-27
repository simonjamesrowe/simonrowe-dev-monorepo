# simonrowe.dev

[![CI](https://github.com/simonjamesrowe/simonrowe-dev-monorepo/actions/workflows/ci.yml/badge.svg)](https://github.com/simonjamesrowe/simonrowe-dev-monorepo/actions/workflows/ci.yml)

The monorepo behind [simonrowe.dev](https://simonrowe.dev) — Simon Rowe's
personal site, its content management system, the AI features layered on top of
it, and the self-hosted agents that review and deploy this repository.

It runs on a single Raspberry Pi.

---

## What's in here

### The website

A React SPA over a Spring Boot API. Beyond the usual portfolio pages it carries a
fair amount of machinery:

| Area | What it does |
|------|--------------|
| **Profile & experience** | Profile, employment history and skill groups, cross-linked through drawers; a résumé PDF generated on demand |
| **Blog** | Markdown posts with syntax highlighting and Mermaid diagrams, tags, media assets |
| **News & events** | Articles and events aggregated from configured sources by scheduled agents, with a weekly digest |
| **Favourites** | Signed-in visitors can favourite articles and events |
| **AI summaries** | On-demand, globally shared LLM summaries of aggregated articles |
| **Narration** | Text-to-speech audio for blog posts and article summaries, playable from listing pages and a docked player |
| **Chat** | A site chatbot over WebSocket/STOMP: RAG over Elasticsearch vectors, web search, page fetching, guardrails, and inline widgets |
| **MCP** | An MCP server exposing the site's tools to external agents, plus a `/mcp` page documenting it |
| **Guided tour** | A scripted walkthrough of the site, editable from the admin CMS |
| **Search** | Full-text site search over Elasticsearch |
| **Contact** | Contact form with reCAPTCHA and mail delivery |
| **Admin CMS** | Auth0-protected `/admin` for blogs, jobs, skills, tags, profile, media, code examples, tour steps, content sources and data operations (backup, restore, re-embed) |

### The software factory

`software-factory/` is a second Spring Boot service that runs coding agents
against this repository, orchestrated by Temporal:

- **Code review** — a signed GitHub webhook turns a pull request into a durable
  workflow that reviews an exact commit SHA and comments on the PR.
- **Feedback loop** — review conversations on merged PRs are distilled into
  guidance and proposed back as a PR to the agent instructions repo.
- **CVE fix** — a daily schedule reads Dependency-Track findings, opens a PR
  bumping the affected dependency, and drives CI to green.
- **Deploy** — a merge to `main` deploys itself, with maintenance page,
  verification, rollback and automated triage on failure.

See [docs/software-factory.md](docs/software-factory.md).

---

## Repository layout

```text
backend/            Spring Boot API — content, agents, chat, MCP, admin
frontend/           React + Vite SPA (public site and /admin CMS)
software-factory/   Temporal-backed agents: code review, cvefix, deploy, feedback
scripts/            Local dev, backup/restore, production operations, monitoring
config/             nginx, checkstyle, Grafana Alloy, OTel, SearXNG, Temporal config
docs/               Architecture, setup guides and production runbooks
specs/              Spec-driven feature folders (spec, plan, tasks, research)
designs/            Static HTML/CSS design explorations and the UI kit
stitch/             Generated design mockups per page
evals/              Promptfoo evaluations for the chatbot
ideas/              Unstarted feature ideas
.github/workflows/  ci.yml, publish.yml, evals.yml
```

Two Gradle modules (`backend`, `software-factory`) plus a standalone npm
project in `frontend/`. Shared JVM dependency versions live in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## Documentation

### Architecture and design

| Document | What it covers |
|----------|----------------|
| [Architecture](docs/architecture.md) | System map, deployables, data stores, request paths, async flows |
| [Software factory](docs/software-factory.md) | The four agent modules, task queues, container split |
| [Temporal code reviewer](docs/temporal-code-reviewer.md) | Original design rationale and operational boundaries for code review |
| [Model usage audit](docs/model-usage.md) | Every place this app calls a model, and where the model id is set |
| [`specs/`](specs/) | Per-feature specs, plans and research (spec-driven development) |
| [`docs/superpowers/`](docs/superpowers/) | Design docs and implementation plans for individual changes |
| [`designs/`](designs/) | Visual design system, colour and type tokens, page explorations |

### Production runbooks

| Runbook | Read it when |
|---------|--------------|
| [Deploy](docs/runbooks/deploy.md) | Shipping to prod, or auto-deploy misbehaved |
| [Software factory](docs/runbooks/software-factory.md) | The reviewer didn't review, or the factory needs recovery |
| [Manual actions](docs/runbooks/software-factory-manual-actions.md) | Something needs GitHub org admin and can't be automated |
| [CVE fix](docs/runbooks/cvefix.md) | The daily dependency-patching flow |
| [Production monitoring](docs/runbooks/prod-monitoring.md) | The watchdog, cold-start hazards, host defects, installing the cron monitor |
| [Dependency-Track](docs/runbooks/dependency-track.md) | Vulnerability findings and SBOM ingestion |
| [Static analysis](docs/runbooks/static-analysis.md) | SonarQube Cloud, coverage reports, quality gate |
| [Langfuse observability](docs/runbooks/langfuse-observability.md) | LLM traces are missing or shallow |
| [Article summaries](docs/runbooks/article-summaries.md) | On-demand summary generation |

### Setup guides

Each third-party integration has a step-by-step guide:

| Guide | For |
|-------|-----|
| [Auth0](docs/auth0-setup.md) | Authentication for `/admin` and signed-in features |
| [OpenAI](docs/openai-api-setup.md) | Chat, embeddings, summaries |
| [Groq](docs/groq-api-setup.md) | Alternative model provider |
| [Google Cloud TTS](docs/setup/google-cloud-tts.md) | Narration audio |
| [Google Drive](docs/google-drive-setup.md) | Off-site backup storage |
| [reCAPTCHA](docs/recaptcha-setup.md) | Contact form and chat gating |
| [Google Analytics](docs/google-analytics-setup.md) | Site analytics |

---

## Getting started

Prerequisites: **JDK 21**, **Node 22 LTS**, **Docker** with Compose v2.

```bash
docker compose up -d        # infrastructure only: Mongo, Kafka, Elasticsearch, Temporal, Langfuse
./scripts/start.sh          # backend on :8080, frontend on :5173
```

The backend and frontend run on the host, not in containers, so you get fast
reloads and a debugger. Credentials are read from `.env` files —
`backend/.env` and `frontend/.env`, copied from
`~/workspace/simonjamesrowe/env`.

| Endpoint | URL |
|----------|-----|
| Frontend | <http://localhost:5173> |
| API | <http://localhost:8080/api/profile> |
| Actuator | <http://localhost:8082/actuator/health> |
| Temporal UI | <http://localhost:8233> |
| Langfuse | <http://localhost:3000> |

The management port is **8082** locally and **8081** in production — the
production compose file overrides it.

Individual services: `./scripts/start-backend.sh`, `./scripts/start-frontend.sh`,
`./scripts/stop.sh`. Full command reference is in [CLAUDE.md](CLAUDE.md).

### Tests

```bash
./gradlew :backend:test              # backend (Testcontainers — needs Docker, not compose)
./gradlew :software-factory:test     # software factory
cd frontend && npm test              # Vitest
cd frontend && npm run e2e           # Playwright
./gradlew check                      # Checkstyle + tests + JaCoCo verification
```

Backend integration tests share singleton Mongo, Kafka and Elasticsearch
Testcontainers rather than starting one per class.

### Data

```bash
./scripts/backup.sh     # dump MongoDB + uploads to a timestamped tarball in ~/backups
./scripts/restore.sh    # restore the newest tarball from ~/backups
```

Production backups run nightly to Google Drive (full, including media; last 7
retained). To get production data locally, restore the latest Drive backup
through the admin **Data Operations** page rather than running `mongorestore`
against prod.

Data changes ship as [Mongock](https://docs.mongock.io) change units in
`com.simonrowe.migration`, never as ad-hoc scripts. Automatic index creation is
off, so indexes must be created by a change unit — `@CompoundIndex` on its own
does nothing.

---

## Building images

```bash
./gradlew :backend:bootBuildImage --imageName=simonrowe-backend:local
docker build -f Dockerfile.frontend -t simonrowe-frontend:local .
docker build -f Dockerfile.software-factory -t simonrowe-software-factory:local .
```

The backend image is a JVM image built with Cloud Native Buildpacks. GraalVM
native compilation is deliberately **disabled** — Embabel requires JVM mode
(`kotlin-reflect` is incompatible with native image).

On merge to `main`, the `Publish` workflow builds all three images for `arm64`
and pushes them to
`ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-{backend,frontend,software-factory}`.

## Deployment

Production runs `docker-compose.prod.yml` on a Raspberry Pi, exposed through a
pinggy tunnel behind Cloudflare. A merge to `main` deploys itself; nothing is
pushed to the Pi.

```bash
docker compose -f docker-compose.prod.yml up -d    # reconcile the stack
./scripts/status-prod.sh                           # what is running
```

Read [docs/runbooks/deploy.md](docs/runbooks/deploy.md) before deploying by hand,
and [docs/runbooks/prod-monitoring.md](docs/runbooks/prod-monitoring.md) before
restarting anything — the stack has a self-healing watchdog and several
non-obvious cold-start hazards.

---

## Tech stack

**Backend** — Java 21, Spring Boot 3.5.16, Spring Data MongoDB, Spring Data
Elasticsearch, Spring Kafka, Spring Security (OAuth2 resource server),
Spring AI 1.1.8 (OpenAI, MCP server, Elasticsearch vector store), Embabel 1.0.0,
Mongock 5.5.1, Bucket4j, OpenPDF, CommonMark.

**Frontend** — React 19, TypeScript 5.7, Vite 6, React Router 7, MDXEditor,
react-markdown, Mermaid, Lucide, Auth0, Zod + React Hook Form. Plain CSS with
BEM naming and custom properties for theming, in a single `styles.css`.

**Software factory** — Spring Boot, Temporal 1.36, MongoDB, GitHub App
integration, Claude Code CLI.

**Infrastructure** — MongoDB 8, Elasticsearch 8.17, Kafka 7.8 (KRaft),
Temporal 1.31, Langfuse 3, SearXNG, Portainer, Dependency-Track 5, nginx, pinggy.

**Observability** — Actuator, Prometheus metrics, OpenTelemetry Spring Boot
starter with a Micrometer tracing bridge; Grafana Alloy fans traces out to
Langfuse and logs to Grafana Cloud Loki.

**Quality** — Checkstyle (Google style), JaCoCo (78% floor on backend), ESLint,
Vitest coverage, SonarQube Cloud, CycloneDX SBOMs into Dependency-Track,
Promptfoo evals for chat behaviour, and an agentic code review on every PR.

---

## Contributing

This is a personal project, but the working conventions are written down:

- [CLAUDE.md](CLAUDE.md) — commands, design decisions, and hard-won production
  facts. Worth reading even if you are not an agent.
- Conventional commits and branch prefixes (`feat/`, `fix/`, `chore/`).
- Features are developed spec-first under `specs/`.
- CI must be green, and the automated reviewer must be satisfied, before merge.
