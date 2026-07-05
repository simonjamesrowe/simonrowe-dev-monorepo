# Chat Personas, Source Widgets, and Editorial Blog Digests — Design

**Date:** 2026-07-05
**Status:** Approved (pending implementation plan)

## Summary

Improve the portfolio chat and automated blog digest experience around a primary
visitor persona: a technical hiring manager evaluating Simon's engineering depth,
market awareness, and concrete implementation evidence.

The work has three connected outcomes:

1. Make chat behavior more predictable: the assistant must not answer until a
   visitor submits a prompt, and each prompt should produce one clean response.
2. Add image-capable source widgets where images are meaningful, especially for
   blogs, news, and events.
3. Move generated digest posts away from repetitive "AI & Tech Roundup" titles
   and generic abstract hero images toward personal editorial notes grounded in
   the actual source material.

## Decisions

- **Primary persona:** technical hiring manager.
- **First end-to-end scenario:** market awareness: "What has Simon been paying
  attention to in AI and backend engineering recently?"
- **Required regression scenarios:** architecture depth, implementation evidence,
  and follow-up grounding.
- **News/events source scope:** use existing aggregated news/events as sources in
  chat and digest generation. Do not mix Simon's blog posts into the public
  news/events listing as another feed source in this pass.
- **Widget image rule:** widgets render images only where the content naturally
  supports them. Missing images must produce a clean text-first card, not a blank
  image slot or decorative placeholder.
- **Digest voice:** personal editorial note. Titles and summaries should sound
  like Simon curated the material, not like a generic automated publication.

## Persona Scenarios

### Market Awareness

Prompt: "What has Simon been paying attention to in AI and backend engineering
recently?"

Expected behavior:

- Search recent Simon blog posts and aggregated news/events.
- Render blog, news, and event cards where relevant.
- Include image thumbnails on cards that have real image data.
- Give a short synthesis that links Simon's writing to the external sources.
- Cite source links visibly.

Failure modes to prevent:

- Generic web-news answer with no connection to Simon's content.
- Source data hidden in long prose instead of rendered as cards.
- Assistant continuing to talk after the useful response is complete.

### Architecture Depth

Prompt: "Talk me through Simon's experience with Spring Boot, Kafka, search, and
AI."

Expected behavior:

- Use skills, jobs, blogs, and code examples where relevant.
- Render widgets for structured evidence.
- Summarize the pattern across roles and projects without re-listing card data.
- Avoid unsupported claims.

### Implementation Evidence

Prompt: "Show me concrete examples of how Simon builds production-grade
services."

Expected behavior:

- Render code and blog widgets.
- Frame why each example demonstrates delivery quality.
- Avoid dumping large code blocks into prose when a code card can carry the
  detail.

### Follow-Up Grounding

Prompt sequence: ask the architecture-depth prompt, then ask "What about
testing?"

Expected behavior:

- Use conversation-aware retrieval.
- Keep the follow-up tied to the earlier architecture topic.
- Cite real site content and links.
- Avoid hallucinated sources or treating the follow-up as a standalone generic
  testing question.

## Chat Behavior

The chat should open silently. Suggested prompts can be displayed, but the
assistant must not emit an answer until the visitor either submits text or clicks
a prompt chip.

Each visitor prompt should create exactly one assistant response stream. During
that stream, tools may publish activity indicators and widgets, and the assistant
may add brief framing text. Once the stream ends, no additional assistant content
should appear unless the visitor sends another prompt.

Clear-chat and reconnect flows must not replay an old response, send the initial
query twice, or create a new assistant message without a new visitor action.

## Image-Capable Widgets

Add optional image fields only to widget payloads where images add useful
context:

- **Blogs:** include the blog featured image when available, plus title, summary,
  tags/date, and read-post link.
- **News/articles:** include `imageUrl` when available, plus source badge, title,
  summary, published date, and external link.
- **Events:** prepare for an optional image if a source provides one, but default
  to a strong date/venue/location card.
- **Profile/contact:** profile imagery can be used where it has clear purpose,
  but should not be repeated in every assistant message.
- **Jobs, skills, code:** remain text/card-first unless there is meaningful
  existing media. Do not add forced placeholder imagery.

Frontend rendering requirements:

- No empty image containers.
- Remote and local upload paths resolve consistently.
- Cards remain readable and stable when images are missing.
- Unknown widget kinds still fail gracefully.

Backend payload requirements:

- Blog widget payloads expose resolved featured-image paths.
- News widget payloads expose existing article `imageUrl`.
- Event widget payloads include optional image support without requiring one.
- News and event tools publish typed widget events, matching the existing
  tool-driven widget model used by skills, jobs, code, and blogs.

## News And Events As Sources

Existing aggregation already stores external articles and events and indexes
them for search and embeddings. This design keeps that pipeline and adds two
consumer improvements:

1. Chat can render news and event results as visual source cards.
2. The digest generator can use aggregated article/event titles, summaries,
   source names, dates, and URLs to draft more grounded editorial posts.

