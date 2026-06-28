# Data Model: Landing Chat Widgets

## ChatResponse

Represents one server-to-client event on the active chat topic.

Fields:

- `sessionId`: Chat session identifier.
- `content`: Text content for stream chunks, final text fallback, or error text.
- `type`: One of `STREAM_START`, `STREAM_CHUNK`, `STREAM_END`, `ERROR`, `TOOL_START`, `TOOL_END`, `WIDGET`.
- `timestamp`: Event creation timestamp.
- `toolLabel`: Optional human-readable label for tool activity.
- `widgetKind`: Optional widget category for `WIDGET` events.
- `payload`: Optional structured widget data.

Validation rules:

- `STREAM_CHUNK` should include non-empty `content`.
- `ERROR` should include user-safe `content`.
- `TOOL_START` and `TOOL_END` should include `toolLabel`.
- `WIDGET` should include `widgetKind` and a non-empty `payload`.
- Unknown `widgetKind` values are allowed by transport but skipped by the frontend registry.

## ChatBlock

Frontend-only representation of an assistant message.

Variants:

- `text`: Accumulated assistant prose.
- `tool`: Tool activity with `label` and `status` of `running` or `done`.
- `widget`: Inline card with `widgetKind` and typed `payload`.

State transitions:

- `STREAM_START` creates a new assistant message with no blocks.
- `STREAM_CHUNK` appends to the trailing text block or creates one.
- `TOOL_START` adds a running tool block.
- `TOOL_END` marks the matching running tool block done.
- `WIDGET` adds a widget block.
- `STREAM_END` finalizes the assistant message.
- `ERROR` adds an error text block and finalizes.

## SkillsWidgetPayload

Fields:

- `groups`: Skill groups.
- `groups[].name`: Group name.
- `groups[].skills`: Skills inside the group.
- `groups[].skills[].name`: Skill name.
- `groups[].skills[].rating`: Optional numeric strength rating.

## EmploymentWidgetPayload

Fields:

- `jobs`: Employment records.
- `jobs[].company`: Company name.
- `jobs[].title`: Role title.
- `jobs[].start`: Start date or label.
- `jobs[].end`: End date or present label.
- `jobs[].summary`: Short role summary.
- `jobs[].skills`: Related skill names.

## CodeWidgetPayload

Fields:

- `examples`: Code examples.
- `examples[].id`: Existing code example identifier.
- `examples[].title`: Example title.
- `examples[].description`: Summary.
- `examples[].language`: Programming/configuration language.
- `examples[].code`: Code content or preview.
- `examples[].skills`: Related skill names.

## BlogWidgetPayload

Fields:

- `posts`: Blog posts.
- `posts[].id`: Existing blog identifier or slug.
- `posts[].title`: Post title.
- `posts[].summary`: Short description.
- `posts[].tags`: Tag names.
- `posts[].publishedDate`: Publication date.
- `posts[].url`: Public route for the post.

## Landing Page

Represents the refreshed public homepage composition.

Elements:

- Top navigation with brand, route links, search, theme toggle, and mobile menu.
- Chat-first hero with Simon's name, role, positioning, prompt chips, and inline chat entry.
- About summary with portrait, concise copy, skill chips, CV link, experience link, and social links.
- Footer with public exploration and connection links.

Validation rules:

- Must render in dark and light themes.
- Must remain readable at mobile, tablet, and desktop widths.
- Must preserve keyboard access to navigation, chat, theme controls, links, and form controls.
