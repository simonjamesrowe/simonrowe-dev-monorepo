# Tasks: Project Infrastructure

**Feature**: 001-project-infrastructure
**Date**: 2026-02-22
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

**Note**: This task list reflects constitution v1.2.0 (Java 21, Spring Boot 3.5.x, GraalVM native image via bootBuildImage, OpenTelemetry Spring Boot Starter). Tasks marked [x] were completed in the initial implementation. Tasks marked [ ] require new work or rework to align with the updated constitution.

---

## Phase 1: Setup

Project skeleton: root Gradle build, backend Spring Boot project, frontend React project, and repository configuration files.

- [x] T001 Create root Gradle build with Kotlin DSL plugin management and shared configuration in `build.gradle.kts`
- [x] T002 Create Gradle settings with multi-project include for backend in `settings.gradle.kts`
- [x] T003 Create Gradle version catalog with Spring Boot, Testcontainers, and tooling versions in `gradle/libs.versions.toml`
- [x] T004 Create Gradle properties with JVM args and project metadata in `gradle.properties`
- [x] T005 Initialize Gradle wrapper (jar + properties) in `gradle/wrapper/`
- [x] T006 Create the `gradlew` and `gradlew.bat` wrapper scripts at the repository root
- [x] T007 Create backend build configuration with Spring Boot, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, and Actuator dependencies in `backend/build.gradle.kts`
- [x] T008 Create Spring Boot application entry point with `@SpringBootApplication` annotation in `backend/src/main/java/com/simonrowe/Application.java`
- [x] T009 Create base application configuration with server port, management port, MongoDB URI, Kafka bootstrap servers, Elasticsearch URIs, and virtual threads enabled in `backend/src/main/resources/application.yml`
- [x] T010 Create structured JSON logging configuration with Logback in `backend/src/main/resources/logback-spring.xml`
- [x] T011 Create frontend project with React, TypeScript, Vite dependencies and dev/build/test scripts in `frontend/package.json`
- [x] T012 Create TypeScript configuration for the frontend project in `frontend/tsconfig.json`
- [x] T013 Create Vite build configuration with API proxy to backend on port 8080 in `frontend/vite.config.ts`
- [x] T014 Create root React application component in `frontend/src/App.tsx`
- [x] T015 Create React application entry point in `frontend/src/main.tsx`
- [x] T016 Create HTML template for the frontend application in `frontend/index.html`
- [x] T017 Create `.gitignore` with rules for Gradle, Node.js, IDE files, Docker volumes, and build artifacts at the repository root
- [x] T018 Create `.editorconfig` with indentation, charset, and line ending rules at the repository root

---

## Phase 2: Foundational

Docker Compose for local development, quality gate tooling (Checkstyle, JaCoCo, CycloneDX), GraalVM native image configuration, OpenTelemetry Spring Boot Starter, and Testcontainers test infrastructure.

- [x] T019 Create Docker Compose for local development with MongoDB 8, Kafka 7.8 (KRaft mode), and Elasticsearch 8.17 including health checks, named volumes, and port mappings in `docker-compose.yml`
- [x] T020 Create multi-stage frontend Dockerfile with Node.js for build and Nginx for serving, including reverse proxy configuration for `/api` routes in `Dockerfile.frontend`
- [x] T021 Download and add Google Java Style Checkstyle configuration in `config/checkstyle/google_checks.xml`
- [x] T022 Add Checkstyle plugin configuration with `maxWarnings = 0` and reference to `google_checks.xml` in `backend/build.gradle.kts`
- [x] T023 Add JaCoCo plugin configuration with XML/HTML reports and 80% minimum line coverage enforcement linked to the `check` task in `backend/build.gradle.kts`
- [x] T024 Add CycloneDX BOM plugin configuration scoped to `runtimeClasspath` with output to `build/reports/bom` in `backend/build.gradle.kts`
- [x] T025 Add SonarQube plugin configuration with project key, organization, host URL, and JaCoCo report path in `build.gradle.kts`
- [x] T026 Update Gradle version catalog to Spring Boot 3.5.x, add OpenTelemetry Spring Boot Starter and OTLP exporter version entries, and add GraalVM buildtools plugin version in `gradle/libs.versions.toml`
- [x] T027 Add `org.graalvm.buildtools.native` plugin to the backend build for GraalVM native image compilation in `backend/build.gradle.kts`
- [x] T028 Add OpenTelemetry Spring Boot Starter (`io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`) and OTLP exporter dependencies to `backend/build.gradle.kts`, replacing the Java Agent approach
- [x] T029 Remove `Dockerfile.backend` — backend container is now built via `./gradlew bootBuildImage` (Cloud Native Buildpacks) per constitution Principle I
- [x] T030 Create Testcontainers base test configuration with `@SpringBootTest`, `@Testcontainers`, and `@ServiceConnection` for MongoDB, Kafka, and Elasticsearch in `backend/src/test/java/com/simonrowe/ApplicationTests.java`
- [x] T031 Create test configuration overrides for Testcontainers in `backend/src/test/resources/application-test.yml`

