# simonrowe.dev Monorepo

Personal website for [simonrowe.dev](https://simonrowe.dev) — a full-stack application with a Java/Spring Boot backend and React frontend.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK | 21 | `java -version` |
| Node.js | 22 LTS | `node --version` |
| Docker | Latest | `docker --version` |
| Docker Compose | v2 | `docker compose version` |

## Quickstart

### 1. Start infrastructure services

```bash
docker compose up -d
```

Wait for services to become healthy:

```bash
docker compose ps
```

### 2. Start the backend

```bash
./gradlew :backend:bootRun
```

- Application: http://localhost:8080
- Management: http://localhost:8081

Verify health:

```bash
curl http://localhost:8081/actuator/health
```

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

### 4. Restore seed data (first time only)

The app requires data in MongoDB to display content. Restore from a Strapi backup:

```bash
./scripts/restore-backup.sh
```

This finds the latest `strapi-backup-*.tar.gz` in `~/backups`, transforms the Strapi data to match the Spring Boot schema, and copies images to `backend/uploads/`. Restart the backend after restoring.

To use a different backup directory:

```bash
./scripts/restore-backup.sh /path/to/backups
```

## Data Backup & Restore

### Create a backup

Dumps the current MongoDB data and uploads into a timestamped tarball:

```bash
./scripts/create-backup.sh
```

Backups are saved to `~/backups` by default. To specify a different directory:

```bash
./scripts/create-backup.sh /path/to/backups
```

### Restore a backup

```bash
./scripts/restore-backup.sh
```

| Script | Purpose |
|--------|---------|
| `scripts/restore-backup.sh` | Extract, transform, and load a Strapi backup into MongoDB + copy images |
| `scripts/create-backup.sh` | Dump current MongoDB data + images into a backup tarball |
| `scripts/migrate-strapi-data.js` | Mongosh script that transforms Strapi collections to Spring Boot schema (used by restore) |

## Content Management (Admin Panel)

The site includes an authenticated admin panel at `/admin` for managing blogs, jobs, skills, media, and other content. It requires Auth0 for authentication — see the [Auth0 Setup Guide](docs/auth0-setup.md) for configuration instructions.

## Running Tests

### Backend

```bash
./gradlew :backend:test
```

Integration tests use Testcontainers — Docker Compose services are not required.

### Frontend

```bash
cd frontend
npm test
```

### Full quality gate

```bash
./gradlew check
```

Runs Checkstyle, tests, and JaCoCo coverage verification.

## Build Container Images

### Backend (GraalVM native image via Cloud Native Buildpacks)

```bash
./gradlew :backend:bootBuildImage --imageName=simonrowe-backend:local
```

Note: This compiles the backend to a GraalVM native image inside a Buildpack container. Requires Docker running and ~8GB RAM available. Build takes 3-5 minutes.

### Frontend

```bash
docker build -f Dockerfile.frontend -t simonrowe-frontend:local .
```

## Production Deployment

```bash
docker compose -f docker-compose.prod.yml up -d
```

View the Pinggy tunnel URL:

```bash
docker compose -f docker-compose.prod.yml logs pinggy
```

## Project Structure

```
backend/             Java/Spring Boot API service
frontend/            React/TypeScript SPA
scripts/             Backup and restore scripts
config/
  checkstyle/        Google Java Style config
  otel/              OpenTelemetry Collector config
.github/workflows/   CI/CD pipelines
```

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.5.9, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch
- **Runtime**: GraalVM native image via `bootBuildImage` (Cloud Native Buildpacks)
- **Frontend**: React 19, TypeScript, Vite
- **Infrastructure**: MongoDB 8, Kafka 7.8 (KRaft), Elasticsearch 8.17
- **Observability**: Spring Boot Actuator, Prometheus metrics, OpenTelemetry Spring Boot Starter (compile-time tracing)
- **Quality**: Checkstyle (Google style), JaCoCo (80% coverage), CycloneDX BOM, SonarCloud
