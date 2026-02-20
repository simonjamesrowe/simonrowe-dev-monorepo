# Data Model: Profile & Homepage

**Feature**: 002-profile-homepage
**Date**: 2026-02-21
**Storage**: MongoDB

## Collections

### `profiles`

Stores the site owner's professional profile. In practice, this collection contains exactly one document.

**Source**: Existing MongoDB backup at `/Users/simonrowe/backups/strapi-backup-20251116_170434/mongodb/strapi/profiles.bson`

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `_id` | `ObjectId` | Yes (auto) | MongoDB document ID | `ObjectId("5e4925127b93ca001dfd520f")` |
| `name` | `String` | Yes | Full display name | `"Simon Rowe"` |
| `firstName` | `String` | Yes | First name (derived from `name` or stored separately) | `"Simon"` |
| `lastName` | `String` | Yes | Last name (derived from `name` or stored separately) | `"Rowe"` |
| `title` | `String` | Yes | Professional title | `"Engineering Leader"` |
| `headline` | `String` | Yes | Headline message displayed prominently | `"PASSIONATE ABOUT AI NATIVE DEV..."` |
| `description` | `String` | Yes | About section content in Markdown format | `"I am driven to achieve..."` |
| `profileImage` | `ImageRef` | Yes | Profile photograph reference | See Image subdocument |
| `sidebarImage` | `ImageRef` | Yes | Sidebar navigation avatar reference | See Image subdocument |
| `backgroundImage` | `ImageRef` | Yes | Desktop hero background image reference | See Image subdocument |
| `mobileBackgroundImage` | `ImageRef` | Yes | Mobile hero background image reference | See Image subdocument |
| `location` | `String` | Yes | Geographic location | `"London"` |
| `phoneNumber` | `String` | Yes | Contact phone number | `"+447909083522"` |
| `primaryEmail` | `String` | Yes | Primary contact email | `"simon.rowe@gmail.com"` |
| `secondaryEmail` | `String` | No | Secondary contact email (may be empty) | `""` |
| `cvUrl` | `String` | No | URL or path to downloadable CV/resume | `"/api/resume"` |
| `createdAt` | `Date` | Yes (auto) | Document creation timestamp | `ISODate("2020-02-16T...")` |
| `updatedAt` | `Date` | Yes (auto) | Document last-modified timestamp | `ISODate("2024-11-17T...")` |

**Notes**:
- The existing backup stores `name` as a single field (e.g., `" Simon Rowe"` with leading space). The new model adds explicit `firstName` and `lastName` fields. Migration must trim whitespace from the legacy `name` field.
- Image references in the legacy Strapi backup are ObjectId references to an `upload_file` collection. The new model embeds image metadata directly (see Image subdocument below) or references a managed file storage path.
- The `cv` field in legacy data is an ObjectId reference to an uploaded file. The new model stores `cvUrl` as a direct URL string, since CV generation is owned by Spec 004.

### Image Subdocument

Embedded within Profile document for image fields. Not a separate collection.

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `url` | `String` | Yes | Primary image URL (relative or absolute) | `"/uploads/profile_abc123.jpg"` |
| `name` | `String` | No | Original filename | `"profile-photo.jpg"` |
| `width` | `Integer` | No | Image width in pixels | `400` |
| `height` | `Integer` | No | Image height in pixels | `400` |
| `mime` | `String` | No | MIME type | `"image/jpeg"` |
| `formats` | `ImageFormats` | No | Responsive image variants | See below |

### ImageFormats Subdocument

Embedded within Image subdocument. Provides pre-generated responsive variants.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `thumbnail` | `Image` | No | Thumbnail variant (typically ~150px) |
| `small` | `Image` | No | Small variant (typically ~500px) |
| `medium` | `Image` | No | Medium variant (typically ~750px) |
| `large` | `Image` | No | Large variant (typically ~1000px) |

---

### `social_medias`

Stores social media profile links. Separate collection from profiles.

