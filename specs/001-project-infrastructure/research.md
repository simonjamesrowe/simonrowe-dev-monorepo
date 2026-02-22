# Research: Project Infrastructure

**Feature**: 001-project-infrastructure
**Date**: 2026-02-21
**Purpose**: Document technology decisions, rationale, and alternatives for the infrastructure scaffolding of simonrowe.dev monorepo.

---

## 1. Spring Boot 3.5.x + Java 21 Setup

### Decision

Use Spring Boot 3.5.x with Java 21 (current LTS) as the backend runtime. Enable virtual threads by default via `spring.threads.virtual.enabled=true` in application configuration. Java 21 provides virtual threads (stable), pattern matching, records, and sealed classes. Spring Boot 3.5.x has mature support for GraalVM native images via `bootBuildImage` and the `org.graalvm.buildtools.native` plugin (see section 13).

### Rationale

- Spring Boot 3.5.x is the current stable release line with long-term support and active development.
- Java 21 is the current LTS release providing virtual threads (Project Loom), enabling high-throughput I/O-bound workloads (MongoDB queries, Elasticsearch calls, Kafka interactions) without reactive programming complexity.
- Virtual threads eliminate the need for WebFlux/reactive stack while achieving comparable scalability for I/O-bound work, aligning with the Simplicity principle.
- Pattern matching for `switch` and `instanceof`, record patterns improve code clarity.
- Spring Boot 3.5.x includes built-in support for GraalVM native images via `bootBuildImage` and the `org.graalvm.buildtools.native` plugin (see section 13).

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Spring Boot 4.x | Not yet released as stable. Spring Boot 3.5.x is the current stable line with full GraalVM native image support. |
| Spring WebFlux (reactive) | Virtual threads provide equivalent concurrency benefits with simpler imperative programming model. Constitution Principle V (Simplicity) favors this approach. |
| Quarkus / Micronaut | Spring Boot has the largest ecosystem, most extensive documentation, and best integration with Spring Data MongoDB, Spring Kafka, and Spring Data Elasticsearch. No concrete requirement justifies switching frameworks. |
| Kotlin (JVM) | Java is specified in the constitution. Kotlin DSL is used for Gradle builds only. |

---

## 2. Gradle Kotlin DSL Multi-Project Build

### Decision

Use Gradle (latest stable) with Kotlin DSL for the build system. Structure as a multi-project build with the root `build.gradle.kts` containing shared plugin configuration and the `backend/build.gradle.kts` containing backend-specific dependencies. Use the Gradle Version Catalog (`gradle/libs.versions.toml`) for centralized dependency version management.

The frontend is NOT a Gradle subproject -- it uses npm/Node.js tooling directly. Gradle only manages the backend build.

### Rationale

- Gradle Kotlin DSL provides type-safe build scripts with IDE autocompletion, reducing build configuration errors.
- Version Catalog (`libs.versions.toml`) centralizes all dependency versions in one file, simplifying version bumps and ensuring consistency.
- Multi-project build allows shared plugin configuration (Checkstyle, JaCoCo, CycloneDX) to be defined once in the root project and inherited by subprojects.
- Keeping the frontend outside Gradle avoids unnecessary complexity -- React projects have mature npm-based tooling (Vite, Jest/Vitest) that does not benefit from Gradle wrapping.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Gradle Groovy DSL | Kotlin DSL provides type safety and better IDE support. Constitution specifies Kotlin DSL preference. |
| Maven | Gradle is specified in the constitution. Gradle's incremental build and build cache provide faster builds. |
| Gradle node plugin for frontend | Adds unnecessary coupling. npm scripts are simpler and more idiomatic for React projects. |
| Gradle buildSrc for shared config | Version Catalog is the modern Gradle approach for dependency management. Convention plugins in `buildSrc` add complexity without clear benefit for a single backend subproject. |

---

## 3. Docker Compose for Local Development

### Decision

Use Docker Compose with two configuration files:
- `docker-compose.yml` -- Local development: starts infrastructure services only (MongoDB, Kafka, Zookeeper, Elasticsearch). The backend and frontend run on the host for hot-reload development.
- `docker-compose.prod.yml` -- Production: starts all services including backend, frontend, and Pinggy tunnel as containers.

Infrastructure service versions will use the latest stable Docker images.

### Rationale

