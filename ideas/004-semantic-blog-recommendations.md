# 004: Semantic Blog Recommendations

## Summary
Use vector embeddings to power a "Related Posts" section on each blog detail page and a personalized "Recommended for You" section on the blog listing page. Instead of matching by shared tags (a common but shallow approach), embeddings capture deeper semantic similarity between posts.

## Why
Currently there are no blog recommendations. Visitors who enjoy one post would benefit from discovering related content. Semantic similarity finds connections that tag-based matching misses - e.g., a post about "Kafka event sourcing" would be related to a post about "CQRS patterns" even if they share no tags.

## Features
- **Related Posts** - 3-5 semantically similar posts shown at the bottom of each blog detail page
- **Reading Journey** - "If you liked this, read next" navigation flow
- **Precomputed Similarities** - Compute and cache similarity scores on blog publish to avoid runtime embedding calls

## Technical Approach
- Reuse the vector store from idea 001
- On blog publish: embed the post, query the vector store for top-5 nearest neighbours, store the result IDs in the blog document
- Expose via `GET /api/blogs/{id}/related` endpoint
- Frontend: "Related Posts" card grid component on `BlogDetailPage`
- Recompute on any blog update via a Kafka content change event listener

## Complexity
Low-Medium (assuming vector store from idea 001 is already in place).

## Dependencies
- Vector store infrastructure (idea 001)
