# Tasks: Project Infrastructure

**Feature**: 001-project-infrastructure
**Date**: 2026-02-21
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

---

## Phase 1: Setup

Project skeleton: root Gradle build, backend Spring Boot project, frontend React project, and repository configuration files.

- [ ] T001 Create root Gradle build with Kotlin DSL plugin management and shared configuration in `build.gradle.kts`
- [ ] T002 Create Gradle settings with multi-project include for backend in `settings.gradle.kts`
- [ ] T003 Create Gradle version catalog with Spring Boot 4, Testcontainers, and tooling versions in `gradle/libs.versions.toml`
- [ ] T004 Create Gradle properties with JVM args and project metadata in `gradle.properties`
- [ ] T005 Initialize Gradle wrapper (jar + properties) in `gradle/wrapper/gradle-wrapper.jar` and `gradle/wrapper/gradle-wrapper.properties`
- [ ] T006 Create the `gradlew` and `gradlew.bat` wrapper scripts at the repository root
- [ ] T007 Create backend build configuration with Spring Boot 4, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, and Actuator dependencies in `backend/build.gradle.kts`
- [ ] T008 Create Spring Boot application entry point with `@SpringBootApplication` annotation in `backend/src/main/java/com/simonrowe/Application.java`
- [ ] T009 Create base application configuration with server port, management port, MongoDB URI, Kafka bootstrap servers, Elasticsearch URIs, and virtual threads enabled in `backend/src/main/resources/application.yml`
- [ ] T010 Create structured JSON logging configuration with Logback in `backend/src/main/resources/logback-spring.xml`
- [ ] T011 Create frontend project with React, TypeScript, Vite dependencies and dev/build/test scripts in `frontend/package.json`
- [ ] T012 Create TypeScript configuration for the frontend project in `frontend/tsconfig.json`
- [ ] T013 Create Vite build configuration with API proxy to backend on port 8080 in `frontend/vite.config.ts`
- [ ] T014 Create root React application component in `frontend/src/App.tsx`
- [ ] T015 Create React application entry point in `frontend/src/main.tsx`
- [ ] T016 Create HTML template for the frontend application in `frontend/public/index.html`
- [ ] T017 Create `.gitignore` with rules for Gradle, Node.js, IDE files, Docker volumes, and build artifacts at the repository root
- [ ] T018 Create `.editorconfig` with indentation, charset, and line ending rules at the repository root

---

## Phase 2: Foundational

Docker Compose for local development, Dockerfiles, quality gate tooling (Checkstyle, JaCoCo, CycloneDX), and Testcontainers test infrastructure.

- [ ] T019 Create Docker Compose for local development with MongoDB 8, Kafka 7.8 (KRaft mode), and Elasticsearch 8.17 including health checks, named volumes, and port mappings in `docker-compose.yml`
- [ ] T020 Create multi-stage backend Dockerfile with Java 25 JDK for build and JRE for runtime, including OpenTelemetry agent download in `Dockerfile.backend`
- [ ] T021 Create multi-stage frontend Dockerfile with Node.js for build and Nginx for serving, including reverse proxy configuration for `/api` routes in `Dockerfile.frontend`
- [ ] T022 Download and add Google Java Style Checkstyle configuration in `config/checkstyle/google_checks.xml`
- [ ] T023 Add Checkstyle plugin configuration with `maxWarnings = 0` and reference to `google_checks.xml` in `backend/build.gradle.kts`
- [ ] T024 Add JaCoCo plugin configuration with XML/HTML reports and 80% minimum line coverage enforcement linked to the `check` task in `backend/build.gradle.kts`
- [ ] T025 Add CycloneDX BOM plugin configuration scoped to `runtimeClasspath` with output to `build/reports/bom` in `backend/build.gradle.kts`
- [ ] T026 Add SonarQube plugin configuration with project key, organization, host URL, and JaCoCo report path in `build.gradle.kts`
- [ ] T027 Create Testcontainers base test configuration with `@SpringBootTest`, `@Testcontainers`, and `@ServiceConnection` for MongoDB, Kafka, and Elasticsearch in `backend/src/test/java/com/simonrowe/ApplicationTests.java`
- [ ] T028 Create test configuration overrides for Testcontainers in `backend/src/test/resources/application-test.yml`