- Docker Compose is specified in the constitution for both local and production orchestration.
- Separating infrastructure services from application services in local dev enables fast iteration with hot-reload (Spring DevTools for backend, Vite HMR for frontend).
- Named volumes preserve data across container restarts during development.
- Health checks on all services ensure dependent services wait for readiness before starting.
- A single `docker-compose up -d` command satisfies the 5-minute bootstrap requirement (SC-001).

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Kubernetes (minikube/kind) | Violates Principle V (Simplicity). Docker Compose meets all requirements without Kubernetes complexity. |
| Podman Compose | Docker Compose has broader ecosystem support and documentation. No concrete requirement for Podman. |
| Testcontainers for local dev | Testcontainers is designed for test lifecycle, not persistent development environments. Docker Compose is more appropriate for long-running dev services. |
| Single docker-compose.yml with profiles | Two files provide clearer separation of concerns. Production config includes secrets and Pinggy; dev config is minimal. Profiles would mix both concerns in one file. |

### Service Configuration

| Service | Image | Ports (Local Dev) | Purpose |
|---------|-------|-------------------|---------|
| MongoDB | `mongo:8` | 27017 | Primary persistence |
| Kafka | `confluentinc/cp-kafka:7.8.x` (KRaft) | 9092 | Async messaging |
| Elasticsearch | `elasticsearch:8.17.x` | 9200 | Full-text search |

Note: Kafka will use KRaft mode (no Zookeeper required) with Confluent Platform images that support KRaft-only deployment, simplifying the local stack.

---

## 4. GitHub Actions CI/CD Workflows

### Decision

Two workflow files:
1. **`ci.yml`** -- Triggered on pull requests to `main`. Runs: Checkstyle, compile, unit tests, integration tests (Testcontainers), JaCoCo coverage check, SonarQube analysis, CycloneDX BOM generation, and frontend build + test.
2. **`publish.yml`** -- Triggered on push to `main` (after merge). Runs full build, then builds and publishes Docker images to `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend` and `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend`.

Both workflows use GitHub-hosted Ubuntu runners. Gradle builds use the `gradle/actions/setup-gradle` action for caching. Node.js uses `actions/setup-node` with npm caching.

### Rationale

- Separate CI and publish workflows ensure quality gates run on PRs but container publishing only happens on main branch merges (FR-007).
- GitHub-hosted runners avoid infrastructure maintenance overhead.
- Gradle action provides build caching, significantly reducing build times.
- Docker layer caching in GitHub Actions reduces image build times for subsequent runs.
- The 10-minute pipeline target (SC-002) is achievable with parallel Gradle task execution and Docker layer caching.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Single workflow with conditionals | Two workflows are clearer about intent and easier to maintain. |
| Self-hosted runners | Adds infrastructure management burden. GitHub-hosted runners are sufficient for this project's scale. |
| Docker Hub registry | Constitution specifies ghcr.io. GitHub Container Registry integrates natively with GitHub Actions authentication. |
| ArgoCD / FluxCD for deployment | Violates Principle V (Simplicity). Docker Compose + Pinggy meets production deployment requirements. |

---

## 5. Spring Boot Actuator on Separate Management Port

### Decision

Configure Spring Boot Actuator to run on a separate management port (e.g., 8081) while the application serves traffic on port 8080. Expose the following endpoints on the management port:
- `/actuator/health` -- Service health with component details
- `/actuator/prometheus` -- Prometheus metrics endpoint
- `/actuator/info` -- Application info

Configuration in `application.yml`:
```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      show-details: when-authorized
      show-components: always
```

### Rationale

- Constitution Principle II mandates actuator endpoints on a separate port from application traffic.
- Separate port allows infrastructure (load balancers, monitoring systems) to access health/metrics without exposing them on the public application port.
- Prometheus endpoint provides metrics in the format Prometheus expects for scraping.
- Health endpoint with component details allows monitoring of downstream service connectivity (MongoDB, Kafka, Elasticsearch).

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Same port with path-based separation | Constitution requires a separate port. Path-based separation also risks accidental exposure of actuator endpoints to public traffic. |
| Spring Boot Admin | Adds unnecessary dependency for a single-service deployment. Prometheus + health endpoints are sufficient. |
| Custom health endpoint | Spring Boot Actuator provides battle-tested health indicators for MongoDB, Kafka, and Elasticsearch out of the box. |

---

## 6. OpenTelemetry Integration via Spring Boot Starter

### Decision

Use the OpenTelemetry Spring Boot Starter for compile-time instrumentation of the Spring Boot backend. The Java Agent CANNOT be used because the backend is compiled to a GraalVM native image, which does not support `-javaagent` bytecode instrumentation.

Dependencies in `backend/build.gradle.kts`:
```kotlin
implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
```

Traces are exported using the OTLP protocol to an OpenTelemetry Collector sidecar in the production Docker Compose stack.

