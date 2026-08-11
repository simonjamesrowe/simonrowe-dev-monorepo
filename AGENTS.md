<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
specs/032-on-demand-narration/plan.md
<!-- SPECKIT END -->

## Active Technologies
- Java 21, TypeScript 5.7, React 19 + Spring Boot 3.5.x, Spring AI 1.1.4, Spring WebSocket STOMP, MongoDB, Elasticsearch, Kafka, Vite 6, @stomp/stompjs, lucide-react, react-markdown, react-syntax-highlighter (feat/frontend/landing-chat-widgets)
- MongoDB for existing profile/jobs/skills/blog/code-example data; in-memory chat sessions only for chat state (feat/frontend/landing-chat-widgets)
- Java 21, TypeScript 5.7, React 19 + Spring Boot 3.5.x, MongoDB, Vite 6, React Router, lucide-react, Testing Library, Vitest, JUnit 5 (feat/frontend/landing-chat-widgets)
- Java 21, TypeScript 5.7, React 19 + Spring Boot 3.5.x, Spring Kafka, Spring Data MongoDB, Google Auth Library, Spring RestClient, CommonMark, Bucket4j, Micrometer, Vitest (simonrowe/feat/audio-on-demand)
- MongoDB narration records, local uploaded MP3 assets, and temporary private Cloud Storage Long Audio output (simonrowe/feat/audio-on-demand)

## Recent Changes
- feat/frontend/landing-chat-widgets: Added Java 21, TypeScript 5.7, React 19 + Spring Boot 3.5.x, Spring AI 1.1.4, Spring WebSocket STOMP, MongoDB, Elasticsearch, Kafka, Vite 6, @stomp/stompjs, lucide-react, react-markdown, react-syntax-highlighter
- feat/frontend/landing-chat-widgets: Planned landing/profile split with centered chat-first homepage, public Profile page, and retargeted tour seed data

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
```