---

## Phase 3: US1 - Local Development Bootstrap

Implements User Story 1 (P1): A developer can clone the repo and have a complete working development environment with all services running locally.

- [ ] T029 [US1] Configure Spring Boot Actuator on management port 8081 with health, prometheus, and info endpoints exposed, and health detail visibility in `backend/src/main/resources/application.yml`
- [ ] T030 [US1] Configure MongoDB health indicator auto-detection via Spring Data MongoDB dependency in `backend/build.gradle.kts`
- [ ] T031 [US1] Configure Kafka health indicator auto-detection via Spring Kafka dependency in `backend/build.gradle.kts`
- [ ] T032 [US1] Configure Elasticsearch health indicator auto-detection via Spring Data Elasticsearch dependency in `backend/build.gradle.kts`
- [ ] T033 [US1] Verify health endpoint returns composite status with mongo, kafka, and elasticsearch components per contract in `specs/001-project-infrastructure/contracts/health-api.yaml`
- [ ] T034 [US1] Configure Spring Boot DevTools for automatic restart on code changes in `backend/build.gradle.kts` and `backend/src/main/resources/application.yml`
- [ ] T035 [US1] Configure Vite dev server with HMR and API proxy to `http://localhost:8080` for frontend hot-reload development in `frontend/vite.config.ts`
- [ ] T036 [P] [US1] Create a basic frontend smoke test to verify App component renders in `frontend/tests/App.test.tsx`

---

## Phase 4: US2 - Automated Quality Verification

Implements User Story 2 (P2): Automated pipeline verifies code quality through linting, testing, static analysis, and coverage checks on every PR.

- [ ] T037 [US2] Create GitHub Actions CI workflow triggered on PRs to main with Java 25 setup, Gradle caching, Checkstyle, test, JaCoCo coverage, CycloneDX BOM, and SonarCloud analysis in `.github/workflows/ci.yml`
- [ ] T038 [US2] Add frontend build and test steps (npm install, npm test, npm run build) to the CI workflow in `.github/workflows/ci.yml`
- [ ] T039 [US2] Configure CI workflow to upload JaCoCo coverage report and CycloneDX BOM as GitHub Actions artifacts in `.github/workflows/ci.yml`
- [ ] T040 [US2] Create GitHub Actions publish workflow triggered on push to main that builds and pushes backend image to `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend` in `.github/workflows/publish.yml`
- [ ] T041 [US2] Add frontend Docker image build and push to `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend` in the publish workflow in `.github/workflows/publish.yml`
- [ ] T042 [US2] Configure SonarCloud project properties and quality gate integration in the CI workflow, consuming JaCoCo XML reports for coverage in `.github/workflows/ci.yml`

---

## Phase 5: US3 - Production Deployment

Implements User Story 3 (P3): Complete application stack deployed via Docker Compose with public URL access through Pinggy tunnel.

- [ ] T043 [US3] Create production Docker Compose with backend, frontend, MongoDB, Kafka, Elasticsearch, OTel Collector, and Pinggy services including health checks and dependency ordering in `docker-compose.prod.yml`
- [ ] T044 [US3] Configure Pinggy tunnel service to forward public traffic to the frontend Nginx container on port 80 in `docker-compose.prod.yml`
- [ ] T045 [US3] Configure backend service environment variables for production including MongoDB URI, Kafka bootstrap servers, Elasticsearch URIs, and OTel agent in `docker-compose.prod.yml`
- [ ] T046 [US3] Create OpenTelemetry Collector configuration with OTLP receiver and logging exporter for distributed tracing in `config/otel/otel-collector-config.yaml`
- [ ] T047 [US3] Configure frontend Nginx to reverse proxy `/api/*` requests to `backend:8080` for production routing in `frontend/nginx.conf` (copied into the Docker image via `Dockerfile.frontend`)
- [ ] T048 [US3] Configure Kafka advertised listeners for Docker internal network (`kafka:29092`) in the production Compose file in `docker-compose.prod.yml`
- [ ] T049 [US3] Document all production environment variables, their defaults, and required overrides in `specs/001-project-infrastructure/data-model.md` (already captured; verify completeness)

---

## Phase 6: Polish

Documentation, validation, and final cleanup.