Configuration via `application.yml`:
```yaml
otel:
  service:
    name: simonrowe-backend
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
  metrics:
    exporter: none
  logs:
    exporter: none
```

### Rationale

- GraalVM native images perform ahead-of-time (AOT) compilation; Java agents that rely on runtime bytecode manipulation are fundamentally incompatible.
- The OpenTelemetry Spring Boot Starter provides compile-time auto-configuration that works with Spring AOT and native images.
- The starter integrates with Spring's auto-configuration system and provides instrumentation for Spring MVC, Spring Data, Spring Kafka, and RestClient.
- OTLP is the standard export protocol, compatible with any OpenTelemetry-compatible backend (Jaeger, Zipkin, Grafana Tempo, etc.).
- Disabling OTel metrics and logs exporters avoids duplication with Prometheus (metrics) and structured logging (logs).

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| OpenTelemetry Java Agent | Incompatible with GraalVM native images. Requires runtime bytecode instrumentation that AOT compilation eliminates. |
| Micrometer Tracing with OTel bridge | Viable alternative but adds an extra abstraction layer. The OTel Spring Boot Starter provides more direct OpenTelemetry integration with better community support for native images. |
| Manual instrumentation | Too much boilerplate. The starter provides automatic instrumentation for all Spring-supported libraries. |
| Jaeger client directly | Jaeger recommends migrating to OpenTelemetry. OTel is the industry standard. |

---

## 7. Testcontainers for Integration Tests

### Decision

Use Testcontainers for Java to spin up real MongoDB, Kafka, and Elasticsearch instances during integration tests. Configure a shared test infrastructure using JUnit 5 `@Testcontainers` annotation and Spring Boot's `@ServiceConnection` for automatic connection configuration.

Use the `@SpringBootTest` annotation with Testcontainers for integration tests. Reuse containers across test classes where possible to reduce startup overhead.

### Rationale

- Constitution Principle III mandates Testcontainers for integration tests -- no mocked infrastructure.
- `@ServiceConnection` (Spring Boot 3.1+) automatically configures Spring datasource/connection properties from Testcontainers, eliminating manual property wiring.
- Container reuse (`testcontainers.reuse.enable=true`) reduces test suite execution time.
- Testing against real services catches integration issues that mocks cannot.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Embedded MongoDB / Embedded Kafka | Constitution explicitly prohibits mocked infrastructure for integration tests. Embedded alternatives often have behavioral differences from production. |
| Docker Compose for tests | Testcontainers provides better test lifecycle management, automatic cleanup, and parallel test execution support. |
| In-memory fakes | Same as mocked infrastructure -- prohibited by constitution. |
| Shared test environment | Introduces test coupling and flakiness. Testcontainers provides isolated, reproducible test infrastructure per test run. |

---

## 8. Google Java Style Enforcement via Checkstyle

### Decision

Use the Gradle Checkstyle plugin with the official Google Java Style configuration file (`google_checks.xml`). The Checkstyle task runs as part of the `check` lifecycle and fails the build on any violation.

Configuration in `backend/build.gradle.kts`:
```kotlin
plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.21.x"
    configFile = rootProject.file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
}
```

### Rationale

- Constitution Principle III mandates Google Java Style Guide conformance.
- Checkstyle is the standard tool for Java style enforcement with first-class Gradle integration.
- The official `google_checks.xml` is maintained by Google and matches the published style guide.
- `maxWarnings = 0` ensures no style drift -- all violations are treated as errors.
- Running as part of `check` means developers get feedback during local builds, not just in CI.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| google-java-format (formatter) | Formatters and linters serve different purposes. google-java-format auto-formats but does not catch all style violations. Checkstyle is the enforcement mechanism; google-java-format can be used as a complementary IDE formatter. |
| Spotless plugin | Spotless is a formatter, not a linter. It can auto-fix but does not fail builds on violations in the same way Checkstyle does. Consider adding alongside Checkstyle as a convenience. |
| PMD / Error Prone | These are static analysis tools, not style checkers. SonarQube covers static analysis. Checkstyle specifically addresses the Google Java Style requirement. |

---

## 9. JaCoCo Configuration

### Decision

Use the Gradle JaCoCo plugin to generate test coverage reports and enforce minimum coverage thresholds. Configure line and branch coverage minimums. Generate HTML and XML reports (XML for SonarQube consumption).

