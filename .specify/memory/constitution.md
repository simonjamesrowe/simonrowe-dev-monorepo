<!--
  Sync Impact Report
  ==================
  Version change: 1.10.0 → 1.11.0 (MINOR)

  Modified principles:
    - Principle II: Modern Java & React Stack
      Changed: RAG advisor from QuestionAnswerAdvisor to custom
      ContextAwareQuestionAnswerAdvisor with conversation-aware
      vector search, structured document metadata, and code
      example filtering from general RAG context.

  Added sections:
    - Principle VII: Interactive Site Tour — new section covering
      tour step data model, cross-page navigation, auto-advance
      timer, interactive step actions, and search simulation.
    - Renumbered Backup & Restore to VIII, Shell Scripting to IX.

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
- The frontend Nginx configuration MUST proxy `/api/` and
  `/uploads/` requests to the backend container and MUST serve
  `/index.html` as a fallback for client-side routes so direct
  navigation to React Router URLs works in production.
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
  prevent automated submissions. The reCAPTCHA widget MUST be
  conditionally rendered based on `VITE_RECAPTCHA_SITE_KEY`
  availability — when the sitekey is absent (e.g. local development),
  the form MUST remain functional without reCAPTCHA. Zod form schemas
  MUST accept a `recaptchaEnabled` flag to make token validation
  conditional.
- Frontend styling MUST use plain CSS with BEM naming conventions
  and CSS custom properties (variables) for theming. No CSS
  framework (Bootstrap, Tailwind, etc.) or CSS-in-JS library
  (styled-components, Emotion) MAY be introduced. All styles MUST
  reside in a single `styles.css` file.
- Typography MUST use Inter (body text) and Space Grotesk
  (headings), loaded via Google Fonts with `display=swap` for
  performance. Font declarations MUST use CSS custom properties
  (`--font-main`, `--font-heading`).
- User-uploaded media (images, PDFs) MUST be served by the backend
  via Spring Boot's `ResourceHandlerRegistry` at the `/uploads/**`
  path. The uploads directory MUST be configurable via the
  `UPLOADS_PATH` environment variable (default: `uploads/`
  relative to the backend module). The resource handler MUST
  resolve the path to an absolute URI at startup.
- Lucide React MUST be the icon library for all frontend icons.
  No other icon library (Font Awesome, Material Icons, etc.) MAY
  be introduced.
- The sidebar MUST default to collapsed state, showing only icons
  with tooltips. It MUST include a profile photo icon for home
  navigation and lucide-react icons for each section. Social media
  links MUST appear at the bottom of the sidebar with a divider.
- Frontend routes MUST follow these conventions: `/jobs/{id}` for
  job detail drawers, `/skills-groups/{id}` for skill group drawers,
  `/blogs` for blog listing, `/blogs/{slug}` for blog detail pages.
  Search index URLs generated by the backend MUST match these
  frontend routes exactly.
- AI-powered chat MUST use Spring AI 1.1.4 with OpenAI as the LLM
  provider (`spring-ai-starter-model-openai`). Chat completions MUST
  use GPT 5.4 Nano. Text embeddings for RAG MUST use
  `text-embedding-3-small`. The API key MUST be configured via the
  `OPENAI_API_KEY` environment variable. No other LLM provider SDK
  MAY be introduced.
- The backend MUST expose an MCP (Model Context Protocol) server via
  `spring-ai-starter-mcp-server-webmvc`. MCP tools (e.g.
  `ProfileMcpTools`) provide structured data retrieval capabilities
  to the chat client alongside RAG vector search.
- Semantic search for chat MUST use Spring AI's Elasticsearch vector
  store (`spring-ai-starter-vector-store-elasticsearch`) with the
  existing Elasticsearch instance. Vector embeddings MUST be stored
  alongside keyword search indices. A custom
  `ContextAwareQuestionAnswerAdvisor` MUST be used to inject
  retrieved context into chat prompts. This advisor enriches vector
  search queries with recent conversation history (last N user
  messages) so follow-up questions retrieve relevant documents.
  Retrieved documents MUST include structured metadata (title, URL,
  sourceType) to prevent hallucinated links. Code examples MUST be
  filtered from general RAG context (by `sourceType`) to avoid
  mixing code snippets into conversational responses.
- Content embedding MUST be driven by Kafka events. The
  `EmbeddingService` and `EmbeddingChangeConsumer` MUST
  automatically vectorise content (blogs, jobs, skills, code
  examples) when changes are published. Re-embedding MAY be
  triggered from the admin UI.
- `CodeExample` entities MUST be stored in MongoDB with skill
  associations (`@DBRef`). Admin CRUD for code examples MUST
  follow the same DTO pattern as other admin endpoints.
- Docker redeploy MUST be available from the admin UI. The backend
  MUST use `ProcessBuilder` to execute Docker Compose CLI commands
  (binary mounted from host). Redeploy configuration (compose file
  path, services, docker binary path, self-restart delay) MUST be
  externalised via `@ConfigurationProperties` records.
