# Share links for blogs and news/events

Date: 2026-08-28

## Summary

Add a Share control to blog posts, blog listing cards, and news/event cards. It hands
out a short, human-readable, first-party URL — `https://simonrowe.dev/s/exactly-once` —
which redirects to the content and counts human clicks. The short-link endpoint also
serves Open Graph metadata, so a shared link unfurls with a title, description and
image in Slack, LinkedIn, WhatsApp and iMessage.

## Motivation

Three problems, all real today:

1. **Blog URLs are unshareable by hand.** There is no slug on `Blog`; the public route is
   `/blogs/{id}` where `id` is a 24-character Mongo ObjectId, e.g.
   `simonrowe.dev/blogs/68f3a1c94b2e7d5a0c1f9e83`.
2. **No click visibility.** Nothing records whether a shared link was ever opened.
3. **Nothing unfurls.** `frontend/index.html` contains no OG or Twitter tags and there is
   no prerendering. Every link pasted anywhere renders as the generic site title with no
   image or description. Crawlers do not run JS and *do* follow redirects, so a bare
   `302` to the SPA fixes nothing.

A fourth, subtler one: news and events have no first-party page at all. Cards link
straight out to `article.originalUrl`, so today "sharing a news item" can only mean
sharing someone else's URL — the visitor never sees the AI summary or the narration
audio that are the first-party value on that page.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Short link purpose | Human-readable slug **and** click counting | Both wanted; the slug is the visible half, the counter the motivating half |
| Slug length | ≤ 20 characters, unique | Readable and speakable; long enough to say what the content is |
| News/event share target | `/news-events?article={id}`, opening the summary drawer | Points the share at first-party work rather than the publisher |
| Unfurl strategy | `/s/{slug}` serves OG HTML to **every** client, redirect via meta-refresh + JS | No User-Agent guesswork on the correctness-critical path; `curl`-verifiable |
| Minting | Eager: on save, on ingest, plus a Mongock backfill | Removes the need for any public write endpoint — the Share button is pure frontend |
| Click data | A counter, incremented only for non-unfurler User-Agents | Smallest thing that makes the number mean what it looks like |
| Share control | Native share sheet on mobile, clipboard on desktop | Progressive enhancement over a guaranteed clipboard path; no third-party logos |

### Rejected alternatives

- **OG tags on the canonical `/blogs/:id` URL** via nginx routing crawlers to a
  backend-rendered page. The better long-term answer, and it would fix links copied
  from the address bar too. Excluded because it is its own feature and touches the prod
  proxy conf; once the Share button hands out `/s/` links, the short link is the URL
  people paste.
- **A first-party page per article summary** (`/news/{slug}`). Best share target and the
  only one that carries OG tags cleanly, but it is a new page and raises an editorial
  question — a URL on simonrowe.dev that is mostly a machine summary of someone else's
  writing.
- **Per-click event rows** (`short_link_clicks` with timestamp, referrer, platform).
  More interesting to look at, but a new collection, retention thinking, and a privacy
  dimension. Turns a shortener into an analytics product.
- **User-Agent sniffing to choose between OG HTML and a `302`.** Rejected for the OG
  path because a missed bot silently breaks unfurling. Accepted for click counting,
  where a missed bot merely inflates a statistic.
- **Storing the slug on the content documents.** Would need three schema changes, could
  not enforce uniqueness across three collections, and would put the slug in two places.

## Data model

One new collection, `short_links`. No changes to `blogs`, `aggregated_articles` or
`aggregated_events`.

```
_id            String    the slug itself
contentType    enum      BLOG | ARTICLE | EVENT
contentId      String    id in blogs / aggregated_articles / aggregated_events
clickCount     long
lastClickedAt  Instant   nullable
createdAt      Instant
```

The slug **is** the `_id`: the redirect becomes a primary-key lookup, and slug
uniqueness is enforced by Mongo rather than by application code that hopes. A unique
compound index on `(contentType, contentId)` guarantees exactly one link per item, so a
re-save can never mint a second slug for content that already has one.

Indexes are created by a Mongock change unit, **not** by annotations:
`auto-index-creation` is off in this repo, so `@CompoundIndex` alone is decorative.

