# Chat fix-up — design

Date: 2026-07-17
Status: Approved (pending spec review)

## Background

The "Ask me anything" chat drawer (`frontend/src/components/chat/*`, backend
`com.simonrowe.chat.*` + `com.simonrowe.mcp.ProfileMcpTools`) works but has four
visible problems, observed on a "what software development skills does he have on the
front end and the back end?" prompt:

1. **Tool activity is uninformative.** The completed tool block shows a hardcoded
   "Used 1 tool" and hides the real, friendly label behind an expander.
2. **The assistant answers twice.** Two full answers render in separate bubbles.
3. **Answer text is scrambled.** Words/links are corrupted, e.g.
   `Jasper Reports F Apache,OP` (should be "Jasper Reports, Apache FOP") and a broken
   link `[Workcover Queensland](/experience Macquarie Group,)`.
4. **Answers are plain prose.** No inline links to site content and no inline images,
   even though the model already receives canonical URLs and image URLs.

## Goals

- Show a clear, contextual label for each tool the assistant uses.
- Exactly one clean, correctly-ordered answer per user prompt.
- Let answer prose link to site content and embed images, **without** the model being
  able to fabricate URLs.
- Internal links navigate within the SPA. Items without their own page open the
  relevant drawer via the URL (item-level deep links).

## Non-goals

- No redesign of the chat visual style, widgets, or streaming transport (STOMP/WS).
- No new content pages. We reuse existing routes, sections, and drawers.
- No change to auth, rate limiting, or session limits.

---

## Section 1 — Tool activity display

**Problem:** `ToolActivityBlock.tsx` renders a `<details>` whose summary is the literal
string `"Used 1 tool"`; the real label (already sent by the backend) only appears when
expanded.

**Design:** Remove the expander. Render one line per tool block:

- `status === 'running'` → spinner + `block.label` (unchanged behaviour).
- `status === 'done'` → checkmark + `block.label` (the friendly label, not "Used 1 tool").

Multiple tools in one turn → multiple stacked labelled lines (the reducer already pushes
one block per tool).

**Backend labels** already exist in `ProfileMcpTools.java`:
`"Looking up Simon's skills"`, `"Pulling up employment history"`,
`"Fetching code examples"`, `"Searching blog posts"`, `"Searching tech news"`,
`"Finding upcoming events"`. Copy may be refined but no structural backend change is
required for this section.

**Success:** the collapsed/finished state reads e.g. "✓ Looking up Simon's skills".

---

## Section 2 — One clean answer (double-answer + scrambled text)

Both symptoms are investigated together with a **live reproduction first**
(systematic debugging), because the scramble and the duplicate bubble may share a root
cause (e.g. two generations publishing to the same `/topic/chat.{sessionId}` and
interleaving, the Spring AI stream emitting both the tool-call turn and the answer turn,
or a frontend double-send on the `initialQuery`/reconnect path in
`ChatPanel.tsx`).

Regardless of the exact root cause, two robustness changes are made:

1. **Server text is authoritative on `STREAM_END`.** The backend already accumulates the
   full answer in `ChatController.fullResponse` and sends it with `STREAM_END`. Today
   `chatStreamReducer.ts` ignores that content when text blocks already exist. Change:
   on `STREAM_END`, reconcile the message's text block(s) to the server's authoritative
   `fullResponse`, so the final rendered answer is always clean even if intermediate
   chunks arrived messy. (Tool/widget blocks are preserved; only the streamed prose text
   is reconciled.)
2. **No duplicate generations.** Ensure a single subscription per prompt on the backend
   and no double `sendMessage` on the frontend `initialQuery`/reconnect path. Once the
   real cause is fixed, remove the prompt band-aid in `ChatConfig.widgetPromptGuidance()`
   ("Do not start a new answer unless the visitor has sent a new prompt.").

**Success:** exactly one assistant bubble per user prompt; the final rendered text
matches the model's output verbatim (no transpositions, no broken markdown).

---

## Section 3 — Rich rendering in answer prose (links + images, allowlisted)

The model already has the raw material: retrieval context is formatted with
`[Source: <title> | URL: <url> | Type: <type>]` in
`ContextAwareQuestionAnswerAdvisor`, and widget payloads carry image URLs.

### Backend

- Tighten the system prompt / `widgetPromptGuidance()` so the model:
  - links a blog mention to its `/blogs/:id` page;
  - links a role/job or skill group to its item-level deep link (see Section 4);
  - renders news/events as external links;
  - embeds an image **only** from a URL it was explicitly given;
  - **never invents or guesses URLs** — if it has no URL for something, it links nothing.

### Frontend (`ChatMessage` custom `react-markdown` renderers)