- Real-time communication (e.g. chat streaming) MUST use Spring
  WebSocket with STOMP protocol. The frontend MUST use `@stomp/stompjs`
  as the WebSocket client library.
- API rate limiting MUST use Bucket4j. Rate limit configuration MUST
  be externalised via `@ConfigurationProperties` records.
- Chat sessions MUST be stored in-memory (`ConcurrentHashMap`) with
  scheduled cleanup. Chat sessions MUST NOT be persisted to MongoDB
  unless a concrete read/recovery requirement exists.
- Markdown content rendered in the frontend MUST use React Markdown
  with GitHub Flavored Markdown support. Fenced code blocks MUST be
  syntax highlighted, and `mermaid` code fences MAY render diagrams.
  Raw HTML rendering MAY only be enabled for trusted first-party
  content managed by the repository or authenticated admin tooling;
  it MUST NOT be enabled for arbitrary user-submitted content.

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
- `@SpringBootTest` integration tests MUST extend
  `AbstractIntegrationTest`, which provides a shared Spring
  application context with singleton MongoDB container
  (`SharedMongoContainer`) and common `@MockitoBean` declarations
  (JwtDecoder, ElasticsearchOperations, VectorStore,
  BlogSearchRepository, ImageVariantGenerator,
  ContentChangePublisher). This ensures all MongoDB-backed
  integration tests reuse a single Spring context and a single
  Testcontainer instance, avoiding redundant context reloads and
  container restarts.
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
- Grafana Alloy MUST be the telemetry collector in production.
  It receives OTLP traces from the backend on port 4317 (gRPC)
  and forwards them to Grafana Cloud Tempo. It also collects
  Docker container logs and forwards them to Grafana Cloud Loki.
  Configuration lives at `/config/alloy/config.alloy`.
- Portainer CE MUST provide a container management UI in
  production, exposed at `console.simonrowe.dev` behind the
  nginx reverse proxy. Data persists in a named Docker volume.
- Nginx MUST act as the production reverse proxy, routing
  requests by hostname to frontend, backend, and Portainer
  services. It MUST handle WebSocket upgrades for chat streaming
  and perform health checks on upstream services.
- Structured logging MUST be used across all services.

### V. Simplicity & Incremental Delivery

Start with the simplest working solution. Add complexity only
when a concrete requirement demands it. YAGNI applies.

- Features MUST be delivered as independently testable increments.
- No premature abstractions — three similar lines are better than
  an unjustified abstraction.
- Data that is only forwarded (e.g. contact form submissions sent
  via email) and never queried MUST NOT be persisted to MongoDB.
  Persistence MUST only be introduced when a concrete read
  requirement exists.

### VI. Admin CMS UX Standards

The admin CMS MUST follow consistent UX patterns for content
management pages. These patterns ensure a professional, efficient
editing experience.

- Admin list pages MUST use Lucide React icons for status indicators
  (e.g. `CheckCircle`/`XCircle` for published state) and action
  buttons (e.g. `Pencil` for edit, `Trash2` for delete) instead of
  text labels.
- The blog editor layout MUST use a two-column top section: Title
  and Short Description on the left half, Featured Image picker on
  the right half. Tags and Skills selectors MUST appear above the
  content editor.
- The MDXEditor markdown content area MUST have a reduced
  `min-height` of 250px to avoid excessive whitespace.
- The Media Library picker MUST render as a right-side sliding
  drawer (using the existing `.drawer`/`.drawer-overlay` CSS
  pattern) rather than a centered modal dialog.
- Blog entities in MongoDB MUST use `@DBRef` for tags and skills
  references. The admin API MUST use a DTO pattern to convert
  between `@DBRef` entity references (backend) and plain string
  IDs (frontend API).

### VII. Interactive Site Tour

The site MUST include an interactive guided tour accessible via a
"Take a Tour" button. The tour MUST showcase key site features
across multiple pages.

- Tour steps MUST be stored in MongoDB (`tourSteps` collection)
  and managed via the admin UI with drag-and-drop reordering.
- Each tour step MUST have: `targetSelector` (CSS selector),
  `title`, `description` (Markdown), `position`, `route`
  (target page path), and optional `autoAdvanceMs` (milliseconds
  before auto-advancing, default 7000ms).
- The tour MUST support cross-page navigation: when a step
  targets an element on a different page, `TourProvider` MUST
  navigate via React Router and poll for the target element
  (up to 2 seconds) before spotlighting it.
- Auto-advance MUST be implemented with a CSS-animated progress
  bar. Hovering the tooltip MUST pause auto-advance. Manual
  Next/Previous clicks MUST reset the timer.
- Interactive step actions MUST be defined in `tourActions.ts`
  mapping selectors to actions (e.g. `openChat`, `clickElement`).
  Steps MAY open drawers, the chat panel, or trigger search
  simulation. Cleanup actions MUST close drawers/panels when
  leaving a step.
