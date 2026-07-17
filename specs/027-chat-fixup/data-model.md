# Phase 1 Data Model: Chat Drawer Fix-Up

This feature changes rendering behaviour and adds ids to two existing widget payloads. There
are no new persisted entities and no MongoDB schema changes — the ids already exist on the
source DTOs and are merely surfaced. Entities below are the in-flight / in-memory shapes the
feature touches.

## 1. ChatBlock (frontend — `chatTypes.ts`)

Discriminated union rendered in order within an assistant message.

| Variant | Fields | Change |
|---------|--------|--------|
| text | `kind:'text'`, `content: string` | On `STREAM_END`, content reconciled to server `fullResponse` |
| tool | `kind:'tool'`, `label: string`, `status:'running'\|'done'` | No shape change; `done` now renders `label` (not "Used 1 tool") |
| widget | `kind:'widget'`, `widgetKind: string`, `payload: unknown` | No shape change; source of per-message allowlist |

**Rule (reconcile)**: On `STREAM_END`, text blocks are collapsed/replaced to the
authoritative `fullResponse`; tool and widget blocks are preserved with their relative order.

## 2. ChatMessageModel (frontend — `chatTypes.ts`)

| Field | Type | Notes |
|-------|------|-------|
| role | `'user'\|'assistant'` | unchanged |
| content | `string?` | user text / fallback |
| blocks | `ChatBlock[]?` | assistant ordered blocks |
| timestamp | `string` | unchanged |
| finalized | `boolean?` | set true on STREAM_END/ERROR |

**Rule (single bubble)**: exactly one assistant `ChatMessageModel` per user prompt; no second
message created from `STREAM_END` content when blocks already exist.

## 3. ChatResponse (backend record — `chat/ChatResponse.java`; FE `ChatResponse` type)

Wire envelope over STOMP. No field changes.

| Field | Type | Used by |
|-------|------|---------|
| sessionId | string | topic routing / FE guard |
| content | string | STREAM_CHUNK text; **STREAM_END `fullResponse` (now authoritative)** |
| type | enum `STREAM_START\|STREAM_CHUNK\|STREAM_END\|TOOL_START\|TOOL_END\|WIDGET\|ERROR` | reducer dispatch |
| timestamp | string | display |
| toolLabel | string? | TOOL_START/END friendly label |
| widgetKind | string? | WIDGET dispatch |
| payload | object? | WIDGET data |

## 4. Widget payloads — id additions (backend + frontend)

Only two payloads change; the rest already carry ids/urls.

### SkillsWidgetPayload.Group  — **ADD `id`**
Backend record `chat/SkillsWidgetPayload.java`:
- before: `Group(String name, List<Skill> skills)`
- after: `Group(String id, String name, List<Skill> skills)`
- mapper `ProfileMcpTools.toSkillsPayload` maps `SkillGroupSummaryDto.id` → `Group.id`.
- Frontend `chatTypes.ts` `SkillWidgetPayload.groups[]` gains `id?: string`.

### EmploymentWidgetPayload.Job — **ADD `id`**
Backend record `chat/EmploymentWidgetPayload.java`:
- before: `Job(String company, String title, String start, String end, String summary, List<String> skills)`
- after: `Job(String id, String company, String title, String start, String end, String summary, List<String> skills)`
- mapper `ProfileMcpTools.toEmploymentPayload` maps `JobSummaryDto.id` → `Job.id`.
- Frontend `chatTypes.ts` `EmploymentWidgetPayload.jobs[]` gains `id?: string`.

### Unchanged (already carry ids/urls)
- `BlogWidgetPayload.Post`: `id`, `url`, `imageUrl` ✓
- `NewsWidgetPayload.Article`: `id`, `originalUrl`, `imageUrl` ✓
- `EventWidgetPayload.Event`: `id`, `originalUrl` (imageUrl currently null) ✓
- `CodeWidgetPayload.Example`: `id` ✓

**Rule (no fabrication)**: The model may only build a job/skill deep link when the id is
present in the tool return + widget payload; the prompt forbids guessing.

## 5. Per-message link/image allowlist (frontend — derived, `linkPolicy.ts`)

Not persisted; computed at render time from a message's widget blocks.

| Source widget | Contributes to allowlist |
|---------------|--------------------------|
| blogs | each post `url`, `imageUrl` |
| news | each article `originalUrl`, `imageUrl` |
| events | each event `originalUrl` (+ `imageUrl` if present) |
| code | each example image URL (if any) |
| profile/avatar | profile image URL |

**Rules**:
- Internal route/anchor/query patterns are always allowed by pattern (no allowlist entry).
- External `https` link allowed only if URL ∈ allowlist; else plain text.
- Non-`https` non-internal scheme → plain text.
- Image allowed if `src` ∈ allowlist OR `src` starts with uploads origin (`/uploads/` or
  `${API_BASE_URL}/uploads/`); else dropped.

## 6. Deep-link URL scheme (frontend routing)

| Target | URL | Effect |
|--------|-----|--------|
| Job drawer | `/experience?job=<jobId>` | `openJob(jobId)` on load |
| Skill-group drawer | `/experience?skillGroup=<groupId>` | `openSkillGroup(groupId)` on load |
| Section anchors | `/experience#roles`, `/experience#skills`, `/news-events#news`, `/news-events#events` | scroll to section id |
| Blog page | `/blogs/:id` | React Router navigation |

**State transitions (drawer param)**:
1. Navigate with `?job=<id>` → `openJob(id)` → drawer open, `selectedJobId=id`.
2. User closes drawer → `closeJob()` → clear `?job` from URL.
3. Stale/unknown id → drawer component renders empty/listing; no error.
(`selectedJobId` and `selectedGroupId` are mutually exclusive per existing `useDrawer`.)

## 7. Langfuse init resources (deployment — idempotent)

Provisioned once at Langfuse startup via `LANGFUSE_INIT_*`; created only if absent.

| Resource | Source value |
|----------|--------------|
| Organization | `LANGFUSE_INIT_ORG_ID` |
| Project | `LANGFUSE_INIT_PROJECT_ID` |
| Project public key | `LANGFUSE_INIT_PROJECT_PUBLIC_KEY = ${LANGFUSE_PUBLIC_KEY}` |
| Project secret key | `LANGFUSE_INIT_PROJECT_SECRET_KEY = ${LANGFUSE_SECRET_KEY}` |
| Admin membership | `LANGFUSE_INIT_USER_EMAIL` (admin@simonrowe.dev), `LANGFUSE_INIT_USER_NAME` |

**Rule (agreement by construction)**: project keys equal the Alloy exporter keys, so traces
land in the provisioned project without manual key copying.
