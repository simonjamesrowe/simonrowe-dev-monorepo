# Data Model: Site Navigation, Landing Hero and Portfolio

## `home_page` (new, singleton)

One document with the fixed `_id` `"home"`.

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | string | always `"home"` |
| `headlineLine1` | string | required, 1–60 chars |
| `headlineLine2` | string | required, 1–60 chars (rendered accented) |
| `lede` | string | optional, ≤ 240 chars (hidden on phones) |
| `primaryCta.label` | string | required, 1–32 chars |
| `primaryCta.href` | string | required; site path (`/…`, not `//`) or absolute `https:` URL |
| `secondaryCta.label` | string | optional, ≤ 32 chars; if present, `href` required |
| `secondaryCta.href` | string | same rule as `primaryCta.href` |
| `showTourLink` | boolean | default `true` |
| `tourLinkLabel` | string | 1–32 chars, default "Take a tour" |
| `askPill.lead` | string | optional, ≤ 40 chars |
| `askPill.label` | string | required, 1–40 chars |
| `askPill.buttonLabel` | string | required, 1–20 chars |
| `updatedAt` | instant | set on save |

The name, title, location and both background images are read from `Profile` and are not stored here.

**Seed**: the defaults listed in the spec's Assumptions, written by the change unit only if `_id: "home"` is
absent.

## `portfolio_projects` (new)

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId string | |
| `slug` | string | required, `^[a-z0-9]+(?:-[a-z0-9]+)*$`, ≤ 60 chars, **unique index** |
| `name` | string | required, 1–60 chars |
| `tagline` | string | required, 1–140 chars |
| `description` | string (markdown) | optional, ≤ 20,000 chars |
| `status` | enum | `LIVE` \| `BETA` \| `IN_DEVELOPMENT` \| `COMING_SOON` |
| `displayOrder` | int | ≥ 0; ties broken by `name` |
| `published` | boolean | default `false` (seeded rows `true`) |
| `image` | ImageAsset | optional, from the Media Library |
| `liveUrl` | string | optional, absolute `https:` URL |
| `accentHue` | int | 0–359, default 212 |
| `createdAt`, `updatedAt` | instant | |

**Indexes** (Mongock): `{slug: 1}` unique (`idx_portfolio_slug`), and `{published: 1, displayOrder: 1}`
(`idx_portfolio_published_order`).

**Public projection**:
- `COMING_SOON`: `{slug, name, tagline, status, accentHue, displayOrder}`.
- Any other status: adds `description`, `image`, `liveUrl`.

**Seed** (published, `COMING_SOON`), inserted only for slugs that are absent:

| order | slug | name | hue | tagline |
| --- | --- | --- | --- | --- |
| 0 | `software-factory` | Software Factory | 212 | An autonomous loop that reviews, deploys and watches this site. |
| 1 | `term-time` | Term Time | 152 | A school assistant for parents, grounded in what the school publishes. |
| 2 | `co-parents` | Co-Parents | 336 | Shared family admin for parents across two homes. |
| 3 | `clinicians-veil` | Clinician's Veil | 266 | Details soon. |

## Backup and restore

Both collections are added to `BackupService.BACKUP_COLLECTIONS` and
`RestoreService.IMPORT_ORDER_INDEPENDENT`. After import, restore calls the change unit's `createIndexes`, so the
unique slug index comes back.
