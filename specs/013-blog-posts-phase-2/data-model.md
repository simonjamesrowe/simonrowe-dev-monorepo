# Data Model: Blog Post Series Phase 2

## New Tags (9 documents in `tags` collection)

| Name | Notes |
|------|-------|
| Auth0 | Identity/auth provider |
| Content Management | CMS topic |
| Spring AI | AI framework |
| MCP (Model Context Protocol) | AI tool protocol |
| Chatbot | Conversational AI |
| Nginx | Reverse proxy/web server |
| Grafana | Observability platform |
| Observability | Monitoring/logging/tracing topic |
| DevOps | Deployment/operations topic |

**Tag document structure** (existing schema):
```javascript
{
  _id: ObjectId(),
  name: "Auth0",
  createdAt: ISODate("2026-04-03T00:00:00Z"),
  updatedAt: ISODate("2026-04-03T00:00:00Z"),
  _class: "com.simonrowe.admin.Tag"
}
```

## Blog Posts (3 documents in `blogs` collection)

### Post 6: Building a CMS from Scratch

```javascript
{
  _id: ObjectId(),
  title: "Building a CMS from Scratch: Auth0, MDXEditor, and a Media Library",
  shortDescription: "How I replaced Strapi with a custom content management system using Auth0 for authentication, MDXEditor for rich markdown editing, and a media library with automatic image variants.",
  content: "... (800+ words, markdown) ...",
  published: true,
  featuredImageUrl: "/uploads/blog-phase2-6-cms.jpg",
  tags: [
    { "$ref": "tags", "$id": ObjectId("...") },  // Auth0
    { "$ref": "tags", "$id": ObjectId("...") },  // Content Management
    { "$ref": "tags", "$id": ObjectId("...") },  // React (existing)
    { "$ref": "tags", "$id": ObjectId("...") },  // Spring Boot (existing)
    { "$ref": "tags", "$id": ObjectId("...") }   // AI (existing or new from 010)
  ],
  skills: [
    { "$ref": "skills", "$id": ObjectId("...") },  // Java
    { "$ref": "skills", "$id": ObjectId("...") },  // Spring Boot
    { "$ref": "skills", "$id": ObjectId("...") },  // React
    { "$ref": "skills", "$id": ObjectId("...") },  // TypeScript
    { "$ref": "skills", "$id": ObjectId("...") }   // MongoDB
  ],
  createdDate: ISODate("2026-03-20T10:00:00Z"),
  updatedDate: ISODate("2026-03-20T10:00:00Z"),
  _class: "com.simonrowe.admin.Blog"
}
```

### Post 7: Adding AI Chat to My Portfolio

```javascript
{
  _id: ObjectId(),
  title: "Adding AI Chat to My Portfolio: Spring AI, Gemini, and MCP Tools",
  shortDescription: "I embedded an AI chatbot into my portfolio site using Spring AI and Google Gemini, with MCP tool endpoints that let the AI query my profile, blogs, and skills in real time.",
  content: "... (800+ words, markdown) ...",
  published: true,
  featuredImageUrl: "/uploads/blog-phase2-7-ai-chat.jpg",
  tags: [
    { "$ref": "tags", "$id": ObjectId("...") },  // Spring AI
    { "$ref": "tags", "$id": ObjectId("...") },  // MCP
    { "$ref": "tags", "$id": ObjectId("...") },  // AI (existing or new)
    { "$ref": "tags", "$id": ObjectId("...") }   // Chatbot
  ],
  skills: [
    { "$ref": "skills", "$id": ObjectId("...") },  // Java
    { "$ref": "skills", "$id": ObjectId("...") },  // Spring Boot
    { "$ref": "skills", "$id": ObjectId("...") },  // React
    { "$ref": "skills", "$id": ObjectId("...") }   // TypeScript
  ],
  createdDate: ISODate("2026-03-27T10:00:00Z"),
  updatedDate: ISODate("2026-03-27T10:00:00Z"),
  _class: "com.simonrowe.admin.Blog"
}
```

### Post 8: Production-Ready

```javascript
{
  _id: ObjectId(),
  title: "Production-Ready: Docker Compose, Backups, and Observability",
  shortDescription: "Taking a personal project to production with Docker Compose, nginx reverse proxy, Google Drive backups, Pinggy tunnelling, and Grafana Cloud observability.",
  content: "... (800+ words, markdown) ...",
  published: true,
  featuredImageUrl: "/uploads/blog-phase2-8-production.jpg",
  tags: [
    { "$ref": "tags", "$id": ObjectId("...") },  // Docker (existing)
    { "$ref": "tags", "$id": ObjectId("...") },  // Nginx
    { "$ref": "tags", "$id": ObjectId("...") },  // Grafana
    { "$ref": "tags", "$id": ObjectId("...") },  // Observability
    { "$ref": "tags", "$id": ObjectId("...") }   // DevOps
  ],
  skills: [
    { "$ref": "skills", "$id": ObjectId("...") },  // Docker
    { "$ref": "skills", "$id": ObjectId("...") },  // MongoDB
    { "$ref": "skills", "$id": ObjectId("...") },  // Kafka (if exists)
    { "$ref": "skills", "$id": ObjectId("...") }   // Elasticsearch (if exists)
  ],
  createdDate: ISODate("2026-04-05T10:00:00Z"),
  updatedDate: ISODate("2026-04-05T10:00:00Z"),
  _class: "com.simonrowe.admin.Blog"
}
```

## Relationships

```
Blog ──@DBRef──> Tag (many-to-many)
Blog ──@DBRef──> Skill (many-to-many)
```

- Tags and skills are referenced by ObjectId via MongoDB `$ref` notation
- Spring Data resolves these automatically on read
- The migration script must look up existing tag/skill ObjectIds before creating blog documents

## Existing Tags to Reuse

The following tags may already exist from the Phase 1 blog series (spec 010) or initial data:
- Spring Boot
- React
- MongoDB
- Docker
- AI
- Elasticsearch

The migration script should look these up by name and reuse their ObjectIds.

## Existing Skills to Reference

Skills are looked up by name. Expected skills in the system:
- Java, Spring Boot, React, TypeScript, MongoDB, Docker, Nginx, Grafana, Kafka, Elasticsearch

Skills that don't exist will be silently skipped (no error, just fewer skill references on the blog post).
