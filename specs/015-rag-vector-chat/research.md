# Research: RAG-Enhanced AI Chat with Vector Embeddings

**Feature**: 015-rag-vector-chat | **Date**: 2026-04-05

## R1: Embedding Provider — OpenAI via Spring AI

**Decision**: Use OpenAI as the single AI provider for both chat (GPT 5.4 Nano) and embeddings (`text-embedding-3-small`).

**Rationale**: Groq doesn't offer embeddings. Anthropic doesn't offer embeddings. OpenAI provides both chat and embeddings through a single API key and Spring AI dependency (`spring-ai-starter-model-openai`). GPT 5.4 Nano ($0.20/$1.25 per 1M tokens) is cheaper than the current Groq kimi-k2 ($1.00/$3.00). Embedding cost is negligible at $0.02/1M tokens.

**Alternatives considered**:
- Groq + separate OpenAI for embeddings only — two providers, two API keys, more config complexity
- Anthropic Haiku + Voyage AI — two providers, more expensive for chat ($1.00/$5.00)
- Local Ollama for embeddings — free but slow on Raspberry Pi deployment, still need Groq/other for chat

**Configuration pattern**:
```yaml
spring.ai.openai.api-key: ${OPENAI_API_KEY}
spring.ai.openai.chat.options.model: gpt-5.4-nano
spring.ai.openai.embedding.options.model: text-embedding-3-small
```

Both models auto-configured from the single `spring-ai-starter-model-openai` dependency. Chat and embedding configs are independent namespaces sharing only the API key.

## R2: Vector Store — Elasticsearch 8.x

**Decision**: Use Spring AI's `spring-ai-starter-vector-store-elasticsearch` with the existing Elasticsearch 8.17.0 instance.

**Rationale**: Elasticsearch is already in the Docker Compose stack. ES 8.x natively supports `dense_vector` fields with kNN search. No new infrastructure needed. Spring AI provides a first-party integration.

**Alternatives considered**:
- PGVector — would require adding PostgreSQL to the stack
- Milvus/Weaviate/Qdrant — dedicated vector DBs, overkill for hundreds of documents
- Chroma — another new service to manage

**Key details**:
- Spring AI dependency: `spring-ai-starter-vector-store-elasticsearch`
- Auto-configures `ElasticsearchVectorStore` bean using the existing ES `RestClient`
- Creates vector index automatically on startup if not exists
- `text-embedding-3-small` produces 1536-dimensional vectors
- Similarity: cosine (recommended for OpenAI embeddings)
- Index config via properties: `spring.ai.vectorstore.elasticsearch.index-name`, `.dimensions`, `.similarity`

**Docker Compose change**: Add `path.repo` environment variable and backup volume mount for snapshot/restore support.

## R3: RAG Integration — RetrievalAugmentationAdvisor

**Decision**: Use Spring AI's `RetrievalAugmentationAdvisor` (newer API in 1.1.2) to inject vector store context into chat prompts.

**Rationale**: The `RetrievalAugmentationAdvisor` provides more control over retrieval parameters than the older `QuestionAnswerAdvisor`. It can be added as an advisor alongside the existing `MessageChatMemoryAdvisor` in `ChatConfig`.

**Integration point**: The existing `ChatConfig.chatClient()` bean currently registers `MessageChatMemoryAdvisor` and `ProfileMcpTools`. The RAG advisor will be added as an additional default advisor. The existing MCP tools (`searchBlogs`, `searchSite`) can remain as fallbacks for keyword search, or be removed once vector search proves reliable.

**Configuration**:
```java
RetrievalAugmentationAdvisor.builder()
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.7)
        .topK(5)
        .build())
    .build()
```

## R4: Content Chunking Strategy

**Decision**: Use Spring AI's `TokenTextSplitter` for ~500 token chunks. Implement custom overlap wrapper if needed.

**Rationale**: `TokenTextSplitter` is Spring AI's built-in token-aware splitter. It works directly with the `Document` abstraction that `VectorStore.add()` expects.

