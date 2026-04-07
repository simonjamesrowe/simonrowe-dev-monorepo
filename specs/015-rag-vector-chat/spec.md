# Feature Specification: RAG-Enhanced AI Chat with Vector Embeddings

**Feature Branch**: `015-rag-vector-chat`
**Created**: 2026-04-05
**Status**: Draft
**Input**: Replace keyword-based chat search with semantic RAG pipeline using vector embeddings in Elasticsearch, add code examples collection for skills, and add Elasticsearch backup/restore support.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Semantic Chat Search (Priority: P1)

A visitor opens the chat on Simon's personal website and asks a natural language question like "What experience does Simon have with event-driven architectures?" The system semantically matches the query against all embedded content (blog posts, job descriptions, skills) and provides a contextually rich answer drawing from the most relevant content — even when the visitor's exact wording doesn't appear in any content.

**Why this priority**: This is the core value proposition. The current keyword-based search misses semantic connections, leading to incomplete or irrelevant chat responses. Semantic search directly improves every visitor's chat experience.

**Independent Test**: Can be fully tested by asking the chat questions that use different wording than the source content (e.g., asking about "microservices" when the blog post uses "distributed systems") and verifying the AI surfaces relevant content.

**Acceptance Scenarios**:

1. **Given** blog posts, jobs, and skills have been embedded into the vector store, **When** a visitor asks "What does Simon know about cloud infrastructure?", **Then** the chat returns an answer referencing relevant blog posts, job experiences, and skills — even if those entries don't contain the exact phrase "cloud infrastructure".
2. **Given** the vector store contains embedded content, **When** a visitor asks a question with no semantically related content, **Then** the chat gracefully responds that it doesn't have specific information on that topic rather than returning irrelevant results.
3. **Given** a visitor is in an active chat session, **When** they ask follow-up questions, **Then** each query retrieves fresh context from the vector store while maintaining conversational continuity.

---

### User Story 2 - Content Embedding & Ingestion (Priority: P1)

When blog posts, job descriptions, or skills are created or updated via the admin CMS, the system automatically chunks and embeds the content into the vector store. This ensures the chat always has up-to-date semantic search capabilities without manual intervention.

**Why this priority**: Without embedded content, the semantic chat (User Story 1) cannot function. Ingestion is a prerequisite that must work reliably.

**Independent Test**: Can be tested by creating or updating a blog post in the admin CMS, then querying the vector store directly to confirm new embeddings were created with correct metadata.

**Acceptance Scenarios**:

1. **Given** a new blog post is published via the admin CMS, **When** the ingestion pipeline runs, **Then** the blog content is chunked and embedded in the vector store with metadata (title, tags, publish date, source type).
2. **Given** an existing blog post is updated, **When** the ingestion pipeline runs, **Then** the old embeddings for that post are replaced with new ones reflecting the updated content.
3. **Given** a blog post is unpublished, **When** the ingestion pipeline runs, **Then** the embeddings for that post are removed from the vector store.
4. **Given** the embedding service is temporarily unavailable, **When** content is created or updated, **Then** the system queues the content for embedding and retries when the service recovers, without blocking the admin workflow.

---

### User Story 3 - Code Examples Management (Priority: P2)

Simon (the site owner) uses the admin CMS to create and manage a collection of code examples. Each code example is associated with one or more skills and includes a title, description, programming language, and the code itself. These code examples are embedded into the vector store so visitors can ask code-related questions in chat, and Simon can use them as a personal reference library.

**Why this priority**: Adds a new content type that enriches the chat's ability to answer technical questions and doubles as an internal reference tool. Valuable but the core RAG pipeline must work first.

**Independent Test**: Can be tested by creating code examples in the admin CMS, verifying they appear in the code examples listing, and then asking the chat a code-related question to confirm the examples are retrieved.

**Acceptance Scenarios**:

