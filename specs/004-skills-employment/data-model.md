# Data Model: Skills & Employment

**Feature**: 004-skills-employment | **Date**: 2026-02-21

## Overview

Two MongoDB collections serve the skills and employment features. `skill_groups` embeds skills as subdocuments within their parent groups. `jobs` stores employment and education entries with skill references. The resume feature reads from both collections plus the `profiles` collection (owned by Spec 002).

---

## Collection: `skill_groups`

Stores skill category groups with embedded individual skills. 9 documents expected.

### Document Schema

```json
{
  "_id": "ObjectId",
  "name": "string",
  "description": "string | null",
  "rating": "number (0-10, double)",
  "displayOrder": "number (integer)",
  "image": {
    "url": "string",
    "name": "string",
    "width": "number",
    "height": "number",
    "mime": "string",
    "formats": {
      "thumbnail": { "url": "string", "width": "number", "height": "number" },
      "small": { "url": "string", "width": "number", "height": "number" },
      "medium": { "url": "string", "width": "number", "height": "number" },
      "large": { "url": "string", "width": "number", "height": "number" }
    }
  },
  "skills": [
    {
      "id": "string (unique identifier)",
      "name": "string",
      "rating": "number (0-10, double or integer)",
      "displayOrder": "number (integer)",
      "description": "string | null (markdown)",
      "image": {
        "url": "string",
        "name": "string",
        "width": "number",
        "height": "number",
        "mime": "string",
        "formats": {
          "thumbnail": { "url": "string", "width": "number", "height": "number" }
        }
      }
    }
  ]
}
```

### Example Document

```json
{
  "_id": "ObjectId('5f6359e55ee4c9001d2b9628')",
  "name": "Spring",
  "description": "The Spring Framework provides a comprehensive programming and configuration model for modern Java-based enterprise applications...",
  "rating": 9.5,
  "displayOrder": 2,
  "image": {
    "url": "/uploads/spring-logo.png",
    "name": "spring-logo.png",
    "width": 200,
    "height": 200,
    "mime": "image/png",
    "formats": {
      "thumbnail": { "url": "/uploads/thumbnail_spring-logo.png", "width": 50, "height": 50 }
    }
  },
  "skills": [
    {
      "id": "5f635b7e5ee4c9001d2b9633",
      "name": "Spring Boot",
      "rating": 10,
      "displayOrder": 1,
      "description": "Auto-configuration, embedded servers, production-ready features...",
      "image": {
        "url": "/uploads/spring-boot.png",
        "name": "spring-boot.png",
        "width": 100,
        "height": 100,
        "mime": "image/png",
        "formats": {
          "thumbnail": { "url": "/uploads/thumbnail_spring-boot.png", "width": 50, "height": 50 }
        }
      }
    },
    {
      "id": "5f635b8b5ee4c9001d2b9634",
      "name": "Spring MVC",
      "rating": 9,
      "displayOrder": 2,
      "description": "RESTful web services, request mapping...",
      "image": null
    }
  ]
}
```

### Indexes

| Index | Fields | Type | Purpose |
|-------|--------|------|---------|
| `_id` | `_id` | Primary (default) | Document lookup by ID |
| `idx_display_order` | `displayOrder: 1` | Ascending | Sort groups for grid display |

### Field Notes

- `rating`: Pre-calculated aggregate of child skill ratings. Updated when skills are modified (Spec 007 - Content Management). The API layer can also compute this on-the-fly as a fallback.
- `skills[].id`: String identifier used for cross-referencing with `jobs.skills[]`. Migrated from the original Strapi `_id` ObjectId values.
- `skills[].rating`: Numeric 0-10 scale. Drives color coding: green (>= 9), blue (8.5-8.9), orange (< 8.5).
- `skills[].description`: Markdown-formatted text rendered on the frontend with `react-markdown` and in the PDF with commonmark parsing.
- `image` / `skills[].image`: Nullable. When null, the frontend displays a placeholder or hides the image container (edge case EC-008).

---

## Collection: `jobs`

Stores employment positions and educational experiences. 9 documents expected.

### Document Schema

```json
{
  "_id": "ObjectId",
  "title": "string",
  "company": "string",
  "companyUrl": "string (URL)",
  "companyImage": {
    "url": "string",
    "name": "string",
    "width": "number",
    "height": "number",
    "mime": "string",
    "formats": {
      "thumbnail": { "url": "string", "width": "number", "height": "number" },
      "small": { "url": "string", "width": "number", "height": "number" }
    }
  },
  "startDate": "string (ISO date: YYYY-MM-DD)",
  "endDate": "string (ISO date: YYYY-MM-DD) | null",
  "location": "string",
  "shortDescription": "string",
  "longDescription": "string (markdown)",
  "isEducation": "boolean",
  "includeOnResume": "boolean",
  "skills": ["string (skill ID references)"]
}
```

