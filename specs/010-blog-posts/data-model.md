# Data Model: Blog Post Series - Rebuilding simonrowe.dev with AI

**Feature**: 010-blog-posts
**Date**: 2026-02-27

## Existing Entities (No Changes)

### Blog (collection: `blogs`)

```
{
  _id:              ObjectId     -- auto-generated
  title:            String       -- blog post title
  shortDescription: String       -- summary for listing cards (150-250 chars)
  content:          String       -- full markdown body
  published:        Boolean      -- visibility flag (true = visible)
  featuredImageUrl:  String|null  -- URL to featured image (e.g., "/uploads/blog-rebuild-1.png")
  createdDate:      ISODate      -- publication timestamp
  updatedDate:      ISODate      -- last modified timestamp
  tags:             DBRef[]      -- references to tags collection
  skills:           DBRef[]      -- references to skills collection
}
```

Index: `idx_published_created` compound on `{published: 1, createdDate: -1}`

### Tag (collection: `tags`)

```
{
  _id:   ObjectId    -- auto-generated
  name:  String      -- unique tag name
}
```

Index: unique on `name`

### Skill (collection: `skills`)

```
{
  _id:   ObjectId    -- auto-generated
  name:  String      -- skill name
}
```

## New Data to Insert

### New Tags (8 documents)

| Tag Name | Purpose |
|----------|---------|
| SpecKit | Posts referencing the SpecKit specification toolkit |
| Conductor | Posts about Conductor parallel agent tool |
| Spec-Driven Development | Posts about the spec-first methodology |
| AI Productivity | Posts about AI accelerating development |
| Parallel Development | Posts about concurrent agent workflows |
| Data Migration | Posts about migrating from Strapi |
| Retrospective | The lessons learned post |
| AI | General AI topic tag (create if not existing) |

### New Blog Posts (5 documents)

Each blog post document follows this structure:

```javascript
{
  _id: ObjectId(),
  title: "Post Title",
  shortDescription: "150-250 char summary",
  content: "# Full Markdown Content\n\nWith code blocks, links, etc.",
  published: true,
  featuredImageUrl: "/uploads/blog-rebuild-N-slug.png",
  createdDate: ISODate("2026-02-27T10:00:00Z"),  // staggered timestamps
  updatedDate: ISODate("2026-02-27T10:00:00Z"),
  tags: [
    { "$ref": "tags", "$id": ObjectId("...") },
    // ... more tag refs
  ],
  skills: [
    { "$ref": "skills", "$id": ObjectId("...") },
    // ... more skill refs
  ]
}
```

### Blog Post to Tag Mapping

| Post | Tags |
|------|------|
| 1 - From Zero to Specification | SpecKit, Spec-Driven Development, AI |
| 2 - Building the Foundation | Spring Boot, React, MongoDB, Conductor, AI |
| 3 - Shipping Six Features in a Day | Conductor, AI Productivity, Parallel Development |
| 4 - Interactive Tours & Migration | Data Migration, AI |
| 5 - Lessons Learned | SpecKit, Conductor, Retrospective, AI Productivity |

### Blog Post to Skill Mapping

| Post | Skills (from `skills` collection) |
|------|-----------------------------------|
| 1 - From Zero to Specification | (none — spec-focused) |
| 2 - Building the Foundation | Java, Spring Boot, React, MongoDB, Docker, Typescript |
| 3 - Shipping Six Features in a Day | React, Elastic Search, Java, Spring Boot, MongoDB |
| 4 - Interactive Tours & Migration | MongoDB, Docker, React, Javascript |
| 5 - Lessons Learned | Java, Spring Boot, React, MongoDB, Elastic Search, Docker |

### Featured Images (5 files)

| File | Post |
|------|------|
| `backend/uploads/blog-rebuild-1-specification.png` | Post 1 |
| `backend/uploads/blog-rebuild-2-foundation.png` | Post 2 |
| `backend/uploads/blog-rebuild-3-parallel.png` | Post 3 |
| `backend/uploads/blog-rebuild-4-migration.png` | Post 4 |
| `backend/uploads/blog-rebuild-5-lessons.png` | Post 5 |

## Elasticsearch Impact

No schema changes needed. The existing `BlogSearchDocument` and `SiteSearchDocument` indices will automatically include the new blog posts when `IndexService.fullSyncBlogIndex()` and `IndexService.fullSyncSiteIndex()` execute on application startup.

## Data Relationships

```
Blog ──DBRef──► Tag (many-to-many)
Blog ──DBRef──► Skill (many-to-many)
Blog ──string──► Featured Image (featuredImageUrl)
```

All relationships use existing patterns. No new collections, indices, or schema changes required.
