# Quickstart: Profile Chat with MCP Tools

**Feature**: 009-profile-chat
**Date**: 2026-02-26

## Prerequisites

- Java 21+ installed
- Node.js 20+ installed
- Docker & Docker Compose running
- Groq API key (get one at https://console.groq.com/keys)

## 1. Start Infrastructure

```bash
docker compose up -d
```

This starts MongoDB, Kafka, and Elasticsearch.

## 2. Configure Groq API Key

Set the environment variable before running the backend:

```bash
export GROQ_API_KEY=gsk_your_key_here
```

Or add to `backend/src/main/resources/application-local.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${GROQ_API_KEY}
```

## 3. Backend Dependencies Added

The following are added to `gradle/libs.versions.toml`:

```toml
[versions]
springAi = "1.1.2"
bucket4j = "8.14.0"

[libraries]
spring-ai-bom = { module = "org.springframework.ai:spring-ai-bom", version.ref = "springAi" }
spring-ai-starter-model-openai = { module = "org.springframework.ai:spring-ai-starter-model-openai" }
spring-ai-starter-mcp-server-webmvc = { module = "org.springframework.ai:spring-ai-starter-mcp-server-webmvc" }
spring-boot-starter-websocket = { module = "org.springframework.boot:spring-boot-starter-websocket" }
bucket4j-core = { module = "com.bucket4j:bucket4j-core", version.ref = "bucket4j" }
```

And in `backend/build.gradle.kts`:

```kotlin
dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom)
    }
}

dependencies {
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.bucket4j.core)
}
```

## 4. Frontend Dependencies Added

```bash
cd frontend
npm install @stomp/stompjs
```

## 5. Run the Backend

```bash
cd backend
../gradlew bootRun
```

Backend starts on port 8080. Key new endpoints:
- WebSocket: `ws://localhost:8080/ws/chat`
- MCP Server (SSE): `http://localhost:8080/mcp`

## 6. Run the Frontend

```bash
cd frontend
npm run dev
```

Frontend starts on port 5173. Navigate to `http://localhost:5173`.

## 7. Test the Chat

1. Type a question in the search bar on the profile homepage
2. Press Enter — a chat panel opens with your question
3. The AI responds using profile, blog, job, and skills data
4. Send follow-up messages in the conversation

## 8. Test MCP Tools

Connect any MCP-compatible client to `http://localhost:8080/mcp` (SSE transport).

Available tools:
- `get_profile` — Profile information
- `search_blogs` — Blog search by keyword
- `get_jobs` — Employment history
- `get_skills` — Skills and skill groups
- `search_site` — Cross-content search

## Key Configuration Properties

```yaml
# application.yml additions
spring:
  ai:
    openai:
      api-key: ${GROQ_API_KEY}
      base-url: https://api.groq.com/openai
      chat:
        model: llama-3.3-70b-versatile
    mcp:
      server:
        type: SYNC
        annotation-scanner:
          enabled: true

chat:
  session:
    cleanup-interval-minutes: 5
    max-inactive-minutes: 30

rate-limit:
  chat:
    requests-per-minute: 20
  mcp:
    requests-per-minute: 60
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GROQ_API_KEY` | Yes | — | Groq API key for LLM access |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:5173` | Allowed CORS origins |
