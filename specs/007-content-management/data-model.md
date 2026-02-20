# Data Model: Content Management System

**Feature**: 007-content-management
**Date**: 2026-02-21
**Storage**: MongoDB

## Overview

All content types are stored as MongoDB documents. Cross-references use MongoDB `ObjectId` strings stored in arrays or single fields. The data model is designed to be compatible with the existing Strapi backup data while adopting cleaner field naming conventions.

---

## Documents

### Blog

**Collection**: `blogs`

Represents a published or draft article with Markdown content.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `title` | String | Yes | -- | Blog post title |
| `shortDescription` | String | Yes | -- | Summary for listing cards and search results |
| `content` | String | Yes | -- | Full article body in Markdown format |
| `published` | Boolean | Yes | `false` | Publication state; `false` = draft (admin-only) |
| `featuredImage` | String | No | `null` | MediaAsset ID for the featured/hero image |
| `tags` | String[] | No | `[]` | Array of Tag IDs |
| `skills` | String[] | No | `[]` | Array of Skill IDs |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ published: 1, createdAt: -1 }` -- public listing query (published blogs sorted by date)
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)
- `{ tags: 1 }` -- filter by tag

**Validation rules**:
- `title` must be non-empty and max 200 characters
- `shortDescription` must be non-empty and max 500 characters
- `content` must be non-empty when `published` is `true`
- `published` blog requires at least one tag

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "title": "Creating a rich web app that can be hosted from home",
  "shortDescription": "A quick introduction into the various components...",
  "content": "I'm the kind of person that learns by doing...",
  "published": true,
  "featuredImage": "abc123def456",
  "tags": ["tag_id_1", "tag_id_2"],
  "skills": ["skill_id_1", "skill_id_2"],
  "createdAt": "2020-07-05T18:02:46.731Z",
  "updatedAt": "2021-03-01T11:29:14.969Z",
  "legacyId": "5f0215c69d8081001fd38fa1"
}
```

---

### Job

**Collection**: `jobs`

Represents an employment position or educational achievement.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `title` | String | Yes | -- | Job title or degree name |
| `company` | String | Yes | -- | Company or institution name |
| `companyUrl` | String | No | `null` | Company website URL |
| `companyImage` | String | No | `null` | MediaAsset ID for company logo |
| `startDate` | String | Yes | -- | Start date in ISO format (YYYY-MM-DD) |
| `endDate` | String | No | `null` | End date in ISO format; `null` = current/ongoing |
| `location` | String | No | `null` | Geographic location |
| `shortDescription` | String | Yes | -- | Brief description for timeline display |
| `longDescription` | String | No | `null` | Full description in Markdown format |
| `education` | Boolean | Yes | `false` | `true` for educational achievements |
| `includeOnResume` | Boolean | Yes | `true` | Whether to include in PDF resume export |
| `skills` | String[] | No | `[]` | Array of Skill IDs |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ startDate: -1 }` -- chronological ordering
- `{ education: 1 }` -- filter education vs employment
- `{ includeOnResume: 1 }` -- resume generation filter
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `title` must be non-empty and max 200 characters
- `company` must be non-empty and max 200 characters
- `startDate` must be a valid ISO date
- `endDate` must be after `startDate` when provided
- `shortDescription` must be non-empty

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "title": "Software Engineering Lead",
  "company": "Upp Technologies",
  "companyUrl": "https://upp.ai",
  "companyImage": "media_asset_id",
  "startDate": "2019-04-15",
  "endDate": "2020-05-01",
  "location": "London",
  "shortDescription": "Hands-on lead engineer in a small cross-functional team.",
  "longDescription": "Lead Engineer working on all verticals...",
  "education": false,
  "includeOnResume": true,
  "skills": ["skill_id_1", "skill_id_2"],
  "createdAt": "2020-02-24T06:42:23.147Z",
  "updatedAt": "2021-02-26T22:37:22.370Z",
  "legacyId": "5e53704f11c196001d06f914"
}
```

---

### Skill

**Collection**: `skills`