1. **Given** Simon is logged into the admin CMS, **When** he navigates to the Code Examples section, **Then** he sees a list of all code examples with title, language, and associated skills.
2. **Given** Simon is on the Code Examples page, **When** he creates a new code example with a title, description, language, code content, and linked skills, **Then** the example is saved and appears in the listing.
3. **Given** a code example exists, **When** Simon edits it and saves, **Then** the changes are persisted and the vector store embeddings are updated.
4. **Given** a code example is linked to the "Kafka" skill, **When** a visitor asks the chat "Can you show me how Simon works with Kafka?", **Then** the chat retrieves and references the relevant code example in its response.
5. **Given** Simon wants to find his own code examples, **When** he browses the Code Examples section in admin, **Then** he can filter by skill and search by title or description.

---

### User Story 4 - Elasticsearch Backup & Restore (Priority: P2)

Since Elasticsearch now stores vector embeddings (which are derived from content but expensive to regenerate), the existing backup and restore scripts are extended to include Elasticsearch data. This ensures disaster recovery covers all critical data stores.

**Why this priority**: Data safety is essential once Elasticsearch holds non-trivial derived data. Without backup/restore, a data loss event would require full re-embedding of all content.

**Independent Test**: Can be tested by running the backup script, verifying it includes Elasticsearch data, then restoring to a clean environment and confirming vector search works correctly.

**Acceptance Scenarios**:

1. **Given** the system has embedded content in Elasticsearch, **When** the backup script runs, **Then** Elasticsearch indices (including vector embeddings) are exported and included in the backup archive alongside the existing MongoDB dump and uploads.
2. **Given** a backup archive containing Elasticsearch data exists, **When** the restore script runs, **Then** Elasticsearch indices are restored and semantic search works immediately without re-embedding.
3. **Given** a backup is run, **When** the backup completes, **Then** the backup size and included Elasticsearch indices are reported in the script output.
4. **Given** the Elasticsearch container is not running, **When** the backup script runs, **Then** it reports a warning about skipped Elasticsearch backup but still completes the MongoDB and uploads backup successfully.

---

### User Story 5 - Admin Re-embedding Trigger (Priority: P3)

Simon can trigger a full re-embedding of all content from the admin dashboard. This is useful after initial setup, after restoring from a backup that didn't include embeddings, or if the embedding model changes.

**Why this priority**: An operational convenience that prevents Simon from needing to manually intervene at the database level. Lower priority because it's only needed occasionally.

**Independent Test**: Can be tested by triggering re-embedding from the admin UI and confirming all content types have fresh embeddings in the vector store.

**Acceptance Scenarios**:

1. **Given** Simon is on the admin dashboard, **When** he triggers a full re-embedding, **Then** the system processes all blog posts, jobs, skills, and code examples through the embedding pipeline.
2. **Given** a re-embedding is in progress, **When** Simon views the admin dashboard, **Then** he sees progress indication showing how many items have been processed out of the total.
3. **Given** a re-embedding is in progress, **When** a visitor uses the chat, **Then** the chat continues to work using whatever embeddings are currently available (no downtime).

---

### Edge Cases

- What happens when the embedding model API is rate-limited or returns errors during bulk ingestion? The system should implement retry with backoff and surface errors in the admin UI.
- What happens when content is very short (e.g., a skill with just a name and rating)? The system should still embed it with available metadata but may combine it with related context (e.g., skill group description).
- What happens when Elasticsearch is restarted and loses in-memory data? Persistent storage volumes ensure data survives restarts; backup/restore provides disaster recovery.
- What happens when a code example contains very long code blocks? The chunking strategy should handle code examples appropriately, potentially keeping code blocks intact rather than splitting mid-function.
- What happens when the same content appears in multiple sources (e.g., a skill referenced in a blog and a job)? Each source type should be embedded independently with its own metadata to allow the retrieval layer to deduplicate or rank by relevance.

## Requirements *(mandatory)*

### Functional Requirements

#### Semantic Search & RAG

- **FR-001**: System MUST embed text content into vector representations and store them in the existing search infrastructure alongside keyword indices.
- **FR-002**: System MUST perform semantic similarity search against embedded content when a visitor sends a chat message.
- **FR-003**: System MUST provide the top relevant content chunks as context to the AI model when generating chat responses.
- **FR-004**: System MUST include source metadata (content type, title, URL) with each retrieved chunk so the AI can reference sources in its responses.
- **FR-005**: System MUST support embedding and retrieval for four content types: blog posts, job descriptions, skills, and code examples.