---

## Phase 3: US1 — Local Development Bootstrap

Implements User Story 1 (P1): A developer can clone the repo and have a complete working development environment with all services running locally.

**Goal**: Developer clones repo, runs `docker compose up -d` and `./gradlew :backend:bootRun`, and has a fully functional dev environment.

**Independent Test**: Start infrastructure via Docker Compose, run backend and frontend, verify health endpoint returns composite status for all services.

- [x] T032 [US1] Configure Spring Boot Actuator on management port 8081 with health, prometheus, and info endpoints exposed in `backend/src/main/resources/application.yml`
- [x] T033 [US1] Configure MongoDB, Kafka, and Elasticsearch health indicator auto-detection via their respective Spring Data/Kafka dependencies in `backend/build.gradle.kts`
- [x] T034 [US1] Configure Spring Boot DevTools for automatic restart on code changes in `backend/build.gradle.kts`
- [x] T035 [US1] Configure Vite dev server with HMR and API proxy to `http://localhost:8080` in `frontend/vite.config.ts`
- [x] T036 [P] [US1] Create a basic frontend smoke test to verify App component renders in `frontend/tests/App.test.tsx`
- [x] T037 [US1] Verify health endpoint returns composite status with mongo, kafka, and elasticsearch components per contract in `specs/001-project-infrastructure/contracts/health-api.yaml`

**Checkpoint**: Local development environment fully functional with health monitoring.

---

## Phase 4: US2 — Automated Quality Verification

Implements User Story 2 (P2): Automated pipeline verifies code quality through linting, testing, static analysis, and coverage checks on every PR.

**Goal**: PRs trigger CI with Checkstyle, tests, JaCoCo coverage, CycloneDX BOM, SonarCloud. Merges to main publish container images.

**Independent Test**: Open a PR, verify all quality gates run and report results. Merge to main, verify images published to ghcr.io.

- [x] T038 [US2] Create GitHub Actions CI workflow triggered on PRs to main with Java 21 setup, Gradle caching, Checkstyle, test, JaCoCo coverage, CycloneDX BOM, and SonarCloud analysis in `.github/workflows/ci.yml`
- [x] T039 [US2] Add frontend build and test steps (npm install, npm test, npm run build) to the CI workflow in `.github/workflows/ci.yml`
- [x] T040 [US2] Configure CI workflow to upload JaCoCo coverage report and CycloneDX BOM as GitHub Actions artifacts in `.github/workflows/ci.yml`
- [x] T041 [US2] Update GitHub Actions publish workflow to build backend image via `./gradlew bootBuildImage --imageName=ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest` instead of `docker build -f Dockerfile.backend` in `.github/workflows/publish.yml`
- [x] T042 [US2] Add frontend Docker image build and push to `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend` in the publish workflow in `.github/workflows/publish.yml`
- [x] T043 [US2] Configure SonarCloud project properties and quality gate integration in the CI workflow in `.github/workflows/ci.yml`

**Checkpoint**: CI/CD pipeline fully operational with bootBuildImage for backend image publishing.

---

## Phase 5: US3 — Production Deployment

Implements User Story 3 (P3): Complete application stack deployed via Docker Compose with public URL access through Pinggy tunnel.

**Goal**: `docker compose -f docker-compose.prod.yml up -d` starts all services in production mode with OTel tracing and public access via Pinggy.

**Independent Test**: Deploy production stack, access via Pinggy URL, verify health and tracing endpoints.

- [x] T044 [US3] Update production Docker Compose to remove `JAVA_TOOL_OPTIONS: "-javaagent:..."` from backend environment and ensure OTel configuration uses Spring Boot Starter env vars (`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_METRICS_EXPORTER`, `OTEL_LOGS_EXPORTER`) in `docker-compose.prod.yml`
- [x] T045 [US3] Configure Pinggy tunnel service to forward public traffic to the frontend Nginx container on port 80 in `docker-compose.prod.yml`
- [x] T046 [US3] Create OpenTelemetry Collector configuration with OTLP receiver and logging exporter for distributed tracing in `config/otel/otel-collector-config.yaml`
- [x] T047 [US3] Configure frontend Nginx to reverse proxy `/api/*` requests to `backend:8080` for production routing in `frontend/nginx.conf`
- [x] T048 [US3] Configure Kafka advertised listeners for Docker internal network (`kafka:29092`) in the production Compose file in `docker-compose.prod.yml`

**Checkpoint**: Production stack deployable with native image backend and compile-time OTel instrumentation.

---

## Phase 6: Polish & Verification

