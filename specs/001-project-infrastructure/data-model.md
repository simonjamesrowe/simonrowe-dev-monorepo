# Data Model: Project Infrastructure

**Feature**: 001-project-infrastructure
**Date**: 2026-02-21
**Purpose**: Document infrastructure configuration model, service definitions, and operational interfaces.

---

## Overview

This spec is infrastructure-focused. There are no application-level entities (those belong to feature specs such as blog posts, projects, skills, etc.). This document defines the infrastructure configuration model: environment variables, service ports, health check endpoints, and Docker service definitions.

---

## 1. Environment Variable Configuration Model

All services are configured exclusively via environment variables (FR-014). No file-based configuration management beyond the initial `application.yml` defaults.

### Backend Service Environment Variables

| Variable | Default (Dev) | Production | Description |
|----------|--------------|------------|-------------|
| `SERVER_PORT` | `8080` | `8080` | Application HTTP port |
| `MANAGEMENT_SERVER_PORT` | `8081` | `8081` | Actuator/management HTTP port |
| `SPRING_DATA_MONGODB_URI` | `mongodb://localhost:27017/simonrowe` | `mongodb://mongodb:27017/simonrowe` | MongoDB connection string |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:9092` | Kafka broker address |
| `SPRING_ELASTICSEARCH_URIS` | `http://localhost:9200` | `http://elasticsearch:9200` | Elasticsearch cluster URI |
| `SPRING_THREADS_VIRTUAL_ENABLED` | `true` | `true` | Enable virtual threads |
| `OTEL_SERVICE_NAME` | `simonrowe-backend` | `simonrowe-backend` | OpenTelemetry service identifier |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | *(not set)* | `http://otel-collector:4317` | OTLP exporter endpoint |
| `OTEL_METRICS_EXPORTER` | `none` | `none` | Disabled (Prometheus handles metrics) |
| `OTEL_LOGS_EXPORTER` | `none` | `none` | Disabled (structured logging handles logs) |
| `JAVA_TOOL_OPTIONS` | *(not set)* | `-javaagent:/app/opentelemetry-javaagent.jar` | OTel agent attachment |

### Frontend Service Environment Variables

| Variable | Default (Dev) | Production | Description |
|----------|--------------|------------|-------------|
| `VITE_API_BASE_URL` | `http://localhost:8080` | `/api` | Backend API base URL |
| `NODE_ENV` | `development` | `production` | Node.js environment mode |

### Infrastructure Service Environment Variables

#### MongoDB

| Variable | Value | Description |
|----------|-------|-------------|
| `MONGO_INITDB_DATABASE` | `simonrowe` | Initial database name |

#### Kafka (KRaft Mode)

| Variable | Value | Description |
|----------|-------|-------------|
| `KAFKA_NODE_ID` | `1` | Kafka broker node ID |
| `KAFKA_PROCESS_ROLES` | `broker,controller` | KRaft combined mode |
| `KAFKA_LISTENERS` | `PLAINTEXT://:9092,CONTROLLER://:9093` | Internal listener config |
| `KAFKA_ADVERTISED_LISTENERS` | `PLAINTEXT://kafka:9092` (prod) / `PLAINTEXT://localhost:9092` (dev) | Advertised listener addresses |
| `KAFKA_CONTROLLER_LISTENER_NAMES` | `CONTROLLER` | Controller listener name |
| `KAFKA_CONTROLLER_QUORUM_VOTERS` | `1@kafka:9093` | KRaft quorum configuration |
| `CLUSTER_ID` | *(generated)* | KRaft cluster identifier |

#### Elasticsearch

| Variable | Value | Description |
|----------|-------|-------------|
| `discovery.type` | `single-node` | Single-node cluster mode |
| `xpack.security.enabled` | `false` | Disable security for dev/simple prod |
| `ES_JAVA_OPTS` | `-Xms512m -Xmx512m` | JVM heap size |

---

## 2. Service Port Mapping

### Local Development

| Service | Container Port | Host Port | Purpose |
|---------|---------------|-----------|---------|
| Backend (app) | 8080 | 8080 | Application API (runs on host) |
| Backend (mgmt) | 8081 | 8081 | Actuator health/metrics (runs on host) |
| Frontend (Vite) | 5173 | 5173 | Vite dev server (runs on host) |
| MongoDB | 27017 | 27017 | Document store |
| Kafka | 9092 | 9092 | Message broker |
| Elasticsearch | 9200 | 9200 | Search engine |

Note: In local development, the backend and frontend run on the host machine (not in containers) for hot-reload support. Only infrastructure services run in Docker.

### Production

| Service | Container Port | Exposed Port | Purpose |
|---------|---------------|-------------|---------|
| Backend (app) | 8080 | *(internal only)* | Application API |
| Backend (mgmt) | 8081 | 8081 | Actuator health/metrics |
| Frontend (nginx) | 80 | *(internal only)* | Web UI serving + API proxy |
| MongoDB | 27017 | *(internal only)* | Document store |
| Kafka | 9092 | *(internal only)* | Message broker |
| Elasticsearch | 9200 | *(internal only)* | Search engine |
| Pinggy | *(tunnel)* | 443 (public) | Public internet access |
| OTel Collector | 4317 | *(internal only)* | Trace collection |

