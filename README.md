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