Configuration in `backend/build.gradle.kts`:
```kotlin
plugins {
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

### Rationale

- Constitution Principle III mandates JaCoCo with enforced coverage thresholds.
- 80% line coverage is a reasonable starting threshold for infrastructure scaffolding. This can be adjusted upward as the codebase matures.
- XML reports are required by SonarQube for coverage visualization.
- HTML reports provide developer-friendly local coverage browsing.
- Linking to `check` task ensures coverage verification runs as part of the standard build lifecycle.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Cobertura | JaCoCo is specified in the constitution and is the de facto standard for Java coverage. More actively maintained. |
| Coverage thresholds in SonarQube only | Local enforcement via JaCoCo provides faster feedback. SonarQube analysis happens in CI, but developers should see failures locally. |
| 90%+ threshold | Starting at 80% is pragmatic for a new project. Can be increased incrementally as the codebase grows. |

---

## 10. CycloneDX BOM Plugin

### Decision

Use the CycloneDX Gradle plugin to generate a Software Bill of Materials (SBOM) in CycloneDX format on every build. The BOM is generated as part of the `build` task and included in CI artifacts.

Configuration in `backend/build.gradle.kts`:
```kotlin
plugins {
    id("org.cyclonedx.bom") version "2.1.x"
}

tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSkipConfigs(listOf("testCompileClasspath", "testRuntimeClasspath"))
    destination = layout.buildDirectory.dir("reports/bom").get().asFile
}
```

### Rationale

- Constitution Principle III mandates CycloneDX BOM generation for dependency tracking.
- CycloneDX is an OWASP standard for SBOM, widely supported by security scanning tools.
- Generating only for runtime dependencies (not test dependencies) keeps the BOM focused on what ships in production.
- The BOM enables vulnerability scanning, license compliance checking, and supply chain security.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| SPDX format | CycloneDX is specified in the constitution. CycloneDX has stronger tooling for vulnerability correlation. |
| Gradle dependency-check plugin (OWASP) | Dependency-check performs vulnerability scanning, not BOM generation. These are complementary -- BOM generation is the requirement here. |
| Manual dependency tracking | Automated BOM generation is more accurate and does not drift from actual dependencies. |

---

## 11. SonarQube Integration

### Decision

Integrate SonarQube analysis via the `org.sonarqube` Gradle plugin. Analysis runs in the CI pipeline (`ci.yml`) on every pull request. SonarQube receives JaCoCo coverage reports and Checkstyle results for a unified quality view.

Configuration in root `build.gradle.kts`:
```kotlin
plugins {
    id("org.sonarqube") version "6.0.x"
}

sonarqube {
    properties {
        property("sonar.projectKey", "simonjamesrowe_simonrowe-dev-monorepo")
        property("sonar.organization", "simonjamesrowe")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths",
            "backend/build/reports/jacoco/test/jacocoTestReport.xml")
    }
}
```

SonarCloud (hosted SonarQube) is used to avoid self-hosting infrastructure.

### Rationale

- Constitution Principle III mandates SonarQube on every PR.
- SonarCloud is the hosted variant, eliminating the need to manage a SonarQube server (Principle V: Simplicity).
- SonarCloud is free for open-source projects on GitHub.
- Integration with JaCoCo provides coverage visualization alongside static analysis findings.
- Quality gates in SonarCloud can block PRs that introduce new issues.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Self-hosted SonarQube | Adds infrastructure management overhead. SonarCloud provides the same functionality as a managed service. |
| CodeClimate / Codacy | Constitution specifies SonarQube. SonarQube has the deepest Java analysis capabilities. |
| SpotBugs / Error Prone only | These are complementary tools, not replacements for SonarQube's comprehensive analysis, quality gate management, and historical tracking. |

---

## 12. Pinggy for Production Exposure

### Decision

Use Pinggy as a tunneling service to expose the production Docker Compose stack to the internet via a public URL. Pinggy runs as a container in `docker-compose.prod.yml` that tunnels traffic to the frontend service (which reverse-proxies API calls to the backend).

Pinggy provides a stable subdomain (with paid plan) or dynamic URL (free tier) for accessing the application.

Configuration in `docker-compose.prod.yml`:
```yaml
services:
  pinggy:
    image: pinggy/pinggy
    command: ["ssh", "-p", "443", "-R0:frontend:80", "-o", "StrictHostKeyChecking=no", "tcp@a.pinggy.io"]
    depends_on:
      frontend:
        condition: service_healthy