In production, only the management port (8081) and the Pinggy tunnel are exposed. All other services communicate via the Docker network.

---

## 3. Health Check Endpoints

### Backend Health Check Model

The `/actuator/health` endpoint on the management port returns a composite health status:

```json
{
  "status": "UP",
  "components": {
    "mongo": {
      "status": "UP",
      "details": {
        "maxWireVersion": 21
      }
    },
    "kafka": {
      "status": "UP",
      "details": {
        "clusterId": "...",
        "brokerId": "1"
      }
    },
    "elasticsearch": {
      "status": "UP",
      "details": {
        "cluster_name": "docker-cluster",
        "status": "green"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 0,
        "free": 0,
        "threshold": 10485760,
        "path": "/app/.",
        "exists": true
      }
    }
  }
}
```

**Health Status Values**:
- `UP` -- Service is healthy and all downstream dependencies are reachable
- `DOWN` -- Service or a critical dependency is unavailable
- `OUT_OF_SERVICE` -- Service is intentionally taken out of rotation

### Docker Compose Health Checks

| Service | Health Check Command | Interval | Timeout | Retries |
|---------|---------------------|----------|---------|---------|
| Backend | `curl -f http://localhost:8081/actuator/health` | 10s | 5s | 5 |
| Frontend | `curl -f http://localhost:80/` | 10s | 5s | 3 |
| MongoDB | `mongosh --eval "db.adminCommand('ping')"` | 10s | 5s | 5 |
| Kafka | `kafka-broker-api-versions --bootstrap-server localhost:9092` | 15s | 10s | 5 |
| Elasticsearch | `curl -f http://localhost:9200/_cluster/health` | 10s | 5s | 5 |

---

## 4. Docker Service Definitions

### docker-compose.yml (Local Development)

```yaml
services:
  mongodb:
    image: mongo:8
    ports:
      - "27017:27017"
    volumes:
      - mongodb-data:/data/db
    environment:
      MONGO_INITDB_DATABASE: simonrowe
    healthcheck:
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: confluentinc/cp-kafka:7.8.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:29092,CONTROLLER://:9093,PLAINTEXT_HOST://:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
      interval: 15s
      timeout: 10s
      retries: 5

  elasticsearch:
    image: elasticsearch:8.17.0
    ports:
      - "9200:9200"
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: "-Xms512m -Xmx512m"
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9200/_cluster/health"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  mongodb-data:
  kafka-data:
  elasticsearch-data:
```

### docker-compose.prod.yml (Production)

Extends the base services and adds application containers:

```yaml
services:
  mongodb:
    # Same as dev

  kafka:
    # Same as dev, but KAFKA_ADVERTISED_LISTENERS uses kafka:9092

  elasticsearch:
    # Same as dev

  backend:
    image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://mongodb:27017/simonrowe
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SPRING_ELASTICSEARCH_URIS: http://elasticsearch:9200
      MANAGEMENT_SERVER_PORT: "8081"
      SPRING_THREADS_VIRTUAL_ENABLED: "true"
      JAVA_TOOL_OPTIONS: "-javaagent:/app/opentelemetry-javaagent.jar"
      OTEL_SERVICE_NAME: simonrowe-backend
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
      OTEL_METRICS_EXPORTER: none
      OTEL_LOGS_EXPORTER: none
    ports:
      - "8081:8081"
    depends_on:
      mongodb:
        condition: service_healthy
      kafka:
        condition: service_healthy
      elasticsearch:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  frontend:
    image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend:latest
    depends_on:
      backend:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:80/"]
      interval: 10s
      timeout: 5s
      retries: 3

  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    volumes:
      - ./config/otel/otel-collector-config.yaml:/etc/otelcol-contrib/config.yaml
    ports:
      - "4317:4317"

  pinggy:
    image: pinggy/pinggy
    command: >
      ssh -p 443 -R0:frontend:80
      -o StrictHostKeyChecking=no
      tcp@a.pinggy.io
    depends_on:
      frontend:
        condition: service_healthy
```

---

## 5. Network Topology

### Local Development

```
Developer Machine
├── Backend (host:8080, host:8081)
├── Frontend Vite Dev Server (host:5173)
└── Docker Network
    ├── MongoDB (localhost:27017)
    ├── Kafka (localhost:9092)
    └── Elasticsearch (localhost:9200)
```

### Production

```
Docker Network (simonrowe-prod)
├── Backend (internal:8080, exposed:8081)
├── Frontend/Nginx (internal:80)
│   └── Reverse proxy /api/* → backend:8080
├── MongoDB (internal:27017)
├── Kafka (internal:9092)
├── Elasticsearch (internal:9200)
├── OTel Collector (internal:4317)
└── Pinggy Tunnel
    └── Public URL → frontend:80
```

---

## 6. Data Volumes

| Volume | Service | Mount Point | Purpose |
|--------|---------|------------|---------|
| `mongodb-data` | MongoDB | `/data/db` | Persistent database storage |
| `kafka-data` | Kafka | `/var/lib/kafka/data` | Kafka log segments |
| `elasticsearch-data` | Elasticsearch | `/usr/share/elasticsearch/data` | Search index data |

All volumes use Docker named volumes. In production, these persist across container restarts. For a clean reset, volumes can be removed with `docker-compose down -v`.