This pass does not add a new external source-management model. The admin content
source screens, aggregation scheduler, and visible news/events pages remain the
source of truth.

## Editorial Digest Generation

Replace the fixed title pattern:

```text
AI & Tech Roundup: <date range>
```

with a title strategy based on the actual source mix. The title generator should
prefer personal, curated phrasing, for example:

- "What caught my eye this week in AI tooling"
- "A few useful backend and AI links from this week"
- "Notes from the week: agents, Spring, and developer tools"

The digest prompt should keep these voice rules:

- First person, as Simon.
- Practical and technical.
- Concise.
- Clear source links.
- No title heading in the generated markdown body if the title is stored
  separately.

The short description should also be generated from the source material rather
than fixed as "Latest roundup of site activity and tech news".

## Blog Image Generation

The existing image generation service already varies palette and composition
deterministically by title, but the prompt still pushes most posts toward a
similar "professional abstract tech hero" style.

Improve image prompts by passing richer context:

- Blog title.
- Blog short description.
- Dominant topics.
- Top source titles and source names for digest posts.
- A visual direction selected from a broader set of editorial and technical
  metaphors.

Preferred visual directions:

- Desk/workbench with notes, diagrams, and browser windows.
- Architecture sketches.
- Tooling dashboards.
- Event streams.
- Model orchestration.
- Search indexes.
- Testing or delivery pipelines.

Guardrails:

- No text, words, letters, logos, or UI copy inside generated images.
- Avoid every post collapsing into abstract neon networks.
- Keep deterministic variation so repeated backfills do not produce random churn
  for unchanged source content.

## Architecture

### Backend

- Extend chat widget DTOs or add new DTOs for `news` and `events`.
- Update `ProfileMcpTools.searchNews` and `getUpcomingEvents` to publish
  `TOOL_START -> WIDGET -> TOOL_END` events when a session id is available.
- Include optional image data in blog/news/event widget payloads.
- Adjust the chat system prompt to emphasize brief framing around widgets and no
  re-listing of card data.
- Extract digest title/short-description generation behind a small strategy or
  helper so it can be unit tested independently.
- Enrich `BlogImageGenerationService` prompts with source/topic context while
  preserving deterministic variation.

### Frontend

- Add `news` and `events` widget kinds to chat types and the widget registry.
- Create image-capable `NewsWidget` and `EventsWidget` components.
- Add optional image rendering to `BlogListWidget`.
- Keep image fallbacks clean: no empty thumbnail area if an image is absent.
- Strengthen `ChatPanel` lifecycle tests around initial mount, initial query,
  clear-chat, reconnect, stream completion, and duplicate or late events.

## Error Handling

- Empty news/event results emit no widget and let the assistant explain the miss
  briefly.
- Invalid or missing image URLs render text-only cards.
- External source links must open as external links; internal blog links remain
  app routes.
- Search unavailability should keep returning the existing unavailable message
  without breaking the chat stream.
- Digest generation should fall back to a deterministic title and raw summary if
  the LLM call fails, but the fallback must not use the old repeated
  "AI & Tech Roundup" prefix.

## Testing

### Frontend

- Widget registry renders `blogs`, `news`, and `events` with images when present.
- The same widgets render cleanly without images.
- Blog cards do not leave empty image slots.
- `ChatPanel` does not create an assistant message on initial mount without
  `initialQuery`.
- `initialQuery` sends one user message and produces one assistant response.
- Clear-chat and reconnect do not replay old messages.
- Duplicate or late `STREAM_END`/widget events do not create extra final
  messages.

### Backend

- `searchNews` publishes news widgets with image URLs where available.
- `getUpcomingEvents` publishes event widgets and handles absent image data.
- Existing skills/jobs/code/blog widget behavior remains intact.
- Digest title generation avoids the old fixed prefix and uses source context.
- Digest short description uses source context.
- Image prompt generation includes source/topic context and preserves no-text
  guardrails.
- Chat system prompt includes brief-framing guidance for widget-backed tools.

### Manual Smoke Test

Open the homepage chat and run the four persona prompts:

1. "What has Simon been paying attention to in AI and backend engineering
   recently?"
2. "Talk me through Simon's experience with Spring Boot, Kafka, search, and AI."
3. "Show me concrete examples of how Simon builds production-grade services."
4. "What about testing?" as a follow-up.

Confirm:

- No assistant answer appears before a visitor prompt.
- Visual widgets appear for applicable sources.
- Images render where source data supports them.
- The assistant gives short framing instead of repeating every card field.
- The response stops cleanly after one assistant turn.

## Out Of Scope

- Persisting chat sessions beyond the current in-memory model.
- Adding a full chat analytics dashboard.
- Adding new external source providers beyond the existing content source model.
- Mixing Simon's own blog posts into the public news/events feed as a source.
- Forcing every widget to include an image.
- Redesigning the whole chat UI outside the widget and lifecycle fixes described
  here.
