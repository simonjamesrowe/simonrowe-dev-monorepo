# Data Model: Interactive Tour

**Feature**: 008-interactive-tour
**Date**: 2026-02-21
**Phase**: 1 (Design)

## MongoDB Collection: `tourSteps`

### Document Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `_id` | ObjectId | Yes (auto) | MongoDB-generated unique identifier |
| `order` | Integer | Yes | Display sequence number (1-based). Determines the order in which steps are presented during the tour. Must be unique across all tour steps. |
| `targetSelector` | String | Yes | CSS selector identifying the DOM element to highlight (e.g., `.tour-search`, `#about-section`, `[data-tour="skills"]`). The frontend uses `document.querySelector()` to resolve this. |
| `title` | String | Yes | Short heading displayed at the top of the tooltip (e.g., "Search the Site", "About Me"). Maximum recommended length: 60 characters. |
| `titleImage` | String | No | URL path to an image displayed alongside the title in the tooltip header. Relative to the backend API base URL (e.g., `/images/tour/search-icon.png`). When null or absent, the tooltip renders without an image. |
| `description` | String | Yes | Instructional text displayed in the tooltip body. Supports Markdown formatting: bold (`**text**`), italic (`*text*`), unordered lists (`- item`), ordered lists (`1. item`), paragraphs (blank line separation), and inline links (`[text](url)`). Rendered by react-markdown on the frontend. |
| `position` | String | Yes | Tooltip placement relative to the highlighted element. Valid values: `top`, `bottom`, `left`, `right`, `center`. Determines where the tooltip appears adjacent to the spotlight cutout. Default: `bottom`. |

### Example Document

```json
{
  "_id": { "$oid": "665a1b2c3d4e5f6a7b8c9d01" },
  "order": 1,
  "targetSelector": ".homepage-banner",
  "title": "Welcome to My Site",
  "titleImage": "/images/tour/welcome.png",
  "description": "This is the **homepage banner** where you can see:\n\n- My professional title\n- A brief introduction\n- Quick navigation links\n\nClick *Next* to continue the tour.",
  "position": "bottom"
}
```

```json
{
  "_id": { "$oid": "665a1b2c3d4e5f6a7b8c9d02" },
  "order": 2,
  "targetSelector": "#about-section",
  "title": "About Me",
  "titleImage": null,
  "description": "Here you can learn about my **professional background**, experience, and interests.\n\nScroll down to read the full description.",
  "position": "top"
}
```

```json
{
  "_id": { "$oid": "665a1b2c3d4e5f6a7b8c9d03" },
  "order": 3,
  "targetSelector": ".tour-search",
  "title": "Site Search",
  "titleImage": "/images/tour/search-icon.png",
  "description": "Use the **search bar** to find content across the entire site.\n\nWatch as we demonstrate a search for you -- the results update as you type!",
  "position": "bottom"
}
```

```json
{
  "_id": { "$oid": "665a1b2c3d4e5f6a7b8c9d04" },
  "order": 4,
  "targetSelector": "[data-tour='skills']",
  "title": "Skills & Technologies",
  "titleImage": null,
  "description": "Browse through my **technical skills** and proficiency levels.\n\n1. Click on any skill to see related blog posts\n2. Skills are grouped by category\n3. Each skill shows a proficiency rating",
  "position": "right"
}
```

```json
{
  "_id": { "$oid": "665a1b2c3d4e5f6a7b8c9d05" },
  "order": 5,
  "targetSelector": "[data-tour='blog']",
  "title": "Blog Posts",
  "titleImage": null,
  "description": "Read my latest **technical articles** covering topics like:\n\n- Spring Boot and microservices\n- Kubernetes and DevOps\n- Software architecture patterns",
  "position": "left"
}
```

```json
{
  "_id": { "$oid": "665a1b2c3d4e5f6a7b8c9d06" },
  "order": 6,
  "targetSelector": "[data-tour='contact']",
  "title": "Get in Touch",
  "titleImage": null,
  "description": "Have a question or want to connect? Use the **contact form** to send me a message directly.\n\nThanks for taking the tour!",
  "position": "top"
}
```

### Java Entity

```java
package com.simonrowe.tour;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "tourSteps")
public record TourStep(
    @Id String id,
    @Indexed(unique = true) int order,
    String targetSelector,
    String title,
    String titleImage,
    String description,
    String position
) {}
```

### TypeScript Interface

```typescript
export interface TourStep {
  id: string;
  order: number;
  targetSelector: string;
  title: string;
  titleImage: string | null;
  description: string;
  position: 'top' | 'bottom' | 'left' | 'right' | 'center';
}
```

### Indexes

| Index | Fields | Type | Purpose |
|-------|--------|------|---------|
| Primary | `_id` | Unique (auto) | MongoDB default document identifier |
| Order | `order` | Unique, Ascending | Enforces step ordering uniqueness; supports `ORDER BY order ASC` query pattern for the API endpoint |

### Field Constraints

| Field | Constraint | Enforcement |
|-------|-----------|-------------|
| `order` | Unique across collection | MongoDB unique index + backend validation |
| `order` | Positive integer (>= 1) | Backend validation in TourService |
| `targetSelector` | Non-empty string | Backend validation; must be a valid CSS selector |
| `title` | Non-empty string, max 100 chars | Backend validation |
| `description` | Non-empty string | Backend validation; no max length enforced (Markdown content varies) |
| `position` | One of: `top`, `bottom`, `left`, `right`, `center` | Backend enum validation |
| `titleImage` | Valid URL path or null | Backend validation when present; null/absent when not configured |

### Migration from Legacy Data

The legacy simonrowe.dev application stored tour steps in Strapi CMS. The data model mapping is:

| Legacy (Strapi) | New (MongoDB) | Notes |
|----------------|---------------|-------|
| `title` | `title` | Direct mapping |
| `selector` | `targetSelector` | Renamed for clarity |
| `description` | `description` | Direct mapping; already Markdown |
| `titleImage` (nested Strapi media) | `titleImage` (URL string) | Flattened from Strapi media object to simple URL path |
| `position` | `position` | Direct mapping |
| `order` | `order` | Direct mapping |

The legacy data can be migrated by exporting tour steps from the Strapi backup at `/Users/simonrowe/backups` and transforming the documents to match the new schema. The `titleImage` field requires extracting the thumbnail URL from the Strapi media format structure (`formats.thumbnail.url`) and storing it as a plain string.
