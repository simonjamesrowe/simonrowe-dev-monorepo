# Implementation Plan: Project Infrastructure

**Branch**: `001-project-infrastructure` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-project-infrastructure/spec.md`

## Summary

Infrastructure scaffolding for the simonrowe.dev monorepo providing a complete development and deployment foundation. This includes a Gradle multi-project build (Kotlin DSL) for the Java 25 / Spring Boot 4 backend, a React frontend project, Docker Compose orchestration for both local development and production, GitHub Actions CI/CD pipelines with full quality gate enforcement, and production deployment via Pinggy tunneling. The plan delivers all three user stories: local development bootstrap (P1), automated quality verification (P2), and production deployment (P3).

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript/JavaScript (frontend)
**Primary Dependencies**: Spring Boot 4.x, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, Spring Boot Actuator, OpenTelemetry, React (latest stable)
**Storage**: MongoDB (primary persistence), Elasticsearch (search), Kafka (messaging)
**Testing**: JUnit 5 + Testcontainers (backend integration), Jest/Vitest (frontend), JaCoCo (coverage)
**Target Platform**: Docker containers on Linux (production via Docker Compose + Pinggy)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: CI pipeline completes in under 10 minutes; local environment starts in under 5 minutes; production deployment accessible in under 15 minutes
**Constraints**: All quality gates must pass before merge; Prometheus metrics on dedicated management port; no mocked infrastructure in integration tests
**Scale/Scope**: Single backend service, single frontend application, 3 infrastructure services (MongoDB, Kafka, Elasticsearch), single production deployment target

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Justification |
|---|-----------|--------|---------------|
| I | Monorepo with Separate Containers | PASS | Single repository with `backend/` and `frontend/` directories. Separate Dockerfiles produce independent container images. Docker Compose at repo root for orchestration. Images published to ghcr.io. |
| II | Modern Java & React Stack | PASS | Java 25, Spring Boot 4, Gradle Kotlin DSL, MongoDB, Kafka, Elasticsearch for backend. React latest stable for frontend. Auth0 configured but not implemented until feature specs require it. Spring Boot Actuator on separate management port. |
| III | Quality Gates (NON-NEGOTIABLE) | PASS | Google Java Style via Checkstyle plugin. JaCoCo with enforced coverage thresholds. SonarQube analysis on every PR. CycloneDX BOM generation. Testcontainers for integration tests. Frontend test infrastructure established. |
| IV | Observability & Operability | PASS | Prometheus metrics via Spring Boot Actuator on dedicated port. OpenTelemetry integration for distributed tracing. Structured JSON logging configured for all services. |
| V | Simplicity & Incremental Delivery | PASS | Minimal scaffolding with no premature abstractions. No application entities or business logic -- only infrastructure. Three user stories delivered incrementally by priority. YAGNI applied throughout. |

## Project Structure

### Documentation (this feature)

```text
specs/001-project-infrastructure/
├── plan.md              # This file
├── research.md          # Phase 0: Technology research and decisions
├── data-model.md        # Phase 1: Infrastructure configuration model
├── quickstart.md        # Phase 1: Developer quickstart guide
├── contracts/           # Phase 1: API contracts
│   └── health-api.yaml  # Actuator health/metrics OpenAPI spec
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── build.gradle.kts                    # Backend build configuration
├── src/
│   ├── main/
│   │   ├── java/com/simonrowe/
│   │   │   └── Application.java        # Spring Boot entry point
│   │   └── resources/
│   │       ├── application.yml          # Application configuration
│   │       └── logback-spring.xml       # Structured logging config
│   └── test/
│       ├── java/com/simonrowe/
│       │   └── ApplicationTests.java    # Smoke test with Testcontainers
│       └── resources/
│           └── application-test.yml     # Test configuration overrides

frontend/
├── package.json                        # Dependencies and scripts
├── tsconfig.json                       # TypeScript configuration
├── vite.config.ts                      # Vite build configuration
├── Dockerfile                          # Frontend container build
├── src/
│   ├── App.tsx                         # Root application component
│   ├── main.tsx                        # Entry point
│   ├── components/                     # Shared components
│   ├── pages/                          # Page components
│   └── services/                       # API client services
├── public/
│   └── index.html                      # HTML template
└── tests/
    └── App.test.tsx                    # Basic smoke test

build.gradle.kts                        # Root build file (plugins, shared config)
settings.gradle.kts                     # Multi-project settings (includes backend)
gradle.properties                       # Gradle configuration properties
gradle/
├── wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── libs.versions.toml                  # Version catalog

docker-compose.yml                      # Local development orchestration
docker-compose.prod.yml                 # Production orchestration with Pinggy
Dockerfile.backend                      # Backend container build (multi-stage)
Dockerfile.frontend                     # Frontend container build (multi-stage)

.github/
└── workflows/
    ├── ci.yml                          # PR quality gates (build, test, lint, analyze)
    └── publish.yml                     # Main branch: build + publish to ghcr.io

config/
├── checkstyle/
│   └── google_checks.xml              # Google Java Style checkstyle config
└── otel/
    └── otel-collector-config.yaml     # OpenTelemetry Collector config (production)

.editorconfig                           # Editor configuration
.gitignore                              # Git ignore rules
```

**Structure Decision**: Option 2 (Web application) selected. The `backend/` directory contains the Gradle subproject for the Spring Boot 4 API service. The `frontend/` directory contains the React application as an independent npm project. Root-level Gradle files configure the multi-project build. Docker Compose files and Dockerfiles live at the repository root for shared orchestration. GitHub Actions workflows live in `.github/workflows/`.

## Complexity Tracking

No constitution violations. All principles pass without exception.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | *N/A* | *N/A* |
