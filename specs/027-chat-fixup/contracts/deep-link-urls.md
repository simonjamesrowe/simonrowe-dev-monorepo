# Contract: Deep-link URLs + link/image render policy

## Recognized internal routes (React Router `<Link>`, always allowed by pattern)
| Pattern | Notes |
|---------|-------|
| `/` | home |
| `/profile` | profile page |
| `/experience` | experience page |
| `/experience?job=<jobId>` | auto-opens job drawer |
| `/experience?skillGroup=<groupId>` | auto-opens skill-group drawer |
| `/experience#roles`, `/experience#skills` | scroll to section |
| `/blogs` | blog listing |
| `/blogs/:id` | blog detail page |
| `/news-events` | news/events page |
| `/news-events#news`, `/news-events#events` | scroll to section |

Anything matching these renders as an in-site navigation (no full reload). A stale/unknown
id degrades gracefully (no drawer / listing), no error.

## Link render decision (custom `a` renderer)
```
href matches internal route pattern      → <Link to={href}>            (in-site)
href is https AND href ∈ message allowlist → <a target=_blank rel=noopener noreferrer>
otherwise (non-allowlisted https, http:, javascript:, data:, malformed) → plain text
```

## Image render decision (custom `img` renderer)
```
src ∈ message allowlist OR src starts with uploads origin (/uploads/ or ${API_BASE_URL}/uploads/)
    → <img loading="lazy" style="max-width:100%;height:auto" alt=…> (rounded)
otherwise → dropped (render nothing)
```

## Per-message allowlist
Built from that message's streamed widget blocks: blog url+imageUrl, news
originalUrl+imageUrl, event originalUrl(+imageUrl), code/profile image URLs.

## ExperiencePage / NewsEventsPage wiring
- `ExperiencePage`: `useSearchParams()` → on `job`/`skillGroup` param, call
  `openJob`/`openSkillGroup`; on drawer close, clear the param.
- Section ids: `roles`, `skills` (Experience); `news`, `events` (NewsEvents).
- Shared `useScrollToHash` effect scrolls to `#hash` after navigation.

## Acceptance
- Internal link → in-site nav / correct drawer (FR-011, FR-018, SC-004).
- `[Workcover Queensland](/experience Macquarie Group,)` and other fabricated/non-allowlisted
  URLs → plain text (FR-012/013, SC-005).
- `javascript:` → plain text (FR-013). Non-allowlisted image dropped (FR-014).
- Stale id → graceful (FR-021).

## Test hooks
- `linkPolicy` unit tests: internal→Link; allowlisted https→new-tab; fabricated→text;
  `javascript:`→text; allowlisted img renders; non-allowlisted img dropped.
- `ExperiencePage` test: `?job=` opens job drawer, `?skillGroup=` opens group drawer, hash
  scrolls; drawer close clears param; unknown id no-throw.
