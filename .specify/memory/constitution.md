<!--
  Sync Impact Report
  ==================
  Version change: 1.2.1 → 1.3.0 (MINOR)

  Modified principles:
    - Principle II: Modern Java & React Stack
      Added: Email transport standard (Spring Boot Starter Mail +
      Brevo SMTP relay). Added: Frontend form validation standard
      (React Hook Form + Zod).
    - Principle V: Simplicity & Incremental Delivery
      Added: Transient data policy — data that is only forwarded
      and not queried MUST NOT be persisted to MongoDB.

  Added sections:
    - Technology Stack Constraints: new rows for Email, Spam
      Protection, Form Validation (frontend), and reCAPTCHA.
    - Development Workflow: note on VITE_* env vars being baked
      into the frontend image at build time via Docker build args.

  Removed sections: None

  Templates requiring updates:
    ✅ .specify/templates/plan-template.md — no changes needed
    ✅ .specify/templates/spec-template.md — no changes needed
    ✅ .specify/templates/tasks-template.md — no changes needed

  Follow-up TODOs: None
-->

# simonrowe-dev-monorepo Constitution

## Core Principles

### I. Monorepo with Separate Containers

All source code for simonrowe.dev MUST live in a single monorepo.
The backend and frontend MUST be built and deployed as separate
containers. Shared configuration (Docker Compose, CI workflows)
lives at the repository root.

- Backend and frontend MUST NOT share a runtime container.
- The backend container MUST be built using Spring Boot's
  `bootBuildImage` Gradle task (Cloud Native Buildpacks). A
  traditional multi-stage Dockerfile MUST NOT be used for the
  backend.
- The frontend container MUST be built using a multi-stage
  Dockerfile (Node.js build + Nginx runtime).
- Docker Compose MUST be the local and production orchestration
  mechanism (with Pinggy for public exposure).
- Images MUST be published to GitHub Container Registry (ghcr.io).

### II. Modern Java & React Stack

The backend MUST use Java 21, Spring Boot 3.5.x, Gradle, MongoDB,
Kafka, and Elasticsearch. The frontend MUST use the latest stable
React release. No CMS — content is managed through application
code and MongoDB persistence.

- Backend build tool MUST be Gradle (Kotlin DSL preferred).
- The backend MUST be compiled to a GraalVM native image using
  the `org.graalvm.buildtools.native` Gradle plugin. The
  `bootBuildImage` task produces a container with the native
  executable — no JVM is included in the runtime image.
- Java 21 is the minimum required version (virtual threads,
  pattern matching, records). Upgrade to a newer LTS when
  Buildpack base images and Spring Boot officially support it.
- MongoDB MUST be the primary persistence store.
- Kafka MUST be used for asynchronous messaging.
- Elasticsearch MUST be used for search functionality.
- Auth0 MUST be the sole authentication provider; no self-service
  registration — users are provisioned directly in Auth0.
- Spring Boot Actuator/management endpoints MUST run on a
  separate port from application traffic.
- Transactional email MUST be sent via Spring Boot Starter Mail
  using the Brevo SMTP relay (smtp-relay.brevo.com:587). No
  third-party email SDK (e.g. SendGrid Java SDK) MAY be
  introduced; the standard JavaMailSender abstraction is
  sufficient.
- Frontend forms MUST use React Hook Form for state management
  and Zod for schema-based validation. Both client-side (Zod)
  and server-side (Jakarta Bean Validation) constraints MUST be
  defined and kept in sync.
- Google reCAPTCHA v2 ("I'm not a robot" checkbox) MUST be used
  on all public-facing forms that submit data to the backend, to
  prevent automated submissions.

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
  Exception: `@MockitoBean` (Spring Framework 6.2+, preferred over
  the deprecated `@MockBean`) MAY be used to suppress an
  infrastructure component that a test does not exercise, provided
  that component has its own dedicated Testcontainer-backed
  integration test (e.g., mock Elasticsearch in a MongoDB-only
  controller test when a separate SearchControllerTest uses a real
  Elasticsearch container).
- Frontend tests MUST exist for critical user journeys.

### IV. Observability & Operability

The system MUST be observable in production from day one.
Debugging MUST NOT require SSH access or log tailing on hosts.

- Prometheus metrics MUST be exposed via a dedicated actuator port
  for scraping.
- OpenTelemetry MUST be integrated for distributed tracing using
  the OpenTelemetry Spring Boot Starter. The OpenTelemetry Java
  Agent MUST NOT be used because it is incompatible with GraalVM
  native images.
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
- Data that is only forwarded (e.g. contact form submissions sent
  via email) and never queried MUST NOT be persisted to MongoDB.
  Persistence MUST only be introduced when a concrete read
  requirement exists.

## Technology Stack Constraints

| Layer        | Technology                        | Version/Notes         |
|--------------|-----------------------------------|-----------------------|
| Language     | Java                              | 21 (LTS)              |
| Framework    | Spring Boot                       | 3.5.x                 |
| Build        | Gradle (Kotlin DSL)               | Latest stable          |
| Native Image | GraalVM Native Image              | Via buildtools plugin  |
| Packaging    | Cloud Native Buildpacks           | Via bootBuildImage     |
| Database     | MongoDB                           | Latest stable          |
| Messaging    | Apache Kafka                      | Latest stable          |
| Search       | Elasticsearch                     | Latest stable          |
| Frontend     | React                             | Latest stable          |
| Auth         | Auth0                             | Managed service        |
| CI/CD        | GitHub Actions                    | Build, test, publish   |
| Registry     | GitHub Container Registry (ghcr)  | Docker images          |
| Orchestration| Docker Compose                    | Local + production     |
| Exposure     | Pinggy                            | Production tunneling   |
| Tracing      | OpenTelemetry Spring Boot Starter | Compile-time instrumentation |
| Metrics      | Prometheus via Actuator           | Separate actuator port |
| Coverage     | JaCoCo                            | Enforced thresholds    |
| Analysis     | SonarQube                         | PR-level analysis      |
| SBOM         | CycloneDX                         | Dependency tracking    |
| Testing      | Testcontainers                    | Integration/slice      |
| Style        | Google Java Style Guide           | Enforced via linter    |
| Email        | Spring Boot Starter Mail + Brevo  | SMTP relay, port 587   |
| Spam protect | Google reCAPTCHA v2               | All public forms       |
| Form state   | React Hook Form + Zod             | Frontend forms         |

## Development Workflow

- All CI MUST run via GitHub Actions: build, test, lint, publish.
- PRs MUST pass all quality gates (tests, coverage, style, static
  analysis) before merge.
- Commits MUST follow semantic versioning prefixes
  (`feat:`, `fix:`, `chore:`, etc.) and include Jira ticket
  numbers where applicable.
- The backend Docker image MUST be built via
  `./gradlew bootBuildImage` and published as part of CI on
  successful merge to main. A Dockerfile MUST NOT be used for
  the backend.
- The frontend Docker image MUST be built via `docker build` with
  a multi-stage Dockerfile and published as part of CI on
  successful merge to main.
- `VITE_*` environment variables are baked into the frontend
  bundle at build time and MUST be passed as Docker build args
  (e.g. `--build-arg VITE_RECAPTCHA_SITE_KEY=...`). They CANNOT
  be injected at container runtime.
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

**Version**: 1.3.0 | **Ratified**: 2026-02-21 | **Last Amended**: 2026-02-24