Documentation, validation, and final cleanup.

- [x] T049 Create README.md with project overview, prerequisites, quickstart instructions, test commands, build commands, and production deployment steps at the repository root
- [x] T050 Update README.md to reflect bootBuildImage for backend container builds (not Dockerfile.backend) and Spring Boot 3.5.x + Java 21 versions
- [x] T051 Verify `.gitignore` covers all generated artifacts (build/, node_modules/, .gradle/, *.class, *.jar, .env)
- [x] T052 Verify all Gradle tasks execute cleanly: `./gradlew clean check jacocoTestReport cyclonedxBom`
- [x] T053 Verify backend native image builds via `./gradlew bootBuildImage --imageName=simonrowe-backend:local`
- [x] T054 Verify frontend Docker image builds: `docker build -f Dockerfile.frontend -t simonrowe-frontend:local .`
- [x] T055 Verify production stack starts and all health checks pass: `docker compose -f docker-compose.prod.yml up -d`
- [x] T056 Validate quickstart flow end-to-end per `specs/001-project-infrastructure/quickstart.md`

---

## Dependencies & Execution Order

```
Phase 1: Setup (all complete)
  All T001-T018 completed in initial implementation.

Phase 2: Foundational (rework required)
  T026 (version catalog update) - no dependencies
  T027 (GraalVM plugin) - depends on T026
  T028 (OTel Starter deps) - depends on T026, can parallel with T027
  T029 (remove Dockerfile.backend) - independent

Phase 3: US1 (mostly complete)
  T037 (verify health endpoint) - depends on Phase 2 complete + docker compose up

Phase 4: US2 (rework required)
  T041 (publish workflow update) - depends on T027, T029

Phase 5: US3 (rework required)
  T044 (prod compose update) - depends on T028, T029

Phase 6: Polish (all pending)
  T050 (README update) - depends on T026, T027, T029
  T052 (Gradle verify) - depends on T026, T027, T028
  T053 (bootBuildImage verify) - depends on T027
  T054 (frontend Docker verify) - independent
  T055 (prod stack verify) - depends on T044
  T056 (quickstart validation) - depends on all prior phases
```

---

## Parallel Opportunities

### Within Phase 2 (Rework)
- **Group A**: T026 (version catalog) → T027 + T028 in parallel (both depend on T026)
- **Group B**: T029 (remove Dockerfile.backend) — independent, parallel with Group A

### Between Phase 3 and Phase 4
- T037 (health verify) and T041 (publish workflow) can run in parallel once Phase 2 is complete.

### Within Phase 5
- T044 (prod compose update) is the only pending task; independent of other phases' pending work.

### Within Phase 6
- T050, T052, T053, T054 can all run in parallel after their dependencies are met.
- T055 depends on T044.
- T056 runs last (full end-to-end).

---

## Implementation Strategy

### Execution Sequence

1. **Version catalog and build plugins first** (T026 → T027 + T028 in parallel). This is the critical path — Spring Boot 3.5.x, GraalVM native plugin, and OTel Starter must be in the build before anything else.

2. **Remove Dockerfile.backend** (T029). Can run in parallel with step 1. The file is no longer needed per constitution.

3. **Update CI/CD pipeline** (T041). The publish workflow must use `bootBuildImage` instead of `docker build -f Dockerfile.backend`.

4. **Update production Docker Compose** (T044). Remove `-javaagent` from `JAVA_TOOL_OPTIONS` since OTel is now compile-time via Spring Boot Starter.

5. **Update documentation** (T050). Align README with the new build approach.

6. **Verify everything** (T037, T052-T056). Run health checks, Gradle tasks, image builds, production stack, and full quickstart validation.

### Key Risk Mitigations

- **Spring Boot 3.5.x availability**: Verify the exact latest 3.5.x version is published to Maven Central before updating the version catalog. Use `3.5.0` as minimum if later patch versions aren't available yet.
- **GraalVM native image build time**: `bootBuildImage` with native compilation takes ~3-5 minutes and requires ~8GB RAM. Ensure Docker Desktop has sufficient resources allocated.
- **OTel Spring Boot Starter + native image compatibility**: The starter must work with Spring AOT processing. Verify no reflection-related issues during native image compilation.
- **Kafka KRaft mode**: Already validated in initial implementation. No changes needed.

### Estimated Task Count by Phase

| Phase | Total | Done | Remaining |
|-------|-------|------|-----------|
| Phase 1: Setup | 18 | 18 | 0 |
| Phase 2: Foundational | 12 | 12 | 0 |
| Phase 3: US1 | 6 | 6 | 0 |
| Phase 4: US2 | 6 | 6 | 0 |
| Phase 5: US3 | 5 | 5 | 0 |
| Phase 6: Polish | 8 | 8 | 0 |
| **Total** | **55** | **55** | **0** |