`short_links` must be added to `BackupService.BACKUP_COLLECTIONS` and
`RestoreService.IMPORT_ORDER_INDEPENDENT`. This is not optional housekeeping: these
slugs appear in links already pasted into other people's Slack channels, so dropping
them on a restore breaks URLs that exist in the wild.

## Slug generation

`ShortLinkSlugger` — pure, no Mongo, unit-testable:

1. Lowercase the title, strip accents, replace runs of non-alphanumerics with `-`, trim
   leading/trailing `-`.
2. Take **whole words** up to 20 characters. `"Exactly-once semantics in Kafka"` yields
   `exactly-once`, not a mid-word chop.
3. On collision, retry with the candidate cut to 17 characters plus `-2`, `-3`, … so the
   20-character ceiling always holds.
4. If the result is empty (emoji-only or non-Latin title), fall back to a 6-character
   random `[a-z0-9]` code.

`ShortLinkService.ensureFor(contentType, contentId, title)` is idempotent — an existing
link always wins and is returned unchanged. Because the same item always returns its own
slug, collisions arise only between *different* items with similar titles.

Called from:

- the blog admin save path (publish or update),
- the article/event ingest path,
- `V029`'s backfill over existing `blogs`, `aggregated_articles` and `aggregated_events`.

The backfill is pure Mongo with no external I/O, so it is safe to let run against the
shared Testcontainers Mongo — unlike the LLM-calling change units this repo has been
bitten by before.

## `GET /s/{slug}`

A single `ShortLinkController`, deliberately outside `/api/`. `SecurityConfig` already
ends `.anyRequest().permitAll()`, so no new matcher is required — but `SecurityConfigTest`
should assert `/s/**` is public so a future tightening cannot silently break it.

Returns `200` with a ~1KB HTML document to **every** client:

- `og:title`, `og:description`, `og:image`, `og:url`, `og:type`,
  `twitter:card=summary_large_image`
- `<meta http-equiv="refresh" content="0;url=…">` plus `<script>location.replace(…)</script>`
- `<link rel="canonical">` pointing at the real destination
- a `<noscript>`-visible plain `<a>`, so a text browser or a stripped-down client still
  has a working link

Destination by type:

| `contentType` | Redirects to |
|---|---|
| `BLOG` | `/blogs/{contentId}` |
| `ARTICLE` | `/news-events?article={contentId}` |
| `EVENT` | `/news-events?event={contentId}` |

An unknown slug returns `404` with the themed not-found body — never a redirect to `/`,
which would make a typo look like a working link.

### OG image resolution

Needs a new property, as none exists today:

```yaml
site:
  base-url: ${SITE_BASE_URL:https://simonrowe.dev}
```

Rules, in order:

1. A `/uploads/…` path gets `base-url` prepended.
2. An already-absolute `http(s)` URL passes through. For news this hotlinks the
   publisher's image — acceptable, since it is the image already displayed on the card.
3. Anything else, or null, falls back to a committed static `/images/share-card.png`.

Never emit a relative `og:image`: crawlers drop it silently, which presents as "the
feature doesn't work" with no error anywhere.

### Click counting

Incremented here, and skipped when the User-Agent matches a known unfurler. This is
load-bearing given the decision to serve OG HTML to everyone: a single paste into a
Slack channel fetches `/s/{slug}` once for the unfurl before any human clicks, and
LinkedIn, WhatsApp and iMessage all do the same. Without the filter, most of the count
is robots reading metadata.

`UnfurlerDetector` matches `Slackbot`, `facebookexternalhit`, `LinkedInBot`, `WhatsApp`,
`Twitterbot`, `Discordbot`, `TelegramBot`, `redditbot`, plus a generic
`bot|crawler|spider|preview`. Table-driven test over the real UA strings.

The increment is a fire-and-forget `$inc`. A Mongo hiccup must never stop the redirect —
the counter is the least important thing this endpoint does.

## nginx routing

`/s/` on `simonrowe.dev` currently hits the SPA. The prod proxy
(`config/nginx/nginx-proxy.conf`) sends all of `/` to `frontend:80`, so **it needs no
change**; the fix is one block in `frontend/nginx.conf`:

