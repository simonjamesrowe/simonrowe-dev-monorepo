# Implementation Plan: RAG-Enhanced AI Chat with Vector Embeddings

**Branch**: `015-rag-vector-chat` | **Date**: 2026-04-05 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/015-rag-vector-chat/spec.md`

## Summary

Replace the current keyword-based Elasticsearch chat search with a semantic RAG (Retrieval-Augmented Generation) pipeline. Blog posts, job descriptions, skills, and a new code examples collection are chunked and embedded as vectors in Elasticsearch using OpenAI's `text-embedding-3-small`. Chat queries are semantically matched via kNN search, providing the LLM (GPT 5.4 Nano) with contextually relevant content. The feature also adds Elasticsearch backup/restore, a code examples admin CRUD, and an admin re-embedding trigger.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 3.5.9, Spring AI 1.1.2 (`spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-elasticsearch`), React (latest stable)
**Storage**: MongoDB 8 (primary), Elasticsearch 8.17.0 (search + vector store), Kafka (async messaging)
**Testing**: JUnit 5 + Testcontainers (backend), Vitest (frontend)
**Target Platform**: Docker Compose on Raspberry Pi (ARM) + local dev (macOS)
**Project Type**: Web application (Spring Boot backend + React frontend)
**Performance Goals**: Chat first-token < 5s including vector retrieval; embedding ingestion < 60s per content change
**Constraints**: GraalVM native image compatibility; single OpenAI API key for both chat and embeddings; ARM deployment
**Scale/Scope**: Hundreds of content items (blogs, jobs, skills, code examples); single admin user; low-traffic personal site

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | No changes to container architecture |
| II. Modern Java & React Stack | VIOLATION — JUSTIFIED | Constitution says "Google Gemini" as LLM provider. Actual codebase already uses Groq/OpenAI-compatible API. Changing to OpenAI for unified chat+embeddings. Constitution amendment required. |
| III. Quality Gates | PASS | Tests with Testcontainers, Checkstyle, JaCoCo all maintained |
| IV. Observability | PASS | New embedding service will use @WithSpan tracing |
| V. Simplicity & Incremental Delivery | PASS | Reuses existing patterns (DataOps, ContentChangeConsumer, admin CRUD) |
| VI. Admin CMS UX Standards | PASS | Code examples admin follows existing patterns (Lucide icons, list+editor layout) |
| VII. Backup & Restore | PASS — EXTENDED | Extending backup/restore to include Elasticsearch (additive, not breaking) |
| VIII. Shell Scripting Standards | PASS | Backup/restore extensions follow bash strict mode patterns |

### Post-Phase 1 Re-check

| Principle | Status | Notes |
|-----------|--------|-------|
| II. AI Provider | JUSTIFIED | See Complexity Tracking. OpenAI provides embeddings that Gemini/Groq cannot. Single-provider simplification. |
| All others | PASS | No additional violations introduced by design artifacts |

## Project Structure

### Documentation (this feature)

```text
specs/015-rag-vector-chat/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 research findings
├── data-model.md        # Entity definitions
├── quickstart.md        # Setup guide
├── contracts/           # API contracts
│   ├── admin-code-examples-api.yaml
│   └── admin-embedding-ops-api.yaml
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── admin/
│   │   ├── CodeExample.java                    # NEW entity
│   │   ├── AdminCodeExampleController.java     # NEW CRUD controller
│   │   └── AdminCodeExampleRepository.java     # NEW repository
│   ├── chat/
│   │   └── ChatConfig.java                     # MODIFIED — add RAG advisor
│   ├── embedding/
│   │   ├── EmbeddingConfig.java                # NEW — VectorStore + splitter config
│   │   ├── EmbeddingService.java               # NEW — chunk, embed, store
│   │   └── EmbeddingChangeConsumer.java        # NEW — Kafka consumer for embedding
│   ├── dataops/
│   │   ├── OperationType.java                  # MODIFIED — add REEMBED_CONTENT
│   │   └── DataOperationsController.java       # MODIFIED — add /reembed endpoint
│   ├── events/
│   │   └── ContentChangeEvent.java             # MODIFIED — add CODE_EXAMPLE to ContentType
│   └── search/
│       └── (existing files unchanged)
├── src/test/java/com/simonrowe/
│   ├── admin/
│   │   └── AdminCodeExampleControllerTest.java # NEW
│   └── embedding/
│       ├── EmbeddingServiceTest.java           # NEW
│       └── EmbeddingChangeConsumerTest.java    # NEW
└── build.gradle.kts                            # MODIFIED — add ES vector store dependency

frontend/
├── src/
│   ├── pages/admin/
│   │   ├── CodeExamplesAdmin.tsx               # NEW — list page
│   │   └── CodeExampleEditor.tsx               # NEW — create/edit form
│   ├── services/
│   │   └── adminApi.ts                         # MODIFIED — add code example API methods
│   └── components/admin/
│       └── (reuse existing patterns)
└── src/styles.css                              # MODIFIED — code example styles

scripts/
├── backup.sh                                   # MODIFIED — add ES snapshot
└── restore.sh                                  # MODIFIED — add ES restore

docker-compose.yml                              # MODIFIED — ES backup volume
docker-compose.prod.yml                         # MODIFIED — ES backup volume
```

**Structure Decision**: Follows existing web application structure. New `embedding/` package for vector store logic, keeping it separate from existing `search/` package (which handles keyword search). Code examples follow the established `admin/` entity pattern.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| AI provider change (Gemini → OpenAI) | OpenAI is the only provider offering both chat and embeddings via a single API. Eliminates multi-provider complexity. | Keeping Gemini/Groq requires a second provider for embeddings (two API keys, two billing accounts, more config). Constitution already out of date — codebase uses Groq, not Gemini. |