**Source**: Existing MongoDB backup at `/Users/simonrowe/backups/strapi-backup-20251116_170434/mongodb/strapi/social_medias.bson`

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `_id` | `ObjectId` | Yes (auto) | MongoDB document ID | `ObjectId("5f63625a5ee4c9001d2b9672")` |
| `type` | `String` | Yes | Platform identifier. Enum: `github`, `linkedin`, `twitter` | `"github"` |
| `name` | `String` | Yes | Display name or description | `"Personal Github Account"` |
| `link` | `String` | Yes | Full URL to external profile | `"https://github.com/simonrowe"` |
| `includeOnResume` | `Boolean` | No | Whether to include this link on the generated resume (used by Spec 004) | `true` |
| `createdAt` | `Date` | Yes (auto) | Document creation timestamp | `ISODate("2020-09-17T...")` |
| `updatedAt` | `Date` | Yes (auto) | Document last-modified timestamp | `ISODate("2021-04-08T...")` |

**Existing data** (from backup):

| type | name | link | includeOnResume |
|------|------|------|-----------------|
| `github` | Personal Github Account | https://github.com/simonrowe | `true` |
| `github` | Public org for all repos that make up www.simonjamesrowe.com | https://github.com/simonjamesrowe | `true` |
| `linkedin` | Linkedin | https://www.linkedin.com/in/simon-rowe-2a94ab1/ | `false` |
| `twitter` | Simon Rowe - Twitter | https://twitter.com/rowe_simon | `null` (field absent) |

**Notes**:
- The `type` field is a simple string enum. New platform types (e.g., `mastodon`, `bluesky`) can be added by extending the enum without schema changes.
- The `includeOnResume` field is optional; when absent, it defaults to `false`.
- The `link` field in the legacy data maps to `url` in the API response DTO for consistency with REST naming conventions.

---

## Relationships

```
profiles (1) ←── fetched together ──→ social_medias (many)
```

- **Profile to SocialMediaLinks**: One-to-many relationship. There is exactly one profile and multiple social media links.
- **Storage strategy**: Separate collections (not embedded). Social media links are stored in their own collection because:
  1. They are independently managed (CRUD operations in Spec 007 - Content Management).
  2. The `includeOnResume` field is consumed by Spec 004 independently of the profile.
  3. The existing data already uses separate collections, minimizing migration effort.
- **API aggregation**: The `GET /api/profile` endpoint joins these at the service layer, returning social media links embedded in the profile response.

---

## Java Entity Mapping

### Profile.java

```java
@Document(collection = "profiles")
public record Profile(
    @Id String id,
    String name,
    String title,
    String headline,
    String description,
    Image profileImage,
    Image sidebarImage,
    Image backgroundImage,
    Image mobileBackgroundImage,
    String location,
    String phoneNumber,
    String primaryEmail,
    String secondaryEmail,
    String cvUrl,
    Instant createdAt,
    Instant updatedAt
) {}
```

### SocialMediaLink.java

```java
@Document(collection = "social_medias")
public record SocialMediaLink(
    @Id String id,
    String type,
    String name,
    String link,
    Boolean includeOnResume,
    Instant createdAt,
    Instant updatedAt
) {}
```

### Image.java (embedded)

```java
public record Image(
    String url,
    String name,
    Integer width,
    Integer height,
    String mime,
    ImageFormats formats
) {}
```

### ImageFormats.java (embedded)

```java
public record ImageFormats(
    Image thumbnail,
    Image small,
    Image medium,
    Image large
) {}
```

---

## Indexes

### profiles collection
- `_id` (default) -- Only one document expected; no additional indexes required.

### social_medias collection
- `_id` (default) -- Small collection (3-5 documents); no additional indexes required.
- Future consideration: `type` index if filtering by platform is needed.

---

## Migration Notes

- The existing Strapi-managed MongoDB data uses ObjectId references for images (`backgroundImage`, `profileImage`, etc.) that point to the `upload_file` collection. The new model embeds image metadata directly within the profile document. A one-time migration script will:
  1. Resolve ObjectId references to `upload_file` documents.
  2. Embed the resolved image data (url, name, width, height, mime, formats) directly into the profile document.
  3. Trim leading/trailing whitespace from the `name` field.
  4. Extract `firstName` and `lastName` from the `name` field.
  5. Convert the `cv` ObjectId reference to a `cvUrl` string.
- The `social_medias` collection requires minimal migration: field names match, and the schema is already compatible. The `link` field is renamed to `url` only at the API response DTO level, not in the MongoDB document.
