# Quickstart: Docker Compose Local Deployment

## Prerequisites

1. Docker and Docker Compose installed
2. Authenticated with GHCR: `docker login ghcr.io`
3. Env file at `~/workspace/simonjamesrowe/env` with all required variables (see `.env.example`)
4. Grafana Cloud account with API key, Tempo and Loki credentials
5. Cloudflare DNS configured (see DNS Setup below)

## Environment Setup

All environment variables live in a single `.env` file at the project root. This file is:
- Read by Docker Compose for variable interpolation (e.g., `${PINGGY_TOKEN}` in the Pinggy command)
- Injected into backend and alloy containers via `env_file: .env`
- Gitignored — never committed to version control

```bash
# Copy env file (done automatically by Conductor setup)
cp ~/workspace/simonjamesrowe/env .env

# Add Grafana Cloud credentials (not in the shared env file)
cat >> .env << 'EOF'
GRAFANA_CLOUD_API_KEY=your-api-key
GRAFANA_CLOUD_TEMPO_USER=your-tempo-user-id
GRAFANA_CLOUD_TEMPO_ENDPOINT=https://tempo-us-central1.grafana.net:443
GRAFANA_CLOUD_LOKI_USER=your-loki-user-id
GRAFANA_CLOUD_LOKI_ENDPOINT=https://logs-us-central1.grafana.net/loki/api/v1/push
EOF
```

See `.env.example` for the full list of required variables.

## Start the Stack

```bash
# Pull latest images and start all services
docker compose -f docker-compose.prod.yml up -d

# Watch logs
docker compose -f docker-compose.prod.yml logs -f

# Check health status
docker compose -f docker-compose.prod.yml ps
```

Backend and frontend images use `pull_policy: always` so they always fetch the latest from GHCR.

## Stop the Stack

```bash
# Stop all containers (preserves volumes)
docker compose -f docker-compose.prod.yml down

# Stop and remove volumes (destroys all data)
docker compose -f docker-compose.prod.yml down --volumes
```

## Access Points

| URL                                | Service           |
|------------------------------------|-------------------|
| https://simonrowe.dev              | Frontend          |
| https://www.simonrowe.dev          | Frontend          |
| https://api.simonrowe.dev/api/*    | Backend API       |
| https://api.simonrowe.dev/ws/chat  | WebSocket (STOMP) |

## Observability (Grafana Cloud)

**Distributed Tracing**: The backend sends OTLP traces to Grafana Alloy, which forwards them to Grafana Cloud Tempo. View traces in Grafana Cloud under Explore > Tempo.

**Logs**: Alloy collects logs from all Docker containers via the Docker socket and ships them to Grafana Cloud Loki. View logs in Grafana Cloud under Explore > Loki.

### Grafana Cloud Setup

1. Sign up at [grafana.com](https://grafana.com) and create a free cloud account
2. Navigate to **Connections > Add new connection** and note:
   - Your Tempo instance user ID and endpoint URL
   - Your Loki instance user ID and endpoint URL
3. Create an API key with `MetricsPublisher` role under **Administration > API Keys**
4. Add the credentials to your `.env` file (see Environment Setup above)

## Cloudflare DNS Setup

Create these CNAME records in Cloudflare (DNS-only mode, grey cloud):

| Type  | Name | Target                  | Proxy Status |
|-------|------|-------------------------|--------------|
| CNAME | @    | (Pinggy tunnel endpoint)| DNS only     |
| CNAME | www  | (Pinggy tunnel endpoint)| DNS only     |
| CNAME | api  | (Pinggy tunnel endpoint)| DNS only     |

The Pinggy tunnel endpoint is shown in the Pinggy dashboard under your custom domain configuration.

## Troubleshooting

- **Images not found**: Run `docker login ghcr.io` and ensure images are published via CI
- **Backend won't start**: Check `docker compose logs backend` — ensure MongoDB, Kafka, and Elasticsearch are healthy first
- **Pinggy tunnel fails**: Verify `PINGGY_TOKEN` is set in `.env` and the Pro subscription is active
- **CORS errors in browser**: Verify `CORS_ALLOWED_ORIGINS` is set to `https://simonrowe.dev,https://www.simonrowe.dev` in the backend environment
- **WebSocket connection fails**: Check that the nginx reverse proxy config includes WebSocket upgrade headers for `api.simonrowe.dev`
- **No traces in Grafana Cloud**: Check `docker compose logs alloy` for auth errors. Verify `GRAFANA_CLOUD_TEMPO_USER` and `GRAFANA_CLOUD_API_KEY` are correct.
- **No logs in Grafana Cloud**: Ensure `/var/run/docker.sock` is mounted into the alloy container. Check `docker compose logs alloy` for Loki push errors.

## Files Modified/Created by This Feature

| File | Action | Purpose |
|------|--------|---------|
| `docker-compose.prod.yml` | Modified | Full stack with nginx, pinggy, alloy, pull policies, CORS, volumes |
| `config/nginx/nginx-proxy.conf` | Created | Hostname-based reverse proxy routing |
| `config/alloy/config.alloy` | Created | Grafana Alloy config for traces (Tempo) and logs (Loki) |
| `.env.example` | Modified | Added Grafana Cloud, Pinggy, and all backend env vars |
| `Dockerfile.frontend` | Modified | Added `VITE_API_BASE_URL` and `VITE_GA_MEASUREMENT_ID` build args |
| `.github/workflows/publish.yml` | Modified | Pass build args to frontend Docker build |
| `conductor.json` | Modified | Setup copies env to root `.env` in addition to backend/frontend |