- [ ] T050 Create README.md with project overview, prerequisites, quickstart instructions, test commands, build commands, and production deployment steps at the repository root `README.md`
- [ ] T051 Validate quickstart flow end-to-end: clone, docker compose up, gradlew bootRun, npm run dev, verify health endpoint per `specs/001-project-infrastructure/quickstart.md`
- [ ] T052 Verify `.gitignore` covers all generated artifacts (build/, node_modules/, .gradle/, *.class, *.jar, .env) at the repository root `.gitignore`
- [ ] T053 Verify all Gradle tasks execute cleanly: `./gradlew clean check jacocoTestReport cyclonedxBom` in `backend/build.gradle.kts`
- [ ] T054 Verify Docker image builds succeed: `docker build -f Dockerfile.backend` and `docker build -f Dockerfile.frontend` for `Dockerfile.backend` and `Dockerfile.frontend`
- [ ] T055 Verify production stack starts and all health checks pass: `docker compose -f docker-compose.prod.yml up -d` for `docker-compose.prod.yml`

---

## Dependencies & Execution Order

```
Phase 1: Setup (sequential - each builds on prior)
  T001 -> T002 -> T003 -> T004 -> T005 -> T006  (Gradle foundation)
  T007 (depends on T001, T002, T003)             (backend build)
  T008, T009, T010 (depend on T007)              (backend source)
  T011 -> T012 -> T013 -> T014 -> T015 -> T016  (frontend foundation)
  T017, T018 (independent of each other)          (repo config)

Phase 2: Foundational (depends on Phase 1 complete)
  T019 (independent - Docker Compose)
  T020 (depends on T007, T008)                    (backend Dockerfile)
  T021 (depends on T011, T014)                    (frontend Dockerfile)
  T022 (independent - config file download)
  T023 (depends on T007, T022)                    (Checkstyle in build)
  T024 (depends on T007)                          (JaCoCo in build)
  T025 (depends on T007)                          (CycloneDX in build)
  T026 (depends on T001)                          (SonarQube in root build)
  T027 (depends on T007, T008, T009)              (test config)
  T028 (depends on T027)                          (test resources)

Phase 3: US1 (depends on Phase 2 complete)
  T029 (depends on T009)                          (actuator config)
  T030, T031, T032 (depend on T007)               (health indicators)
  T033 (depends on T029, T030, T031, T032, T019)  (verify health)
  T034 (depends on T007, T009)                    (DevTools)
  T035 (depends on T013)                          (Vite proxy)
  T036 (depends on T014)                          (frontend test)

Phase 4: US2 (depends on Phase 2 complete; can start in parallel with Phase 3)
  T037 (depends on T007, T023, T024, T025, T027)  (CI workflow)
  T038 (depends on T037, T011)                    (frontend CI)
  T039 (depends on T037)                          (artifact uploads)
  T040 (depends on T020)                          (publish workflow)
  T041 (depends on T040, T021)                    (frontend publish)
  T042 (depends on T037, T026)                    (SonarCloud CI)

Phase 5: US3 (depends on Phases 2 and 3 complete)
  T043 (depends on T019, T020, T021)              (prod compose)
  T044 (depends on T043)                          (Pinggy)
  T045 (depends on T043)                          (backend env)
  T046 (independent - config file)                (OTel config)
  T047 (depends on T021)                          (Nginx proxy)
  T048 (depends on T043)                          (Kafka prod config)
  T049 (depends on T043, T045)                    (env var docs)

Phase 6: Polish (depends on all prior phases)
  T050 (depends on all phases)                    (README)
  T051 (depends on all phases)                    (quickstart validation)
  T052 (depends on T017)                          (gitignore verify)
  T053 (depends on T023, T024, T025, T027)        (Gradle verify)
  T054 (depends on T020, T021)                    (Docker verify)
  T055 (depends on T043)                          (prod stack verify)
```

---

## Parallel Opportunities

Tasks marked with `[P]` can run in parallel with other tasks in the same phase. Beyond those explicitly marked, the following groups can be parallelized:

### Within Phase 1
- **Group A** (Gradle): T001 through T010 (sequential chain)
- **Group B** (Frontend): T011 through T016 (sequential chain, parallel with Group A)
- **Group C** (Config): T017 + T018 (parallel with everything, no dependencies)

