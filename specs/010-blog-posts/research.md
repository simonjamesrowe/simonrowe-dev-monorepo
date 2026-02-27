# Research: Blog Post Series - Rebuilding simonrowe.dev with AI

**Feature**: 010-blog-posts
**Date**: 2026-02-27

## Research Tasks

### R1: Blog Data Model and Insertion Pattern

**Decision**: Blog posts will be inserted via a MongoDB migration script (`.js` file run via `mongosh`), consistent with the existing `migrate-strapi-data.js` and `add-global-job-data.js` patterns.

**Rationale**: The project has no admin/CMS API for creating blog posts yet (Spec 007 is future work). The existing pattern uses JavaScript migration scripts run against MongoDB, which handles DBRef creation for tags and skills. This is the established, working approach.

**Alternatives considered**:
- Direct MongoDB shell insertMany: Less maintainable, harder to make idempotent
- Spring Boot CommandLineRunner seed: Would require code changes to the backend, not content-focused
- REST API: No create endpoint exists; building one is out of scope (Spec 007)

### R2: Tag and Skill Linkage

**Decision**: Blog posts use `@DBRef` references to both `tags` and `skills` collections. New tags must be inserted into the `tags` collection first, then blog documents reference them via `{ "$ref": "tags", "$id": ObjectId(...) }` format.

**Rationale**: Verified from `Blog.java` (lines 22-23: `@DBRef List<Tag> tags, @DBRef List<Skill> skills`) and the migration script (lines 126-133). This is the existing pattern — no deviation needed.

**Key finding**: The `skills` collection (used by `Blog.java` via `com.simonrowe.blog.Skill`) stores simple `{_id, name}` documents. These are the same skill names that appear in `skill_groups.skills[]` but stored flat for blog DBRef lookups. The migration script copies skills from Strapi to this flat collection. New skills relevant to blog posts (e.g., "Claude Code", "React", "Spring Boot") should already exist from prior migrations.

### R3: Existing Tags Available for Reuse

**Decision**: Reuse existing tags from Strapi migration where applicable. Create new tags only for concepts not already present.

**Existing tags** (from Strapi migration — names preserved as-is):
- Technology tags likely include: Kubernetes, Docker, Spring, Spring Boot, React, JavaScript, etc. (exact list depends on Strapi backup data)

**New tags to create**:
- SpecKit
- Conductor
- Spec-Driven Development
- AI Productivity
- Parallel Development
- Data Migration
- Retrospective
- AI (if not already present)

### R4: Featured Images for Blog Posts

**Decision**: Each blog post will have a `featuredImageUrl` pointing to an image in `backend/uploads/`. Images will be simple branded illustrations or relevant screenshots created for the series.

**Rationale**: The existing blog system renders `featuredImageUrl` as a hero image on the detail page and a card thumbnail on the listing page. The `IndexService.blogToSiteDocument()` method (line 112) passes the URL through to both site search and blog search indices. Images must be placed in `backend/uploads/` to be served by Spring's ResourceHandlerRegistry at `/uploads/**`.

**Image naming convention**: `/uploads/blog-rebuild-{N}-{slug}.png` where N is the post number (1-5).

### R5: Code Examples in Blog Posts

**Decision**: Each blog post will contain 2-4 code examples drawn from the actual project source code, embedded as fenced markdown code blocks with language identifiers (```java, ```typescript, ```yaml, etc.).

**Rationale**: The existing `MarkdownRenderer` component uses `react-markdown` with `rehype-raw` and `remark-gfm`, plus a custom `CodeBlock` component with syntax highlighting via `react-syntax-highlighter` (Prism). This already supports fenced code blocks with language-tagged syntax highlighting.

**Code examples per post**:
- Post 1: SpecKit spec template structure, directory layout
- Post 2: Spring Boot entity record, Docker Compose snippet, React component snippet
- Post 3: Conductor workspace pattern, Elasticsearch index config, blog API endpoint
- Post 4: Migration script excerpt, CORS config, tour step data model
- Post 5: Test example, CI workflow excerpt, project stats summary

### R6: Elasticsearch Indexing Strategy

**Decision**: After inserting blog posts via the migration script, the application's `IndexService.fullSyncBlogIndex()` and `IndexService.fullSyncSiteIndex()` methods will reindex all content on next startup. No manual Elasticsearch insertion is needed.

**Rationale**: The IndexService (lines 173-218) provides `fullSyncSiteIndex()` and `fullSyncBlogIndex()` methods that read all published blogs from MongoDB and bulk-index them to Elasticsearch. These run automatically or can be triggered. This is simpler and more reliable than trying to replicate the indexing logic in the migration script.

### R7: Blog URL Pattern

**Decision**: Blog detail pages use `/blogs/{id}` where `id` is the MongoDB document `_id` (ObjectId string).

**Rationale**: Confirmed from `IndexService.blogToSiteDocument()` (line 120: `"/blogs/" + blog.id()`) and frontend route configuration. The constitution mentions `/blogs/{slug}` but the actual implementation uses `/blogs/{id}`. The existing system does not have a slug field.

### R8: Skills Available for Blog DBRef Linking

**Decision**: Blog posts reference skills from the `skills` collection (flat `{_id, name}` documents). The relevant skills for this blog series already exist from Strapi migration and the 009 migration.

**Skills to link per post** (by name, resolved to `_id` at insert time):
- Post 1: (no technical skills — specification focused)
- Post 2: Java, Spring Boot, React, MongoDB, Docker, Typescript
- Post 3: React, Elasticsearch, Java, Spring Boot, MongoDB, Kafka
- Post 4: MongoDB, Docker, React, Javascript
- Post 5: Java, Spring Boot, React, MongoDB, Elasticsearch, Kafka, Docker

**Note**: The `skills` collection (for Blog DBRef) must contain these skill names. They were migrated from Strapi. If any are missing, the migration script must insert them first.
