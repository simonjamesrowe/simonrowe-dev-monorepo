<!--
  Sync Impact Report
  ==================
  Version change: 0.0.0 (template) → 1.0.0 (initial ratification)

  Modified principles: N/A (first fill from template)

  Added sections:
    - Principle I: Monorepo with Separate Containers
    - Principle II: Modern Java & React Stack
    - Principle III: Quality Gates (NON-NEGOTIABLE)
    - Principle IV: Observability & Operability
    - Principle V: Simplicity & Incremental Delivery
    - Section: Technology Stack Constraints
    - Section: Development Workflow
    - Section: Governance

  Removed sections: None

  Templates requiring updates:
    ✅ .specify/templates/plan-template.md — no changes needed,
       Constitution Check section is generic and will pick up these principles
    ✅ .specify/templates/spec-template.md — no changes needed,
       structure is technology-agnostic
    ✅ .specify/templates/tasks-template.md — no changes needed,
       phase structure accommodates these principles
    ✅ No command files exist in .specify/templates/commands/

  Follow-up TODOs: None
-->

# simonrowe-dev-monorepo Constitution

## Core Principles

### I. Monorepo with Separate Containers

All source code for simonrowe.dev MUST live in a single monorepo.
The backend and frontend MUST be built and deployed as separate
Docker containers. Shared configuration (Docker Compose, CI
workflows) lives at the repository root.

- Backend and frontend MUST NOT share a runtime container.
- Docker Compose MUST be the local and production orchestration
  mechanism (with Pinggy for public exposure).
- Images MUST be published to GitHub Container Registry (ghcr.io).

### II. Modern Java & React Stack

The backend MUST use Java 25, Spring Boot 4, Gradle, MongoDB,
Kafka, and Elasticsearch. The frontend MUST use the latest stable
React release. No CMS — content is managed through application
code and MongoDB persistence.

- Backend build tool MUST be Gradle (Kotlin DSL preferred).
- MongoDB MUST be the primary persistence store.
- Kafka MUST be used for asynchronous messaging.
- Elasticsearch MUST be used for search functionality.
- Auth0 MUST be the sole authentication provider; no self-service
  registration — users are provisioned directly in Auth0.
- Spring Boot Actuator/management endpoints MUST run on a
  separate port from application traffic.

### III. Quality Gates (NON-NEGOTIABLE)

Every change MUST pass automated quality checks before merge.
Manual overrides of quality gates are prohibited.

- Code style MUST conform to the
  [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
- JaCoCo MUST enforce minimum test coverage thresholds.
- SonarQube MUST run static analysis on every PR.
- CycloneDX (CDX) BOM MUST be generated for dependency tracking.
- Testcontainers MUST be used for slice and integration tests —
  no mocked infrastructure for integration-level verification.
- Frontend tests MUST exist for critical user journeys.

### IV. Observability & Operability

The system MUST be observable in production from day one.
Debugging MUST NOT require SSH access or log tailing on hosts.

- Prometheus metrics MUST be exposed via a dedicated actuator port
  for scraping.
- OpenTelemetry MUST be integrated for distributed tracing.
- Structured logging MUST be used across all services.

### V. Simplicity & Incremental Delivery

Start with the simplest working solution. Add complexity only
when a concrete requirement demands it. YAGNI applies.

- Features MUST be delivered as independently testable increments.
- No premature abstractions — three similar lines are better than
  an unjustified abstraction.
- Content creation/editing functionality is a future concern; the
  architecture MUST accommodate it but MUST NOT implement it until
  explicitly requested.

## Technology Stack Constraints

| Layer        | Technology                        | Version/Notes         |
|--------------|-----------------------------------|-----------------------|
| Language     | Java                              | 25                    |
| Framework    | Spring Boot                       | 4.x                  |
| Build        | Gradle (Kotlin DSL)               | Latest stable         |
| Database     | MongoDB                           | Latest stable         |
| Messaging    | Apache Kafka                      | Latest stable         |
| Search       | Elasticsearch                     | Latest stable         |
| Frontend     | React                             | Latest stable         |
| Auth         | Auth0                             | Managed service       |
| CI/CD        | GitHub Actions                    | Build, test, publish  |
| Registry     | GitHub Container Registry (ghcr)  | Docker images         |
| Orchestration| Docker Compose                    | Local + production    |
| Exposure     | Pinggy                            | Production tunneling  |
| Metrics      | Prometheus + OpenTelemetry        | Separate actuator port|
| Coverage     | JaCoCo                            | Enforced thresholds   |
| Analysis     | SonarQube                         | PR-level analysis     |
| SBOM         | CycloneDX                         | Dependency tracking   |
| Testing      | Testcontainers                    | Integration/slice     |
| Style        | Google Java Style Guide           | Enforced via linter   |

## Development Workflow

- All CI MUST run via GitHub Actions: build, test, lint, publish.
- PRs MUST pass all quality gates (tests, coverage, style, static
  analysis) before merge.
- Commits MUST follow semantic versioning prefixes
  (`feat:`, `fix:`, `chore:`, etc.) and include Jira ticket
  numbers where applicable.
- Docker images MUST be built and published as part of CI on
  successful merge to main.
- The existing website at `/Users/simonrowe/workspace/simonjamesrowe/react-ui`
  serves as the design reference for the frontend rebuild.
- MongoDB backup data at `/Users/simonrowe/backups` MUST be
  consulted for data model and content migration decisions.

## Governance

This constitution is the authoritative source of project standards
for simonrowe-dev-monorepo. All implementation decisions, code
reviews, and architectural choices MUST comply with the principles
defined above.

- **Amendment procedure**: Any principle change MUST be documented
  with rationale, versioned per semantic versioning, and reflected
  in this file before implementation begins.
- **Versioning policy**: MAJOR for principle removals or
  redefinitions; MINOR for new principles or material expansions;
  PATCH for clarifications and wording fixes.
- **Compliance review**: Every PR MUST be checked against these
  principles. Violations MUST be resolved before merge unless
  explicitly justified in a Complexity Tracking table.

**Version**: 1.0.0 | **Ratified**: 2026-02-21 | **Last Amended**: 2026-02-21
