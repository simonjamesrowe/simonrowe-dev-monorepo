# Quickstart Guide: Project Infrastructure

**Feature**: 001-project-infrastructure
**Date**: 2026-02-21
**Purpose**: Get a developer from zero to a running local development environment.

---

## Prerequisites

Install the following before starting:

| Tool | Version | Verification Command |
|------|---------|---------------------|
| JDK | 25 | `java -version` |
| Node.js | LTS (22.x) | `node --version` |
| npm | Bundled with Node.js | `npm --version` |
| Docker | Latest stable | `docker --version` |
| Docker Compose | v2 (bundled with Docker Desktop) | `docker compose version` |
| Git | Latest stable | `git --version` |

### Recommended

- **IDE**: IntelliJ IDEA (Community or Ultimate) with Google Java Style formatter configured
- **Docker Desktop**: Allocate at least 4GB RAM for Elasticsearch + Kafka + MongoDB

---

## 1. Clone the Repository

```bash
git clone https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git
cd simonrowe-dev-monorepo
```

---

## 2. Start Infrastructure Services

Start MongoDB, Kafka, and Elasticsearch using Docker Compose:

```bash
docker compose up -d
```

Wait for all services to become healthy:

```bash
docker compose ps
```

Expected output -- all services show `healthy` status:

```
NAME              STATUS
mongodb           Up (healthy)
kafka             Up (healthy)
elasticsearch     Up (healthy)
```

If a service shows `starting`, wait a moment and check again. Elasticsearch may take 30-60 seconds to become healthy.

---

## 3. Start the Backend

In a new terminal, start the Spring Boot backend:

```bash
./gradlew :backend:bootRun
```

The backend starts on two ports:
- **Application**: http://localhost:8080
- **Management**: http://localhost:8081

Verify the backend is running:

```bash
curl http://localhost:8081/actuator/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "mongo": { "status": "UP" },
    "kafka": { "status": "UP" },
    "elasticsearch": { "status": "UP" }
  }
}
```

---

## 4. Start the Frontend

In another terminal, install dependencies and start the Vite dev server:

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on http://localhost:5173.

Open your browser and navigate to http://localhost:5173 to see the application.

---

## 5. Verify Everything Works

| Check | URL | Expected |
|-------|-----|----------|
| Frontend loads | http://localhost:5173 | React app renders |
| Backend health | http://localhost:8081/actuator/health | `{"status":"UP"}` |
| Prometheus metrics | http://localhost:8081/actuator/prometheus | Prometheus text format metrics |
| MongoDB accessible | `docker compose exec mongodb mongosh --eval "db.stats()"` | Database statistics |
| Elasticsearch accessible | http://localhost:9200 | Cluster info JSON |

---

## 6. Run Tests

### Backend Tests

Run all backend tests (unit + integration with Testcontainers):

```bash
./gradlew :backend:test
```

Note: Integration tests use Testcontainers to spin up their own MongoDB, Kafka, and Elasticsearch instances. The Docker Compose services do NOT need to be running for tests.

### View Coverage Report

Generate and view the JaCoCo coverage report:

```bash
./gradlew :backend:jacocoTestReport
```

Open `backend/build/reports/jacoco/test/html/index.html` in your browser.

### Run All Quality Checks

Run the full quality gate (Checkstyle + tests + coverage verification):

```bash
./gradlew check
```

### Frontend Tests

```bash
cd frontend
npm test
```

---

## 7. Build Container Images

Build the backend and frontend Docker images locally:

```bash
docker build -f Dockerfile.backend -t simonrowe-backend:local .
docker build -f Dockerfile.frontend -t simonrowe-frontend:local .
```

---

## 8. Run Production Stack Locally

To test the full production stack (all services in containers):

```bash
docker compose -f docker-compose.prod.yml up -d
```

This starts all services including the backend, frontend, and infrastructure. The application is accessible via the Pinggy tunnel URL printed in the logs:

```bash
docker compose -f docker-compose.prod.yml logs pinggy
```

To shut down:

```bash
docker compose -f docker-compose.prod.yml down
```

---

## Common Tasks

### Reset Infrastructure Data

Remove all Docker volumes to start with a clean database:

```bash
docker compose down -v
docker compose up -d
```

### View Backend Logs

```bash
./gradlew :backend:bootRun
# Logs appear in the terminal with structured JSON format
```

### Generate SBOM

Generate the CycloneDX Software Bill of Materials:

```bash
./gradlew :backend:cyclonedxBom
```

Output: `backend/build/reports/bom/bom.json`

### Run SonarQube Analysis Locally

```bash
./gradlew sonar -Dsonar.token=<your-sonarcloud-token>
```

---

## Troubleshooting

### Elasticsearch fails to start

Elasticsearch requires `vm.max_map_count=262144`. On Linux:

```bash
sudo sysctl -w vm.max_map_count=262144
```

On Docker Desktop (macOS/Windows), this is handled automatically.

### Kafka fails health check

Kafka in KRaft mode may take up to 30 seconds to initialize. Wait and retry:

```bash
docker compose ps
# If kafka shows (health: starting), wait 30 seconds
docker compose ps
```

### Port conflicts

If ports 8080, 8081, 9092, 9200, or 27017 are in use, stop the conflicting service or modify the port mappings in `docker-compose.yml`.

### Testcontainers tests fail

Ensure Docker is running and accessible:

```bash
docker info
```

Testcontainers requires Docker socket access. On macOS with Docker Desktop, this should work out of the box.

### Gradle wrapper not found

If `./gradlew` is not executable:

```bash
chmod +x gradlew
```
