# 001: RAG-Enhanced AI Chat with Vector Embeddings

## Summary
Replace the current Elasticsearch-based blog search tool with a full RAG (Retrieval-Augmented Generation) pipeline. Blog posts, job descriptions, and skills would be embedded as vectors using Spring AI's embedding models and stored in a vector database. When visitors ask questions via chat, their query is embedded and semantically matched against the vector store, providing the LLM with highly relevant context chunks rather than keyword-matched results.

## Why
The current chat uses MCP tools that do keyword-based Elasticsearch searches. This misses semantic connections - e.g., a visitor asking "what experience does Simon have with event-driven architectures?" might not match a blog post about Kafka unless the exact words appear. Vector similarity search would surface contextually relevant content even when wording differs.

## Technical Approach
- **Spring AI VectorStore** abstraction with a provider like Milvus, Weaviate, Qdrant, or PGVector
- **Embedding model**: Use Spring AI's `EmbeddingModel` (e.g., OpenAI `text-embedding-3-small` or a local model via Ollama)
- **Chunking strategy**: Split blog markdown into overlapping chunks (~500 tokens) with metadata (blog title, tags, date)
- **Ingestion pipeline**: On blog publish/update, chunk and embed content into the vector store
- **Retrieval**: Replace or augment the `searchBlogs()` MCP tool to query the vector store with cosine similarity
- **Spring AI QuestionAnswerAdvisor**: Wire the vector store into the ChatClient as a retrieval advisor

## Complexity
Medium-High. Requires a new vector database in the Docker Compose stack, an embedding pipeline, and refactoring the chat service to use RAG advisors.

## Dependencies
- Spring AI vector store starter (e.g., `spring-ai-milvus-store-spring-boot-starter`)
- An embedding model API or local Ollama instance
- New Docker Compose service for the vector DB