- The `SearchSimulation` component MUST drive the actual search
  input by syncing `searchValue` from the tour context into the
  `SiteSearch` component's local state.

### VIII. Backup & Restore

Data backup and restore MUST use simple shell scripts that capture
the current MongoDB state, uploaded media assets, and Elasticsearch
data as a single tarball archive. Remote backup to Google Drive
provides off-site disaster recovery.

- `scripts/backup.sh` MUST dump the `simonrowe` database via
  `mongodump`, copy the `backend/uploads/` directory, and create
  an Elasticsearch snapshot (via the `_snapshot` API with a
  filesystem repository) into a timestamped
  `backup-YYYYMMDD_HHMMSS.tar.gz` archive.
- `scripts/restore.sh` MUST find the latest `backup-*.tar.gz`,
  restore MongoDB via `mongorestore --drop`, copy uploads back
  to `backend/uploads/`, and restore Elasticsearch indices from
  the snapshot.
- Backup and restore MUST handle Elasticsearch being unavailable
  gracefully — completing the MongoDB and uploads portions and
  warning about skipped Elasticsearch data.
- Google Drive remote backup MUST be available from the admin UI
  via the `DataOperationsController`. The `GoogleDriveService`
  MUST use the Google API client with OAuth2 credentials
  (`GOOGLE_DRIVE_CLIENT_ID`, `GOOGLE_DRIVE_CLIENT_SECRET`,
  `GOOGLE_DRIVE_REFRESH_TOKEN`, `GOOGLE_DRIVE_FOLDER_ID`).
  Backups are uploaded as ZIP files to a configurable Drive folder.
- Legacy Strapi migration scripts (`migrate-strapi-data.js`,
  `run-migration.sh`, `restore-backup.sh`) are retained for
  reference but the new `backup.sh`/`restore.sh` pair is the
  canonical mechanism going forward.
- Backup archives MUST be stored in `/Users/simonrowe/backups/`
  by default (configurable via first argument).

### IX. Shell Scripting Standards

All shell scripts MUST use bash with `#!/usr/bin/env bash` shebang
and `set -euo pipefail` for strict error handling. No PowerShell,
fish, or other shell languages MAY be used for project scripts.

- Scripts MUST resolve `SCRIPT_DIR` and `PROJECT_DIR` using
  `$(cd "$(dirname "$0")" && pwd)` for portable path resolution.
- MongoDB migration scripts (`.js` files) MUST have a companion
  shell wrapper script (`.sh`) that handles: container discovery,
  file copying into the container, `mongosh` execution, and
  cleanup. The `.js` file MUST NOT be run directly.
- Shell wrapper scripts MUST validate preconditions (container
  running, source files exist) and exit with clear error messages
  on failure.
- Temporary files copied into containers MUST be cleaned up after
  execution.
- Scripts MUST use `docker cp` and `docker exec` for interacting
  with containerised services (not volume mounts for scripts).

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
| CSS approach | Plain CSS + BEM + custom props    | Single styles.css      |
| Typography   | Inter + Space Grotesk             | Google Fonts, swap     |
| Media serving| Spring ResourceHandlerRegistry    | /uploads/**, UPLOADS_PATH env |
| Icons        | Lucide React                      | All frontend icons     |
| Routing      | React Router                      | /jobs/{id}, /skills-groups/{id}, /blogs/{slug} |
| AI/LLM       | Spring AI + OpenAI                | 1.1.4, GPT 5.4 Nano (chat)      |
| MCP Server   | Spring AI MCP Server              | spring-ai-starter-mcp-server-webmvc |
| Embeddings   | OpenAI text-embedding-3-small     | 1536 dims, via spring-ai-starter-model-openai |
| Vector Store | Spring AI + Elasticsearch         | spring-ai-starter-vector-store-elasticsearch |
| Google Drive | Google API Client                 | OAuth2, remote backup/restore    |
| Telemetry    | Grafana Alloy                     | OTLP traces (Tempo) + logs (Loki) |
| Container Mgmt | Portainer CE                    | console.simonrowe.dev            |
| Reverse Proxy| Nginx                             | Alpine, hostname-based routing   |
| WebSocket    | Spring WebSocket (STOMP)          | Real-time chat streaming         |
| FE WebSocket | @stomp/stompjs                    | Frontend STOMP client            |
| Rate Limiting| Bucket4j                          | 8.16.x, token-bucket algorithm   |
| Content render| React Markdown + Mermaid + Prism | Trusted first-party markdown only |
| Scripting    | Bash (#!/usr/bin/env bash)        | set -euo pipefail, strict mode |

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
- Local development seeding MUST use the restore script
  (`scripts/restore-backup.sh`) which auto-detects Strapi vs
  native backup format, transforms data to the Spring Boot schema
  via `scripts/migrate-strapi-data.js`, and copies uploaded media
  to `backend/uploads/`.

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

**Version**: 1.11.0 | **Ratified**: 2026-02-21 | **Last Amended**: 2026-04-12
