# Quickstart: RAG-Enhanced AI Chat with Vector Embeddings

**Feature**: 015-rag-vector-chat

## Prerequisites

- OpenAI API key (for both GPT 5.4 Nano chat and text-embedding-3-small embeddings)
- Docker Compose running (MongoDB, Kafka, Elasticsearch)
- Existing content in MongoDB (blogs, jobs, skills)

## Environment Setup

Add to `backend/.env`:
```bash
OPENAI_API_KEY=sk-...
```

Remove (no longer needed):
```bash
# GROQ_API_KEY=...  (replaced by OpenAI)
```

## Key Configuration Changes

### application.yml
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-5.4-nano
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      elasticsearch:
        index-name: content-embeddings
        dimensions: 1536
        similarity: cosine
```

### Docker Compose (both dev and prod)
```yaml
elasticsearch:
  environment:
    path.repo: /usr/share/elasticsearch/backups
  volumes:
    - elasticsearch-data:/usr/share/elasticsearch/data
    - elasticsearch-backups:/usr/share/elasticsearch/backups
```

## New Dependencies (backend/build.gradle.kts)

```kotlin
implementation(libs.springAiStarterModelOpenai)          // already exists
implementation(libs.springAiStarterVectorStoreElasticsearch)  // NEW
```

## Verification Steps

1. Start the app: `./scripts/start.sh`
2. Log into admin, trigger re-embedding from dashboard
3. Open chat, ask a semantic question (e.g., "What does Simon know about event-driven systems?")
4. Verify the response references relevant content even without exact keyword matches
5. Create a code example in admin, verify it's retrievable via chat
6. Run `./scripts/backup.sh` — verify ES snapshot is included
7. Run `./scripts/restore.sh` — verify semantic search works after restore

## New Admin Routes

- `/admin/code-examples` — List and manage code examples
- `/admin/code-examples/new` — Create new code example
- `/admin/code-examples/:id` — Edit existing code example

## Architecture Overview

```
Visitor Chat Message
        │
        ▼
   ChatController (WebSocket/STOMP)
        │
        ▼
   ChatService → ChatClient
        │           │
        │    RetrievalAugmentationAdvisor
        │           │
        │    ElasticsearchVectorStore
        │       (kNN search)
        │           │
        │    Retrieved context chunks
        │           │
        ▼           ▼
   OpenAI GPT 5.4 Nano (with context)
        │
        ▼
   Streamed response to visitor

Admin Content Change
        │
        ▼
   Admin Controller (save to MongoDB)
        │
        ▼
   Kafka ContentChangeEvent
        │
        ├──▶ ContentChangeConsumer (existing: search index)
        │
        └──▶ EmbeddingChangeConsumer (NEW: vector embedding)
                    │
                    ▼
              TokenTextSplitter → OpenAI Embedding API → ES VectorStore
```