Represents an individual technical or professional competency.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `name` | String | Yes | -- | Skill name (e.g., "Java 8", "Kubernetes") |
| `rating` | Double | Yes | -- | Proficiency rating on 0-10 numeric scale |
| `description` | String | No | `null` | Skill description in Markdown (supports lists, formatting) |
| `image` | String | No | `null` | MediaAsset ID for skill icon/image |
| `order` | Integer | Yes | 0 | Display order within parent skill group |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ order: 1 }` -- display ordering
- `{ name: 1 }` -- unique skill names (unique)
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `name` must be non-empty and max 100 characters
- `rating` must be between 0.0 and 10.0 (inclusive)
- `order` must be a non-negative integer

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "name": "Java 8",
  "rating": 9.0,
  "description": "- Generics\n- Optional\n- Lambdas\n- Streams\n- Date & Time",
  "image": "media_asset_id",
  "order": 1,
  "createdAt": "2020-09-17T12:49:03.944Z",
  "updatedAt": "2020-09-21T20:44:17.441Z",
  "legacyId": "5f635b3f5ee4c9001d2b962f"
}
```

---

### SkillGroup

**Collection**: `skill_groups`

Represents a category of related skills.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `name` | String | Yes | -- | Group name (e.g., "Java / Kotlin") |
| `rating` | Double | No | `null` | Aggregated proficiency rating for the group |
| `description` | String | No | `null` | Category description |
| `image` | String | No | `null` | MediaAsset ID for group representative image |
| `order` | Integer | Yes | 0 | Display order among skill groups |
| `skills` | String[] | No | `[]` | Array of Skill IDs in display order |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ order: 1 }` -- display ordering
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `name` must be non-empty and max 100 characters
- `rating` must be between 0.0 and 10.0 when provided
- `order` must be a non-negative integer
- `skills` array entries must reference valid Skill IDs

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "name": "Java / Kotlin",
  "rating": 9.2,
  "description": "The JVM was initially designed to support only...",
  "image": "media_asset_id",
  "order": 1,
  "skills": ["skill_id_1", "skill_id_2", "skill_id_3"],
  "createdAt": "2020-09-17T12:42:45.940Z",
  "updatedAt": "2020-09-30T06:24:31.252Z",
  "legacyId": "5f6359c55ee4c9001d2b9627"
}
```

---

### Profile

**Collection**: `profiles`

Represents the site owner's personal information. **Single instance** -- only one document exists.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `name` | String | Yes | -- | Full name |
| `title` | String | Yes | -- | Professional title (e.g., "Engineering Leader") |
| `headline` | String | No | `null` | Headline message displayed on homepage |
| `description` | String | No | `null` | Professional bio in Markdown format |
| `location` | String | No | `null` | Geographic location |
| `phoneNumber` | String | No | `null` | Contact phone number |
| `primaryEmail` | String | No | `null` | Primary email address |
| `secondaryEmail` | String | No | `null` | Secondary email address |
| `profileImage` | String | No | `null` | MediaAsset ID for profile photograph |
| `sidebarImage` | String | No | `null` | MediaAsset ID for sidebar navigation image |
| `backgroundImage` | String | No | `null` | MediaAsset ID for homepage background |
| `mobileBackgroundImage` | String | No | `null` | MediaAsset ID for mobile background |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |

**Indexes**: None required (single document).