**Details**:
- Chunk size: 500 tokens
- Min chunk size: 100 characters
- Max chunks per document: 100
- Spring AI 1.1.2 may not have native overlap — implement thin wrapper if overlap is needed
- Code examples: keep code blocks intact where possible, embed as single chunks when under token limit

**Content-specific strategies**:
- Blog posts: chunk body content, attach metadata (title, tags, skills, publish date, type=blog)
- Job descriptions: chunk long description, attach metadata (company, title, dates, skills, type=job)
- Skills: embed name + description + group context as single chunk (typically short), type=skill
- Code examples: embed title + description + code as single chunk when possible, type=code_example

## R5: Elasticsearch Backup/Restore

**Decision**: Use Elasticsearch's snapshot/restore API with a filesystem repository inside the Docker container.

**Rationale**: This is the official ES backup mechanism. It handles vector indices natively. The snapshot is a compressed binary that includes all index data, mappings, and settings.

**Implementation approach**:
1. Add `path.repo=/usr/share/elasticsearch/backups` env var to ES container
2. Mount host volume for backup data persistence
3. Register a filesystem snapshot repository via ES API
4. Create named snapshots via `_snapshot` API with `wait_for_completion=true`
5. Copy snapshot data from volume into the backup tarball
6. On restore: copy snapshot data back, register repo, restore snapshot

**Existing script integration**: Extend `scripts/backup.sh` and `scripts/restore.sh` to include ES snapshot alongside MongoDB dump and uploads.

## R6: Event-Driven Embedding Ingestion

**Decision**: Extend the existing Kafka `ContentChangeConsumer` pattern to trigger embedding on content changes.

**Rationale**: The codebase already has `ContentChangeEvent` records (CREATED, UPDATED, DELETED) and a `ContentChangeConsumer` with retry/DLT. Adding a new consumer group for embeddings follows the same proven pattern.

**Note**: The `ContentChangeEvent` infrastructure exists but events are not currently being published from admin controllers. This feature should wire up event publishing alongside the embedding consumer.

**New content type**: Add `CODE_EXAMPLE` to `ContentType` enum alongside existing BLOG, JOB, SKILL.

## R7: Re-embedding via DataOperations Pattern

**Decision**: Reuse the existing `DataOperation` / `DataOperationsService` / SSE pattern for the admin re-embedding trigger.

**Rationale**: The codebase already has a complete long-running operation framework with:
- `DataOperation` record with progress tracking (percent, message)
- `DataOperationsService` with atomic operation locking and SSE broadcasting
- `DataOperationsController` with `/progress` SSE endpoint
- The `rebuild-index` endpoint is nearly identical to what we need

**Implementation**: Add `REEMBED_CONTENT` to `OperationType`, add a new endpoint, iterate over all content types, embed in batches, update progress per batch.

## R8: GraalVM Native Image Compatibility

**Decision**: Test native image compatibility early; prepare fallback to JVM build if ES vector store causes issues.

**Rationale**: Spring AI OpenAI starter is generally compatible with GraalVM. The Elasticsearch vector store dependency adds the ES Java client which uses extensive reflection for Jackson serialization. This may require additional reflection hints.

**Mitigation**: Run GraalVM tracing agent during integration tests to generate reflection configs. Add `@RegisterReflectionForBinding` for any custom DTOs. If issues persist, the JVM build is still a viable production deployment.

## R9: Constitution Compliance — AI Provider Change

**Decision**: Amend the constitution to replace "Google Gemini" with "OpenAI" as the AI provider.

**Rationale**: The constitution (v1.8.0) states "Spring AI 1.1.2 with Google Gemini as the LLM provider" and "No other LLM provider SDK MAY be introduced." However:
1. The actual codebase already uses Groq via the OpenAI-compatible API (not Gemini)
2. The constitution is outdated — it references `google-genai` but `application.yml` points to `api.groq.com`
3. OpenAI provides both chat and embeddings, which Gemini/Groq cannot
4. This is a justified amendment: the constitution should reflect reality and the new single-provider approach

**Action**: Update constitution Principle II and the Technology Stack Constraints table as part of this feature.