#### Content Ingestion & Embedding

- **FR-006**: System MUST automatically chunk and embed content when it is created, updated, or deleted via the admin CMS.
- **FR-007**: System MUST use overlapping chunks (approximately 500 tokens with configurable overlap) for long-form content (blogs, job descriptions).
- **FR-008**: System MUST preserve metadata with each chunk: source entity ID, content type, title, and relevant tags/skills.
- **FR-009**: System MUST remove stale embeddings when source content is deleted or unpublished.
- **FR-010**: System MUST support periodic full sync to catch any missed updates, in addition to event-driven ingestion.

#### Code Examples Collection

- **FR-011**: System MUST provide an admin interface to create, read, update, and delete code examples.
- **FR-012**: Each code example MUST have: title, description, programming language, code content, and association with one or more skills.
- **FR-013**: Code examples MUST be searchable and filterable by skill and by title/description in the admin interface.
- **FR-014**: Code examples MUST be embedded into the vector store for semantic retrieval during chat.
- **FR-015**: System MUST display code examples with syntax highlighting in the admin interface.

#### Backup & Restore

- **FR-016**: The backup process MUST export Elasticsearch index data (including vector embeddings) as part of the standard backup archive.
- **FR-017**: The restore process MUST import Elasticsearch data from the backup archive and restore indices to a functional state.
- **FR-018**: Backup and restore MUST handle Elasticsearch being unavailable gracefully — backing up what is available and warning about what was skipped.

#### Admin Operations

- **FR-019**: System MUST provide an admin function to trigger full re-embedding of all content types.
- **FR-020**: System MUST show progress feedback during re-embedding operations.
- **FR-021**: Re-embedding MUST NOT cause downtime for visitor-facing chat functionality.

### Key Entities

- **Code Example**: A code snippet managed by the site owner. Attributes: title, description, programming language, code content, associated skills (many-to-many relationship with Skill), creation and modification timestamps.
- **Embedding Chunk**: A vector representation of a content fragment. Attributes: vector data, source entity reference (ID and type), chunk text, metadata (title, tags, content type), chunk position within source.
- **Embedding Job**: A record of an embedding operation. Attributes: status (pending, in-progress, completed, failed), content type, entity ID, timestamps, error details if failed.

## Assumptions

- The system will migrate from Groq to OpenAI as the single AI provider for both chat completions (GPT 5.4 Nano at $0.20/$1.25 per 1M tokens) and embeddings (text-embedding-3-small at $0.02 per 1M tokens). This simplifies configuration to a single API key and reduces costs compared to the current Groq setup.
- The existing 4-hour Elasticsearch sync schedule is a reasonable baseline for periodic full vector sync, but event-driven embedding on content change is the primary mechanism.
- Code examples are a new content type stored in the primary database with the same patterns as existing entities (blogs, skills, jobs).
- The admin CMS code examples page follows the same layout patterns as the existing admin pages (list view with create/edit forms).
- Elasticsearch 8.x (currently in the Docker Compose stack) supports dense vector fields and kNN search natively.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Chat responses to semantically related questions (where exact keywords don't match) return relevant content at least 80% of the time, compared to the current keyword-based approach.
- **SC-002**: New or updated content is available for semantic search within 60 seconds of being saved in the admin CMS.
- **SC-003**: Full re-embedding of all content (estimated hundreds of items) completes within 10 minutes.
- **SC-004**: Backup and restore of the complete system (MongoDB + Elasticsearch + uploads) completes successfully and semantic search works immediately after restore without manual intervention.
- **SC-005**: Chat response time (from visitor message to first streamed token) remains under 5 seconds, including vector retrieval time.
- **SC-006**: Code examples can be created and managed through the admin CMS with the same ease as existing content types (blogs, skills, jobs).
- **SC-007**: The site owner can find any code example by skill or keyword within 10 seconds using the admin interface filters.