### Example Document

```json
{
  "_id": "ObjectId('5e53704f11c196001d06f914')",
  "title": "Software Engineering Lead",
  "company": "Upp Technologies",
  "companyUrl": "https://upp.ai",
  "companyImage": {
    "url": "/uploads/upp-logo.png",
    "name": "upp-logo.png",
    "width": 200,
    "height": 200,
    "mime": "image/png",
    "formats": {
      "thumbnail": { "url": "/uploads/thumbnail_upp-logo.png", "width": 50, "height": 50 },
      "small": { "url": "/uploads/small_upp-logo.png", "width": 100, "height": 100 }
    }
  },
  "startDate": "2019-04-15",
  "endDate": "2020-05-01",
  "location": "London",
  "shortDescription": "Hands-on lead engineer in a small cross-functional team.",
  "longDescription": "Lead Engineer working on all verticals (front end, backend, ops) of the Upp Intelligent Platform...",
  "isEducation": false,
  "includeOnResume": true,
  "skills": [
    "5f635b7e5ee4c9001d2b9633",
    "5f635b8b5ee4c9001d2b9634",
    "5f635c0f5ee4c9001d2b9639",
    "5f635b495ee4c9001d2b9630",
    "5f635b555ee4c9001d2b9631"
  ]
}
```

### Indexes

| Index | Fields | Type | Purpose |
|-------|--------|------|---------|
| `_id` | `_id` | Primary (default) | Document lookup by ID |
| `idx_start_date` | `startDate: -1` | Descending | Reverse chronological ordering for timeline and resume |
| `idx_skills` | `skills: 1` | Multikey | Find jobs by skill ID for correlation queries |
| `idx_resume` | `includeOnResume: 1, startDate: -1` | Compound | Filter and sort jobs for PDF resume generation |

### Field Notes

- `endDate`: Null for current/ongoing positions. Frontend displays "Present" or "Now". PDF displays "Present".
- `isEducation`: Boolean flag distinguishing education from employment. Used to separate entries in the PDF resume and to apply visual indicators on the timeline.
- `includeOnResume`: Boolean flag controlling whether the job appears in the generated PDF resume. All jobs appear on the timeline regardless of this flag.
- `skills[]`: Array of skill ID strings referencing `skill_groups.skills[].id`. The API layer resolves these to full skill objects when returning job detail responses.
- `longDescription`: Markdown-formatted text. Rendered with `react-markdown` on the frontend and converted to styled PDF text on the backend.
- `shortDescription`: Plain text used for the timeline entry preview. No markdown.

---

## Cross-Collection Relationships

```
skill_groups.skills[].id  <-->  jobs.skills[]
         (embedded)                (references)

Skill -> Jobs:  Given skill.id, query: db.jobs.find({skills: skillId})
Job -> Skills:  Given job.skills[], lookup: iterate skill_groups to find matching skill objects
```

### Correlation Resolution Strategy

**Skill detail with related jobs (GET /api/skills/{groupId})**:
1. Fetch the skill group by ID from `skill_groups`.
2. Extract all skill IDs from the group's `skills[]` array.
3. Query `jobs` collection: `{skills: {$in: [skillId1, skillId2, ...]}}`.
4. For each skill in the group, filter the jobs that reference that specific skill ID.
5. Return the skill group with each skill annotated with its related jobs.

**Job detail with related skills (GET /api/jobs/{jobId})**:
1. Fetch the job by ID from `jobs`.
2. Extract the `skills[]` array of skill IDs.
3. Query `skill_groups` to find all groups containing any of these skill IDs.
4. Build the resolved skill objects from the matching embedded skills.
5. Return the job with fully populated skill objects.

---

## Data Migration Notes

The MongoDB backup at `/Users/simonrowe/backups` contains Strapi-format documents in three separate collections (`skills_groups`, `skills`, `jobs`). Migration requires:

1. **Merge `skills` into `skill_groups`**: For each `skill_groups` document, replace the `skills` ObjectId array with embedded skill subdocuments fetched from the `skills` collection.
2. **Convert `jobs.skills`**: Replace ObjectId references with string skill IDs.
3. **Add missing fields**: Strapi documents use `education` (boolean) and `order` (integer) field names. Rename to `isEducation` and `displayOrder` respectively.
4. **Image references**: Strapi stores image ObjectIds referencing the `upload_file` collection. Migration must resolve these to inline image objects with URL paths.
5. **Preserve IDs**: Original ObjectId strings become the skill `id` values used for cross-referencing. This ensures existing relationships remain intact.

### Data Volumes (from backup)

| Collection | Documents | Avg Document Size |
|------------|-----------|-------------------|
| `skill_groups` | 9 | ~2-4 KB (with embedded skills: ~5-15 KB) |
| `jobs` | 9 | ~2-5 KB |
| Total skills | 71 | Embedded in 9 skill_groups |
