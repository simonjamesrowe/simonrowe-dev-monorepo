# Data Model: Docker Compose Local Deployment

This feature is infrastructure-focused and does not introduce new application-level entities. The "data model" consists of container services, their relationships, and configuration artifacts.

## Service Topology

```
Internet → Pinggy (TLS termination) → Nginx Reverse Proxy (port 80)
                                          ├── Host: simonrowe.dev / www.simonrowe.dev → Frontend (port 80)
                                          │                                               └── /api/, /uploads/ → Backend (port 8080)
                                          └── Host: api.simonrowe.dev → Backend (port 8080)
                                                                          ├── MongoDB (port 27017)
                                                                          ├── Kafka (port 29092)
                                                                          └── Elasticsearch (port 9200)
```

## Container Services

| Service          | Image                                                      | Ports (internal) | Volumes                | Health Check          |
|------------------|------------------------------------------------------------|-----------------|-----------------------|-----------------------|
| mongodb          | mongo:8                                                    | 27017            | mongodb-data          | mongosh ping          |
| kafka            | confluentinc/cp-kafka:7.8.0                                | 29092, 9093      | kafka-data            | broker-api-versions   |
| elasticsearch    | elasticsearch:8.17.0                                       | 9200             | elasticsearch-data    | curl cluster/health   |
| backend          | ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend      | 8080, 8081       | backend-uploads       | curl :8081/actuator   |
| frontend         | ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend     | 80               | —                     | curl :80/             |
| nginx            | nginx:alpine                                               | 80               | nginx-proxy.conf (bind) | curl :80/           |
| otel-collector   | otel/opentelemetry-collector-contrib                       | 4317             | otel config (bind)    | —                     |
| pinggy           | pinggy/pinggy                                              | —                | —                     | —                     |

## Named Volumes

| Volume             | Mount Path                           | Purpose                          |
|--------------------|--------------------------------------|----------------------------------|
| mongodb-data       | /data/db                             | MongoDB data persistence         |
| kafka-data         | /var/lib/kafka/data                  | Kafka log segments persistence   |
| elasticsearch-data | /usr/share/elasticsearch/data        | Elasticsearch index persistence  |
| backend-uploads    | (backend container uploads path)     | Media asset persistence          |

## Configuration Files

| File                | Container   | Mount Path                              | Purpose                                  |
|---------------------|-------------|-----------------------------------------|------------------------------------------|
| nginx-proxy.conf    | nginx       | /etc/nginx/conf.d/default.conf          | Reverse proxy hostname routing           |
| otel-collector-config.yaml | otel-collector | /etc/otelcol-contrib/config.yaml | OTel trace pipeline configuration  |

## Environment Variables by Container

### Backend
| Variable                          | Source     | Value                                |
|-----------------------------------|------------|--------------------------------------|
| SPRING_DATA_MONGODB_URI           | Hardcoded  | mongodb://mongodb:27017/simonrowe    |
| SPRING_KAFKA_BOOTSTRAP_SERVERS    | Hardcoded  | kafka:29092                          |
| SPRING_ELASTICSEARCH_URIS         | Hardcoded  | http://elasticsearch:9200            |
| CORS_ALLOWED_ORIGINS              | Hardcoded  | https://simonrowe.dev,https://www.simonrowe.dev |
| MANAGEMENT_SERVER_PORT            | Hardcoded  | 8081                                 |
| SPRING_THREADS_VIRTUAL_ENABLED    | Hardcoded  | true                                 |
| OTEL_SERVICE_NAME                 | Hardcoded  | simonrowe-backend                    |
| OTEL_EXPORTER_OTLP_ENDPOINT      | Hardcoded  | http://otel-collector:4317           |
| OTEL_METRICS_EXPORTER             | Hardcoded  | none                                 |
| OTEL_LOGS_EXPORTER                | Hardcoded  | none                                 |
| BREVO_SMTP_PASSWORD               | Env file   | ${BREVO_SMTP_PASSWORD}               |
| RECAPTCHA_SECRET_KEY              | Env file   | ${RECAPTCHA_SECRET_KEY}              |
| GROQ_API_KEY                      | Env file   | ${GROQ_API_KEY}                      |
| GOOGLE_DRIVE_CLIENT_ID            | Env file   | ${GOOGLE_DRIVE_CLIENT_ID}            |
| GOOGLE_DRIVE_CLIENT_SECRET        | Env file   | ${GOOGLE_DRIVE_CLIENT_SECRET}        |
| GOOGLE_DRIVE_REFRESH_TOKEN        | Env file   | ${GOOGLE_DRIVE_REFRESH_TOKEN}        |
| GOOGLE_DRIVE_FOLDER_ID            | Env file   | ${GOOGLE_DRIVE_FOLDER_ID}            |
| GOOGLE_GENAI_API_KEY              | Env file   | ${GOOGLE_GENAI_API_KEY}              |

### Pinggy
| Variable      | Source    | Value              |
|---------------|----------|--------------------|
| PINGGY_TOKEN  | Env file | ${PINGGY_TOKEN}    |

### Frontend (build-time)
| Variable               | Source      | Value                          |
|------------------------|------------|--------------------------------|
| VITE_API_BASE_URL      | Build arg  | https://api.simonrowe.dev      |
| VITE_RECAPTCHA_SITE_KEY| Build arg  | ${VITE_RECAPTCHA_SITE_KEY}     |
| VITE_GA_MEASUREMENT_ID | Build arg  | ${VITE_GA_MEASUREMENT_ID}      |

## Dependency Graph

```
mongodb ─────────┐
kafka ───────────┤
elasticsearch ───┤
                 ├── backend ──┐
                 │             ├── frontend ──┐
                 │             │              ├── nginx ── pinggy
                 │             └──────────────┘
otel-collector (independent)
```
