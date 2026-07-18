# Contract: `webSearch` chat tool

**Provider**: `WebSearchTools` (`@Tool`), registered in `ChatConfig.defaultTools(...)`
**Caller**: the chat model (decides when to invoke)

## Tool description (encodes boundary A)

> "Search the live web for current information about companies Simon has worked at,
> technologies/skills he lists, or sources in his content. Use ONLY to enrich topics grounded
> in Simon's profile/experience/skills — not for general/unrelated questions."

## Signature

```java
@Tool(description = "...boundary A...")
public Object webSearch(   // List<WebSearchResult> on success, or a short "unavailable" String
    @ToolParam(description = "Search query grounded in Simon's profile/experience/skills")
    String query,
    ToolContext toolContext)
```

## Behaviour matrix

| Condition | Result | Side effects |
|-----------|--------|--------------|
| Not configured (blank SearXNG base URL) | short "web search is unavailable" | warn log; no external call; no tool labels required |
| Query null/blank | empty list | no external call; no tool labels |
| Configured + results | `List<WebSearchResult>` (≤ max-results) | `toolStart`/`toolEnd` "Searching the web" published if sessionId present |
| Configured + zero results | empty list | tool labels published |
| Client error / timeout | short "web search is unavailable" | warn log; tool labels closed; **never throws** |

## Presentation contract

- Results are cited by the model **inline as markdown links** using the existing link rules.
  **No widget** is published (no frontend changes).
- Progress indicator "Searching the web" matches the format of the other tool labels
  (`ChatStreamPublisher.toolStart/toolEnd`), read from `toolContext` `sessionId` null-safely.

## System-prompt contract (`chat.system-prompt`)

- Add a `webSearch` bullet to the tools list with a one-line usage rule mirroring boundary A
  (enrich Simon-grounded topics; cite sources as markdown links; do not use for unrelated
  questions).
- Add a short nudge to use `getRecentBlogs` / `searchNews` for "what's he writing about lately"
  and "what's new in Spring/AI news".
