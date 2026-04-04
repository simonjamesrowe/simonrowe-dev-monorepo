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
- 001-website-redesign: Added TypeScript (frontend), Java 21 (backend — no changes) + React (latest stable), React Router, Lucide React, React Hook Form, Zod, @stomp/stompjs, React Markdown
- 013-blog-posts-phase-2: Added Java 21 (backend), TypeScript (frontend) — no code changes needed; content-only feature + MongoDB 8 (mongosh for migration), Docker (container exec for script runner)
- 012-docker-compose-deploy: Added Nginx config (reverse proxy), Docker Compose YAML, Bash (scripts), Dockerfile modifications + Docker, Docker Compose, nginx:alpine, pinggy/pinggy, GHCR images
- 011-admin-data-ops: Added Java 21 (backend), TypeScript (frontend) + Spring Boot 3.5.x, Spring Data MongoDB, Spring Data Elasticsearch, Google Drive API v3 (`google-api-services-drive`), Google Auth Library, React (latest stable), Lucide Reac

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->

## Active Technologies
- TypeScript (frontend), Java 21 (backend — no changes) + React (latest stable), React Router, Lucide React, React Hook Form, Zod, @stomp/stompjs, React Markdown (001-website-redesign)
- MongoDB (unchanged), Elasticsearch (unchanged) (001-website-redesign)
- Nginx config (reverse proxy), Docker Compose YAML, Bash (scripts), Dockerfile modifications + Docker, Docker Compose, nginx:alpine, pinggy/pinggy, GHCR images (012-docker-compose-deploy)
- MongoDB 8, Kafka 7.8.0, Elasticsearch 8.17.0 (all via named Docker volumes) (012-docker-compose-deploy)
- Java 21 (backend), TypeScript (frontend) — no code changes needed; content-only feature + MongoDB 8 (mongosh for migration), Docker (container exec for script runner) (013-blog-posts-phase-2)
- MongoDB `blogs` and `tags` collections (013-blog-posts-phase-2)