**Link policy (custom `a` renderer):**

- Internal path matching a known route → render as a React Router `<Link>` for in-site
  navigation (no full reload). Allowed patterns:
  `/`, `/profile`, `/experience`, `/blogs`, `/blogs/:id`, `/news-events`, plus the
  item-level query forms from Section 4 (`/experience?job=…`, `/experience?skillGroup=…`)
  and section hashes (`/experience#roles`, etc.). A wrong/stale id degrades gracefully
  in-site (no drawer opens / listing shown).
- External `https://…` → rendered as a new-tab link (`target="_blank"`,
  `rel="noopener noreferrer"`) **only if the URL is in the allowlist**; otherwise the
  link is stripped to its plain text.
- Any other scheme (`javascript:`, `data:`, etc.) → always stripped to plain text.

**Image policy (custom `img` renderer):**

- Render only if `src` is in the allowlist or served from our own uploads origin.
- Rendered lazy-loaded, `max-width: 100%`, height auto, rounded corners, with `alt`.
- Non-allowlisted images are dropped (not rendered).

**Allowlist source:** built per-message from the widget payloads already streamed for
that message — blog `url` + `featuredImageUrl`, news `originalUrl` + `imageUrl`, event
`originalUrl`, code/profile image URLs. Internal route/anchor/query patterns are always
allowed by pattern match (no allowlist entry needed) because they are safe SPA
navigations. No new backend streaming channel is required.

**Success:** links in answers work and navigate correctly; images render inline; a
fabricated URL like `[Workcover Queensland](/experience Macquarie Group,)` renders as
plain text (or a safe section link), never as a broken/dangerous link.

---

## Section 4 — Item-level deep links (drawer wiring) + scroll-to-hash

Blog posts have their own page (`/blogs/:id`). Jobs and skill groups do **not** — on
`/experience` they open drawers via `useDrawer` (`openJob(id)` / `openSkillGroup(id)`,
keyed by `selectedJobId` / `selectedGroupId`). News/events live on `/news-events` as
sections; individual articles/events are external URLs.

### URL scheme

- Job → `/experience?job=<jobId>` (auto-opens the job drawer).
- Skill group → `/experience?skillGroup=<groupId>` (auto-opens the skill-group drawer).
- Section fallback anchors → `/experience#roles`, `/experience#skills`,
  `/news-events#news`, `/news-events#events`.

### Frontend wiring

- `ExperiencePage` reads `useSearchParams()` on mount/param-change and calls
  `openJob` / `openSkillGroup` accordingly (and clears the param when the drawer closes,
  so back/refresh behave).
- A small scroll-to-hash handler (in the page or a shared hook) scrolls to the section
  `id` when a `#hash` is present after navigation.
- Add stable `id`s to the relevant `<section>` elements on `ExperiencePage` and
  `NewsEventsPage` (`roles`, `skills`, `news`, `events`).

### Backend prerequisite

The model can only build these links if it is given the item ids. Today:

- `SkillsWidgetPayload.Group` has **no** id → add the skill-group id.
- `EmploymentWidgetPayload.Job` has **no** id → add the job id.
- Ensure the corresponding **tool return values** (`getSkills`, `getJobs`) also include
  ids so the model sees them in context, not just the widget card.

Blog/news/event/code payloads already carry ids or canonical URLs.

**Success:** a chat link to a specific role or skill group lands on `/experience` with
that exact drawer open; section anchors scroll to the right section.

---

## Testing

**Frontend unit tests (Vitest):**

- Link renderer: internal path → `<Link>`; allowlisted external → new-tab anchor;
  fabricated/non-allowlisted external → plain text; `javascript:` → plain text.
- Image renderer: allowlisted `src` renders; non-allowlisted `src` dropped.
- `chatStreamReducer`: `STREAM_END` reconciles text to the server `fullResponse` while
  preserving tool/widget blocks.
- `ExperiencePage`: `?job=`/`?skillGroup=` opens the correct drawer; hash scrolls to
  section.
- `ToolActivityBlock`: done state renders `block.label`, not "Used 1 tool".

**Backend tests:** widget payloads include ids (skills group, job); tool results expose
ids. Prompt/config wiring compiles. Model link/image behaviour is prompt-driven and
verified manually.

**Manual verification:** reproduce the original prompt and confirm one clean answer,
correct ordering, contextual tool labels, working in-site links (including drawer deep
links), and inline images.

## Success criteria (rollup)

- Finished tool blocks show the contextual label.
- One answer bubble per prompt, text verbatim and correctly ordered.
- Answer prose can link to blogs (page), jobs/skills (drawer deep link), and
  news/events (external), and can embed allowlisted images.
- The model cannot render a fabricated or unsafe URL/image.
