# simonrowe-dev-monorepo Development Guidelines

Last updated: 2026-03-15

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
- 001-add-portainer: Added N/A (infrastructure-only change — Docker Compose YAML + Nginx config) + Portainer CE Docker image (`portainer/portainer-ce:latest`), existing nginx reverse proxy
- 016-prod-health-scripts: Added Bash (strict mode: `set -euo pipefail`) + Docker Compose, curl, systemd (optional, for persistent monitoring)
- 015-rag-vector-chat: Added Java 21 (backend), TypeScript (frontend) + Spring Boot 3.5.9, Spring AI 1.1.4 (`spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-elasticsearch`, `spring-ai-advisors-vector-store`), React (latest stable)
- 015-landing-ai-redesign: Added TypeScript (frontend), React (latest stable) + React, React Router, Lucide React, @stomp/stompjs, react-markdown

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->

## Active Technologies
- Bash (strict mode: `set -euo pipefail`) + Docker Compose, curl, systemd (optional, for persistent monitoring) (016-prod-health-scripts)
- N/A (log files only) (016-prod-health-scripts)
- Java 21 (backend), TypeScript (frontend) + Spring Boot 3.5.9, Spring AI 1.1.4 (`spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-elasticsearch`, `spring-ai-advisors-vector-store`), React (latest stable) (015-rag-vector-chat)
- MongoDB 8 (primary), Elasticsearch 8.17.0 (search + vector store), Kafka (async messaging) (015-rag-vector-chat)
- TypeScript (frontend), React (latest stable) + React, React Router, Lucide React, @stomp/stompjs, react-markdown (015-landing-ai-redesign)
- N/A (frontend-only changes; chat uses existing WebSocket service) (015-landing-ai-redesign)
- N/A (infrastructure-only change — Docker Compose YAML + Nginx config) + Portainer CE Docker image (`portainer/portainer-ce:latest`), existing nginx reverse proxy (001-add-portainer)
- Named Docker volume for Portainer data (user accounts, settings, environment config) (001-add-portainer)