**Validation rules**:
- `name` must be non-empty and max 100 characters
- `title` must be non-empty and max 100 characters
- `primaryEmail` must be a valid email format when provided

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "name": "Simon Rowe",
  "title": "Engineering Leader",
  "headline": "PASSIONATE ABOUT AI NATIVE DEV, CLOUD NATIVE APPLICATIONS...",
  "description": "I am driven to achieve real business value...",
  "location": "London",
  "phoneNumber": "+447909083522",
  "primaryEmail": "simon.rowe@gmail.com",
  "secondaryEmail": "",
  "profileImage": "media_asset_id_1",
  "sidebarImage": "media_asset_id_2",
  "backgroundImage": "media_asset_id_3",
  "mobileBackgroundImage": "media_asset_id_4",
  "createdAt": "2020-02-16T11:18:42.697Z",
  "updatedAt": "2025-11-16T16:35:01.249Z"
}
```

---

### SocialMediaLink

**Collection**: `social_media_links`

Represents an external social profile.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `type` | String | Yes | -- | Platform type (e.g., "github", "linkedin", "twitter") |
| `link` | String | Yes | -- | Full URL to the external profile |
| `name` | String | Yes | -- | Display name for the link |
| `includeOnResume` | Boolean | Yes | `false` | Whether to include in PDF resume export |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ type: 1 }` -- lookup by platform type
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `type` must be one of: `github`, `linkedin`, `twitter`, `website`, `stackoverflow`, `medium`, `other`
- `link` must be a valid URL
- `name` must be non-empty and max 100 characters

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "type": "github",
  "link": "https://github.com/simonrowe",
  "name": "Personal Github Account",
  "includeOnResume": true,
  "createdAt": "2020-09-17T13:19:22.271Z",
  "updatedAt": "2021-04-08T14:54:47.706Z",
  "legacyId": "5f63625a5ee4c9001d2b9672"
}
```

---

### Tag

**Collection**: `tags`

Represents a categorical label for blog posts.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `name` | String | Yes | -- | Tag name (e.g., "Kubernetes", "Spring Boot") |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ name: 1 }` -- unique tag names (unique, case-insensitive collation)
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `name` must be non-empty and max 100 characters
- `name` must be unique (case-insensitive)

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "name": "Kubernetes",
  "createdAt": "2020-02-16T15:20:07.909Z",
  "updatedAt": "2020-02-16T22:04:13.013Z",
  "legacyId": "5e495da7bc8d7d001ddbd7c5"
}
```

---

### TourStep

**Collection**: `tour_steps`

Represents a single step in the guided site tour.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `title` | String | Yes | -- | Step heading displayed in tooltip |
| `selector` | String | Yes | -- | CSS selector for the target page element |
| `description` | String | Yes | -- | Instructional text (supports Markdown formatting) |
| `titleImage` | String | No | `null` | MediaAsset ID for optional tooltip image |
| `position` | String | Yes | `"bottom"` | Tooltip position: `top`, `bottom`, `left`, `right`, `center` |
| `order` | Integer | Yes | 0 | Sequence order in the tour |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**Indexes**:
- `{ order: 1 }` -- tour sequence ordering
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `title` must be non-empty and max 200 characters
- `selector` must be non-empty and a valid CSS selector string
- `description` must be non-empty
- `position` must be one of: `top`, `bottom`, `left`, `right`, `center`
- `order` must be a non-negative integer

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "title": "Site Search",
  "selector": ".tour-search",
  "description": "This search allows you to search across blogs...",
  "titleImage": "media_asset_id",
  "position": "top",
  "order": 1,
  "createdAt": "2021-02-19T19:51:45.292Z",
  "updatedAt": "2021-02-19T21:56:24.254Z",
  "legacyId": "603016d15b654b001e0f0275"
}
```

---

### MediaAsset

**Collection**: `media_assets`

Represents an uploaded image with generated size variants.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `_id` | ObjectId | auto | auto | Primary key |
| `fileName` | String | Yes | -- | Original file name |
| `mimeType` | String | Yes | -- | MIME type (e.g., "image/jpeg", "image/png") |
| `fileSize` | Long | Yes | -- | Original file size in bytes |
| `originalPath` | String | Yes | -- | Relative path to original file on disk |
| `variants` | Object | No | `{}` | Map of variant name to variant metadata |
| `variants.thumbnail` | VariantInfo | No | -- | Thumbnail variant (150px max) |
| `variants.small` | VariantInfo | No | -- | Small variant (300px max) |
| `variants.medium` | VariantInfo | No | -- | Medium variant (600px max) |
| `variants.large` | VariantInfo | No | -- | Large variant (1200px max) |
| `createdAt` | DateTime | auto | now | Creation timestamp |
| `updatedAt` | DateTime | auto | now | Last modification timestamp |
| `legacyId` | String | No | `null` | Original Strapi ObjectId for migration idempotency |

**VariantInfo sub-document**:

| Field | Type | Description |
|-------|------|-------------|
| `path` | String | Relative path to variant file on disk |
| `width` | Integer | Actual width after resizing |
| `height` | Integer | Actual height after resizing |
| `fileSize` | Long | Variant file size in bytes |

**Indexes**:
- `{ fileName: 1 }` -- lookup by name
- `{ mimeType: 1 }` -- filter by type
- `{ legacyId: 1 }` -- migration lookup (unique, sparse)

**Validation rules**:
- `fileName` must be non-empty
- `mimeType` must be one of: `image/jpeg`, `image/png`, `image/gif`, `image/webp`, `image/svg+xml`
- `fileSize` must be positive and not exceed 10MB (10,485,760 bytes)

**Example document**:

```json
{
  "_id": "ObjectId('...')",
  "fileName": "max-nelson-taiuG8CPKAQ-unsplash.jpg",
  "mimeType": "image/jpeg",
  "fileSize": 3955180,
  "originalPath": "media/abc123/original.jpg",
  "variants": {
    "thumbnail": {
      "path": "media/abc123/thumbnail.jpg",
      "width": 150,
      "height": 100,
      "fileSize": 12340
    },
    "small": {
      "path": "media/abc123/small.jpg",
      "width": 300,
      "height": 200,
      "fileSize": 45670
    },
    "medium": {
      "path": "media/abc123/medium.jpg",
      "width": 600,
      "height": 400,
      "fileSize": 123450
    },
    "large": {
      "path": "media/abc123/large.jpg",
      "width": 1200,
      "height": 800,
      "fileSize": 456780
    }
  },
  "createdAt": "2020-02-16T11:45:59.309Z",
  "updatedAt": "2020-02-16T13:35:21.449Z",
  "legacyId": "5e492b7726f151001c239c5d"
}
```

---

## Entity Relationship Diagram

```
Blog ──────── many-to-many ──────── Tag
  │
  └── many-to-many ──── Skill ◄──── SkillGroup (via skills[])
                           │
Job ── many-to-many ───────┘

Profile (singleton)
SocialMediaLink (independent)
TourStep (independent, ordered)

Blog.featuredImage ────────► MediaAsset
Job.companyImage ──────────► MediaAsset
Skill.image ───────────────► MediaAsset
SkillGroup.image ──────────► MediaAsset
Profile.*Image ────────────► MediaAsset
TourStep.titleImage ───────► MediaAsset
```

## Migration Field Mapping

| Strapi Collection | Strapi Field | New Collection | New Field | Notes |
|-------------------|-------------|----------------|-----------|-------|
| `blogs` | `_id` | `blogs` | `legacyId` | String representation |
| `blogs` | `image` | `blogs` | `featuredImage` | Mapped via media migration |
| `blogs` | `tags[]` | `blogs` | `tags[]` | ObjectId array, re-mapped |
| `blogs` | `content` | `blogs` | `content` | Direct copy (already Markdown) |
| `jobs` | `companyImage` | `jobs` | `companyImage` | Mapped via media migration |
| `jobs` | `skills[]` | `jobs` | `skills[]` | ObjectId array, re-mapped |
| `jobs` | `longDescription` | `jobs` | `longDescription` | Direct copy (already Markdown) |
| `skills` | `image` | `skills` | `image` | Mapped via media migration |
| `skills_groups` | `skills[]` | `skill_groups` | `skills[]` | ObjectId array, re-mapped |
| `skills_groups` | `image` | `skill_groups` | `image` | Mapped via media migration |
| `profiles` | `backgroundImage` | `profiles` | `backgroundImage` | Mapped via media migration |
| `profiles` | `profileImage` | `profiles` | `profileImage` | Mapped via media migration |
| `profiles` | `sidebarImage` | `profiles` | `sidebarImage` | Mapped via media migration |
| `profiles` | `cv` | -- | -- | Not migrated (resume is generated dynamically) |
| `social_medias` | `type` | `social_media_links` | `type` | Direct copy |
| `social_medias` | `link` | `social_media_links` | `link` | Direct copy |
| `tour-steps` | `titleImage` | `tour_steps` | `titleImage` | Mapped via media migration |
| `upload_file` | `url` | `media_assets` | `originalPath` | Path re-mapped to new directory |