```

### Rationale

- Constitution specifies Pinggy for production public exposure.
- Pinggy avoids the need for a static IP, domain registration, or cloud provider -- the application runs on any machine with internet access.
- Container-based Pinggy integration means the tunnel lifecycle is managed by Docker Compose alongside other services.
- This is the simplest approach to expose a local Docker Compose stack to the internet (Principle V: Simplicity).

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Cloudflare Tunnel | Constitution specifies Pinggy. Cloudflare Tunnel requires a Cloudflare account and DNS configuration. |
| ngrok | Constitution specifies Pinggy. ngrok has similar functionality but is not the chosen tool. |
| Cloud VM (EC2/GCE) | Adds infrastructure provisioning complexity. Pinggy allows running on any machine. |
| VPS with Nginx | Requires server management, SSL certificate management, and DNS configuration. Pinggy eliminates all of this. |

---

## 13. GraalVM Native Image via bootBuildImage

### Decision

Compile the backend to a GraalVM native image using the `org.graalvm.buildtools.native` Gradle plugin. The container image is produced by `./gradlew bootBuildImage` using Cloud Native Buildpacks (Paketo). No `Dockerfile.backend` is used.

Configuration in `backend/build.gradle.kts`:
```kotlin
plugins {
    id("org.graalvm.buildtools.native")
}
```

Build the native image container:
```bash
./gradlew bootBuildImage \
  --imageName=ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest
```

### Rationale

- Constitution Principle I mandates that the backend container MUST be built via `bootBuildImage` (Cloud Native Buildpacks), not a Dockerfile.
- Constitution Principle II mandates GraalVM native image compilation via the `org.graalvm.buildtools.native` plugin.
- Native images provide sub-second startup time (~0.08s vs ~5-10s for JVM), significantly improving production deployment speed and container orchestration responsiveness.
- No JVM in the runtime image reduces container size and attack surface.
- Cloud Native Buildpacks (Paketo) handle all build complexity: GraalVM installation, AOT processing, native compilation, and minimal container creation.
- Spring Boot 3.5.x has mature native image support with AOT processing that generates reflection/proxy/resource configurations automatically.

### Implications

- **No `-javaagent`**: OpenTelemetry Java Agent cannot be used. The OpenTelemetry Spring Boot Starter provides compile-time instrumentation instead (see section 6).
- **No `Dockerfile.backend`**: The `bootBuildImage` task replaces the multi-stage Dockerfile. The Paketo builder produces a minimal OCI image.
- **AOT processing**: Spring AOT generates metadata at build time. `@Profile` and runtime bean definition changes have restrictions.
- **Build time**: Native image compilation is significantly slower than JVM compilation (~3-5 minutes vs ~30 seconds). This is acceptable for CI/CD but developers use `bootRun` (JVM mode) for local development.
- **Docker required**: `bootBuildImage` uses Docker to run the Buildpack builder. Docker must be running for the build.
- **Memory**: The Buildpack builder requires ~8GB RAM during native compilation. CI runners and Docker Desktop must be configured accordingly.

### Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Multi-stage Dockerfile with JDK/JRE | Constitution mandates `bootBuildImage`. Dockerfiles are more error-prone and require manual base image management. |
| JVM-mode `bootBuildImage` (no native) | Constitution mandates GraalVM native image. JVM mode would still work with `bootBuildImage` but misses the startup/size benefits. |
| Buildpacks without GraalVM | Constitution mandates native image compilation. Standard Buildpacks produce JVM-based images. |
| GraalVM native-image CLI directly | `bootBuildImage` with Paketo abstracts all GraalVM toolchain management. Direct CLI use requires manual installation and configuration. |

---

## Summary of Key Decisions

| # | Decision | Key Technology | Constitution Alignment |
|---|----------|---------------|----------------------|
| 1 | Spring Boot 3.5.x + Java 21 with virtual threads | Spring Boot 3.5.x, Java 21 | Principle II |
| 2 | Gradle Kotlin DSL multi-project with Version Catalog | Gradle, Kotlin DSL | Principle II |
| 3 | Docker Compose with separate dev/prod configs | Docker Compose | Principle I |
| 4 | Dual GitHub Actions workflows (CI + Publish) | GitHub Actions, ghcr.io | Principle I, III |
| 5 | Actuator on separate management port | Spring Boot Actuator | Principle II, IV |
| 6 | OpenTelemetry Spring Boot Starter for tracing | OpenTelemetry Starter, OTLP | Principle IV |
| 7 | Testcontainers for integration tests | Testcontainers, JUnit 5 | Principle III |
| 8 | Checkstyle with Google Java Style config | Checkstyle | Principle III |
| 9 | JaCoCo with 80% minimum coverage | JaCoCo | Principle III |
| 10 | CycloneDX Gradle plugin for SBOM | CycloneDX | Principle III |
| 11 | SonarCloud for hosted static analysis | SonarQube/SonarCloud | Principle III |
| 12 | Pinggy container for production tunneling | Pinggy | Principle I |
| 13 | GraalVM native image via bootBuildImage | GraalVM, Buildpacks, Paketo | Principle I, II |
