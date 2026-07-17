# Contract: Widget Payload id additions

Two payloads gain an `id`; the model-facing tool returns must expose the same ids.

## SkillsWidgetPayload.Group
```java
// backend: chat/SkillsWidgetPayload.java
record Group(String id, String name, List<Skill> skills) {}   // id ADDED (first field)
```
```ts
// frontend: chatTypes.ts
groups: Array<{ id?: string; name: string; skills: Array<{ name: string; rating?: number | null }> }>
```
- `ProfileMcpTools.toSkillsPayload` MUST map `SkillGroupSummaryDto.id` → `Group.id`.

## EmploymentWidgetPayload.Job
```java
// backend: chat/EmploymentWidgetPayload.java
record Job(String id, String company, String title, String start,
           String end, String summary, List<String> skills) {}   // id ADDED (first field)
```
```ts
// frontend: chatTypes.ts
jobs: Array<{ id?: string; company: string; title: string; start?: string;
              end?: string; summary?: string; skills?: string[] }>
```
- `ProfileMcpTools.toEmploymentPayload` MUST map `JobSummaryDto.id` → `Job.id`.

## Model-facing tool returns (getSkills / getJobs)
- No-query branch already returns id-bearing `SkillGroupSummaryDto` / `JobSummaryDto` — keep.
- Ensure ids remain visible to the model in the returned structure so it can build
  `/experience?job=<id>` / `?skillGroup=<id>` links; the prompt forbids guessing ids.

## Acceptance (FR-017)
- Backend Mockito test asserts the captured `SkillsWidgetPayload` groups carry the source
  ids and `EmploymentWidgetPayload` jobs carry the source ids.
- Frontend widget rendering unaffected (id is optional/decorative for the card; used for
  deep links in prose).

## Unchanged payloads (already carry ids/urls)
`BlogWidgetPayload.Post` (id,url,imageUrl), `NewsWidgetPayload.Article`
(id,originalUrl,imageUrl), `EventWidgetPayload.Event` (id,originalUrl),
`CodeWidgetPayload.Example` (id).
