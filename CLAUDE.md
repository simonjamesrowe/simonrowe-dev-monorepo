# simonrowe-dev-monorepo Development Guidelines

Last updated: 2026-04-12

## Technology Stack

- **Backend**: Java 21, Spring Boot 3.5.x, Gradle, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, Spring Security (OAuth2 Resource Server)
- **Frontend**: TypeScript, React (latest stable), Vite, MDXEditor, Lucide React, react-markdown
- **Persistence**: MongoDB 8 (primary), Elasticsearch (search), Kafka (async messaging)
- **Auth**: Auth0 (OAuth2/JWT)
- **CSS**: Plain CSS with BEM naming, single `styles.css` file, CSS custom properties for theming

## Project Structure

```text
backend/           # Spring Boot application
  src/main/java/   # Java source (com.simonrowe.*)
  src/test/java/   # Tests with Testcontainers
  uploads/         # Media asset storage
frontend/          # React + Vite application
  src/             # TypeScript source
  tests/           # Vitest tests
scripts/           # Bash scripts for backup, restore, migration
```

## Commands

```bash
# Start/stop applications (sources env vars from .env files)
./scripts/start.sh                      # Start both backend and frontend together
./scripts/stop.sh                       # Stop both backend and frontend
./scripts/start-backend.sh              # Start backend only (port 8080)
./scripts/start-frontend.sh             # Start frontend only (port 5173)

# Tests
cd backend && ../gradlew test           # Run backend tests
cd frontend && npm test                 # Run frontend tests (vitest)

# Backup & Restore
./scripts/backup.sh                     # Create backup to /Users/simonrowe/backups/
./scripts/restore.sh                    # Restore latest backup

# Environment setup (run automatically by Conductor on workspace creation)
# Copies ~/workspace/simonjamesrowe/env to backend/.env and frontend/.env
```

## Code Style

- Java: Google Java Style Guide, enforced via Checkstyle
- TypeScript: Standard conventions, ESLint
- CSS: BEM naming, plain CSS with custom properties

## Key Design Decisions

- Blog tags/skills use MongoDB `@DBRef` references; admin API uses DTO pattern converting between `@DBRef` entities and string IDs for the frontend
- Admin CMS uses Lucide React icons for actions/status, right-side drawer for Media Library, two-column layout for blog editor
- Uploads served via Spring `ResourceHandlerRegistry` at `/uploads/**`, path configurable via `UPLOADS_PATH` env var (default: `uploads/` relative to backend CWD)
- `scripts/backup.sh` and `scripts/restore.sh` are the canonical data management scripts (legacy Strapi migration scripts retained for reference)

## Recent Changes
- 020-tour-blog-content: Fixed site tour with cross-page navigation, auto-advance timer with CSS progress bar, interactive demos (chat, drawers, search simulation), 10-step tour flow. Added blog post on RAG quality.
- 019-fix-chat-hallucination: Custom `ContextAwareQuestionAnswerAdvisor` for conversation-aware vector search, structured document metadata, code example filtering from general RAG context.
- 018-code-examples-admin-ux: Code example CRUD in admin, improved AI chat quality, admin UX refinements.
- 017-light-dark-mode: Light/dark theme via CSS custom properties, `ThemeContext` with localStorage persistence, `prefers-color-scheme` fallback, image compression script.
- 001-add-portainer: Portainer CE container management at console.simonrowe.dev behind nginx reverse proxy.
- 016-prod-health-scripts: Production health monitoring scripts with Pinggy health check.

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->

## Active Technologies
- TypeScript (latest), CSS3 custom properties + React (latest stable), Lucide React (icons), Vite (build)
- Browser localStorage (theme preference only)
- Spring AI 1.1.4 with OpenAI (GPT 5.4 Nano for chat, text-embedding-3-small for RAG)
- MCP (Model Context Protocol) server for tool-augmented chat
- Interactive site tour with auto-advance, cross-page navigation, and step actions