### Within Phase 2
- **Group A**: T019 (Docker Compose) -- independent
- **Group B**: T022 (Checkstyle config) -- independent
- **Group C**: T023 + T024 + T025 (build plugins) -- all depend on T007, parallel with each other
- **Group D**: T026 (SonarQube root) -- depends only on T001
- **Group E**: T020, T021 (Dockerfiles) -- depend on their respective Phase 1 outputs, parallel with each other

### Between Phase 3 and Phase 4
- Phase 3 (US1) and Phase 4 (US2) can execute **in parallel** since they depend on Phase 2 but not on each other. US1 focuses on runtime configuration while US2 focuses on CI/CD workflows.

### Within Phase 4
- T037 is the CI workflow foundation; T038, T039, T042 extend it (sequential after T037)
- T040, T041 (publish workflow) can be built in parallel with T037-T039 (CI workflow)

### Within Phase 5
- T046 (OTel config) is independent and can be created at any time
- T044, T045, T048 all extend T043 and can be done in parallel after T043

### Within Phase 6
- T052, T053, T054 are independent verification tasks and can run in parallel
- T050 (README) and T051 (quickstart validation) should run last

---

## Implementation Strategy

### Recommended Execution Sequence

1. **Start with the Gradle foundation** (T001-T007). The root build, settings, version catalog, and wrapper must exist before any backend code. This is the critical path.

2. **Frontend skeleton in parallel** (T011-T016). The frontend has no dependency on the backend build and can be scaffolded simultaneously by a second worker.

3. **Repo config files immediately** (T017-T018). These are standalone and should be done early to establish consistent formatting.

4. **Docker Compose before Dockerfiles** (T019 first, then T020-T021). Local development needs infrastructure services before application containers. The dev Compose file is used for daily development; Dockerfiles are needed for CI and production.

5. **Quality plugins as a batch** (T022-T026). Checkstyle config, JaCoCo, CycloneDX, and SonarQube can be added to the build files in a single pass to minimize repeated edits to `backend/build.gradle.kts`.

6. **Testcontainers setup** (T027-T028). Create the base integration test before writing feature-specific tests. This validates the Testcontainers + Spring Boot wiring.

7. **US1 and US2 in parallel tracks**:
   - **Track A (US1)**: Actuator config, health indicators, DevTools, Vite proxy. These are application runtime concerns.
   - **Track B (US2)**: CI workflow, publish workflow, SonarCloud. These are pipeline concerns with no runtime dependency on US1.

8. **US3 after US1 and US2** (T043-T049). Production deployment needs working Dockerfiles (validated by US2 publish) and correct application configuration (validated by US1 health checks).

9. **Polish last** (T050-T055). README, validation, and verification only make sense after all functional work is complete.

### Key Risk Mitigations

- **Testcontainers + Spring Boot 4 compatibility**: Verify Testcontainers supports Spring Boot 4 `@ServiceConnection` in T027 before building out test infrastructure.
- **Kafka KRaft mode**: Validate the Confluent Platform 7.8 KRaft configuration in T019 early, as KRaft listener config is error-prone.
- **Java 25 availability**: Confirm Java 25 is available in the GitHub Actions runner (T037) and the Docker base image (T020). Fall back to Java 24 if 25 is not GA.
- **Elasticsearch memory**: T019 sets `ES_JAVA_OPTS` to limit heap. Verify this works within Docker Desktop default memory limits.
- **Pinggy tunnel stability**: T044 relies on external service availability. Document fallback (direct port exposure) in T050.

### Estimated Task Count by Phase

| Phase | Tasks | Estimated Effort |
|-------|-------|-----------------|
| Phase 1: Setup | 18 tasks (T001-T018) | Foundation -- must complete first |
| Phase 2: Foundational | 10 tasks (T019-T028) | Quality gates and infra -- high value |
| Phase 3: US1 | 8 tasks (T029-T036) | Local dev -- critical path for developers |
| Phase 4: US2 | 6 tasks (T037-T042) | CI/CD -- parallelizable with US1 |
| Phase 5: US3 | 7 tasks (T043-T049) | Production -- depends on US1+US2 |
| Phase 6: Polish | 6 tasks (T050-T055) | Verification and documentation |
| **Total** | **55 tasks** | |