```nginx
location /s/ {
    proxy_pass http://backend:8080/s/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

**Deploy consideration.** `frontend/nginx.conf` is bind-mounted from the deploy directory
in prod, not baked into the image, so pulling a new frontend image alone does not apply
it. `sync-config` fast-forwards the deploy checkout so a normal deploy does pick it up,
and `frontend` is in the recreate allowlist — but verify with an explicit
`curl -i https://simonrowe.dev/s/<known-slug>` after deploy rather than assuming.

`frontend/vite.config.ts`'s dev proxy needs the same route, or `/s/` 404s locally.

## API read path

Add `shortUrl` — the full absolute URL, so the frontend never concatenates a base — to:

- `BlogSummaryResponse`
- `BlogDetailResponse`
- `ArticleResponse`
- `EventResponse`

Populated by a batched lookup against `short_links`, one query per listing (24 articles
is one extra query, not 24).

The field is **nullable**, and the Share button is simply absent when it is null. A
brand-new item whose link has not been minted yet must render fine rather than hand out
a broken URL.

## Frontend

### `ShareButton`

In `components/common/`, alongside `FavouriteButton` and following its shape.

- `navigator.share` when present (mobile) → OS share sheet.
- Otherwise `navigator.clipboard.writeText` → the button swaps to a tick and "Copied"
  for 2 seconds.
- `document.execCommand('copy')` fallback for non-secure contexts — realistically only
  local dev over plain HTTP.
- A visitor cancelling the native sheet throws `AbortError`; swallow it, do not surface
  it as a failure.

### Placement

- `/blogs/:id` — in the post header, near the title.
- Blog listing cards.
- News and event cards.

On news cards Share is the **fourth** control after Listen, Summarise and Favourite, on a
card whose job is a headline and an image. Check at mobile width during implementation;
if four wraps badly the fix is icon-only, not removal.

### News deep-linking

`NewsEventsPage` has no deep-link support today — no `useSearchParams`, and the cards
carry no `id` attributes even though `useScrollToHash(!loading)` is already mounted.

- Read `?article=` / `?event=` via `useSearchParams`.
- Open the summary drawer for that id.
- Add `id={article.id}` / `id={event.id}` to the cards so `useScrollToHash` can find them.
- **An id not present in the loaded page needs a targeted fetch.** This is the case where
  someone opens a shared link to an article that has since fallen off page one, and it is
  the failure mode most likely to be missed — the page would load and silently do nothing.

## Admin

- A `clickCount` column in the blogs admin list.
- A sortable "Shared links" table — slug, title, type, clicks, last clicked — at a new
  `/admin/short-links` route, backed by a new admin endpoint under `/api/admin/**`
  (already `hasRole(ADMIN)`).

## Testing

- `ShortLinkSluggerTest` — word-boundary truncation, the 20-character ceiling holding
  through `-2`/`-3` suffixes, accent stripping, empty and emoji-only titles.
- `ShortLinkServiceTest` — idempotency (two `ensureFor` calls return one slug); collision
  between two distinct items with the same title.
- `ShortLinkControllerTest` — assertions on the actual HTML: OG tags present, `og:image`
  absolute, correct destination per type, `404` on an unknown slug.
- `UnfurlerDetectorTest` — real UA strings, table-driven, both directions.
- `V029` change-unit test, including that a second run mints nothing new.
- Frontend: `ShareButton` across all three paths including `AbortError`;
  `NewsEventsPage` opening the drawer from `?article=`, including the not-in-page id.
- `SecurityConfigTest` — `/s/**` is public.

## Out of scope

Deliberately excluded, all additive later without changing any URL already shared:

- OG tags on the canonical `/blogs/:id` and `/news-events` URLs. Links copied from the
  address bar still unfurl bare.
- Per-click event rows, referrer data, geography, and any chart.
- Hand-editable slugs in the blog editor. Auto-truncation will occasionally produce
  something clumsy; that is accepted for now.
- Short links for any other content type (jobs, code examples, skill groups).
