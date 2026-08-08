# Weekly digest from favourited news

Date: 2026-08-08

## Problem

`WeeklyDigestAgent` currently generates its post from everything that moved:
blog posts published since the last digest, plus up to 15 recent visible
aggregated articles, whether or not any of them are interesting. The result is
a 300-500 word list of links with a sentence each — breadth without judgement,
and no signal that a human chose any of it.

Favourites now exist (`Favourite`, global rather than per-user, typed `NEWS` or
`EVENT`). They are the missing signal: an explicit "this one mattered". The
digest should be built from them alone, and should say something substantial
about each piece rather than pointing at it.

## Goals

- The digest covers only news articles favourited in the last 7 days.
- Each covered article gets a real summary — 2-3 paragraphs drawn from the
  article's actual text — not a one-line blurb.
- Each article gets a short "why this caught my eye" note in Simon's voice.
- The post reads as one piece of writing, not a concatenation.

## Non-goals

- **Events.** `FavouriteType.EVENT` favourites are ignored. Events have a
  different shape (dates, venues, forward-looking) and belong in a different
  format if they get one at all.
- **Simon's own blog posts.** They leave the digest entirely.
- **A note field on `Favourite`.** The "why this caught my eye" line is the
  model inferring an angle from the article text, not Simon's recorded reason.
  Capturing the real reason at favourite time would be better, but it is a
  frontend and API change and is scoped separately.
- **Schedule changes.** `application.yml` already sets
  `aggregation.digest.cron: "0 0 8 * * MON"`, so the job is already weekly and
  aligns with the 7-day window. The `0 0 0 */3 * *` in the `@Scheduled`
  annotation is only a code-level fallback and is left alone.

## Selection

Favourites are selected by a fixed rolling window on when they were hearted,
not on when the article was published and not against a last-digest watermark:

```
FavouriteRepository.findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
    FavouriteType.NEWS, now.minus(windowDays, DAYS))
```

This is a new derived query, already covered by the `idx_type_created` index
created in `V014MakeFavouritesGlobal`. Ordering is most-recently-hearted first,
and that order carries through to the finished post.

Each `Favourite.contentId` resolves to an `AggregatedArticle` by id. Two cases
are dropped and logged: ids that no longer resolve, and articles with
`visible=false` (content hidden from the site should not reappear in a digest).

If the resulting list is empty, the run logs and publishes nothing.

The consequence of a fixed window rather than a watermark is accepted: a
skipped run loses that week's items rather than rolling them forward.

## Pipeline

1. **Select** — as above.
2. **Source** — for each article, `SitemapHtmlScraper.scrapeArticlePagePublic(originalUrl)`.
   That method already swallows exceptions and returns `null`, so the fallback
   chain is: fresh scrape → stored `fullContent` → stored `summary`. Fresh
   scraping is used rather than trusting `fullContent` because its depth varies
   — full page text for HTML and sitemap sources, often a bare feed snippet for
   RSS ones — and it may be weeks stale.
3. **Section** — one LLM call per article, over that single article's text,
   producing 2-3 paragraphs plus the "why this caught my eye" line. Source text
   is capped at 12,000 characters before the call; deliberately more generous
   than the 5,000-character cap used by the aggregation classifier, because
   these summaries need depth.
4. **Compose** — assemble, synthesise, validate. See below.
5. **Publish** — unchanged from today: `DigestMetadataGenerator` supplies title
   and short description, `BlogImageGenerationService` supplies the featured
   image, and the result is saved as a `Blog` with `BlogContentType.DIGEST` and
   the "Weekly Digest" tag, followed by a content-change event.

Scraping stays sequential. At this volume it is a handful of requests per run,
and sequential execution preserves the existing scraper's timeout and
user-agent behaviour rather than introducing concurrency for no gain.

Because the window is fixed, no watermark state is needed anywhere:
`findLastDigestDate()` and `isDigestBlog()` are deleted.

## Composition

There is no cap on article count — a heavy week produces a long post, by
choice. The composer therefore has to hold up under an arbitrary number of
sections, and it does so by building a guaranteed-correct document before
attempting anything clever:

1. **Assemble.** For each section, a `## [title](url)` heading built from Mongo
   values followed by that section's body. This artifact always exists before
   synthesis is attempted.
2. **Synthesise.** One LLM call, handing the model that document with
   instructions to rewrite it as a single flowing piece: one section per
   article, every markdown link preserved exactly as given, and no top-level
   title heading (the metadata generator supplies that separately).
3. **Validate.** Every section's URL must appear verbatim in the output, and
   the output must be non-blank. A missing URL means the model dropped or
   rewrote a link, so the deterministic document is used instead and a warning
   is logged.

The synthesised version ships when it is faithful; the assembled version ships
when it is not. This is what makes a synthesis pass safe: rewriting a whole
document is exactly where a model silently mangles links, and validation makes
that failure mode non-publishing rather than user-visible.

## Components

Three new types in `com.simonrowe.agents`:

- **`DigestSection`** — record of `(articleId, title, url, body, fallback)`.
  The unit passed between stages. `url` and `title` come from Mongo and are
  never model-generated. `fallback` is true when the section's LLM call failed
  and `body` is the article's stored summary rather than generated prose; the
  agent uses it to detect a total-failure run (see Error handling).
- **`ArticleSectionWriter`** — depends on `SitemapHtmlScraper` and `Ai`. One
  public method: article in, `DigestSection` out. Owns the source fallback
  chain and the per-article prompt. Unit-testable against a stubbed scraper and
  a stubbed `Ai` without constructing the agent.
- **`DigestComposer`** — depends on `Ai`. Takes `List<DigestSection>`, returns
  final markdown. Owns assembly, synthesis, and URL validation.

`WeeklyDigestAgent` is left as pure orchestration, roughly half its current
size. It drops `AggregatedArticleRepository` scanning in favour of
`FavouriteRepository` plus by-id lookups. Splitting the work out matters
because the agent is already ~200 lines and this change roughly doubles what it
does.

Changes to existing code:

- `DigestMetadataGenerator.generate(...)` loses its now-always-empty
  `recentBlogs` parameter; the signature becomes
  `generate(List<AggregatedArticle>, String)` and its fallback simplifies
  accordingly.
- Two properties join the existing `aggregation.digest` block in
  `application.yml`: `model` (default `gpt-5.6-luna`, see below) and
  `window-days` (default 7). All three digest call sites — the two new
  components and `DigestMetadataGenerator` — read this property instead of
  hardcoding a model.
- The admin trigger in `AdminAggregationController` is unchanged — it still
  calls `digestAgent::generateDigest` on a virtual thread.

## Model selection and registration

The digest runs on `gpt-5.6-luna`. The three agent call sites currently
hardcode `gpt-4o-mini`, which is thin for multi-paragraph analysis over long
source text; the two in the digest pipeline move to the new property, and
`ContentAggregationAgent`'s classifier is deliberately left on `gpt-4o-mini`
(it runs against every scraped item every 6 hours and is a different
cost/quality trade-off — see `docs/model-usage.md`).

Setting the property is not sufficient on its own.
`embabel-agent-openai-autoconfigure` ships its own model registry at
`classpath:models/openai-models.yml`, header-dated "Model IDs verified against
GET /v1/models on 2026-03-29", whose newest family is GPT-5.4. Embabel
registers one Spring bean per entry and `ai.withLlm(...)` resolves against
those beans, so `ai.withLlm("gpt-5.6-luna")` fails at runtime against the
stock registry. `gpt-5.6-luna` itself is real and available on the account —
verified against `GET /v1/models`, alongside `gpt-5.6-sol` and `gpt-5.6-terra`.

**Upgrading Embabel does not fix this.** The bundled registry in the current
release (1.0.0) carries the same 2026-03-29 header and still stops at GPT-5.4 —
it adds `gpt-5.3-chat-latest` and nothing newer. Explicit registration is
required either way, so the Embabel upgrade is independent of this work and is
tracked separately.

The chosen fix is to register the model ourselves in `AgentConfig` via
Embabel's `OpenAiCompatibleModelFactory`, adding one bean and leaving the
bundled registry untouched so Embabel upgrades stay clean. The rejected
alternative was vendoring a copy of `openai-models.yml` into
`backend/src/main/resources/models/`, which works but makes us owner of the
whole file — including pricing for models we do not use — and lets it go
silently stale on the next Embabel bump.

Model facts for the registration, from OpenAI's model page and pricing:

| Property | Value |
| --- | --- |
| Model id | `gpt-5.6-luna` — **not** the `gpt-5.6` alias, which routes to Sol |
| Context window | 1,050,000 tokens (922,000 max input) |
| Max output | 128,000 tokens |
| Pricing | $0.20 / 1M input, $1.20 / 1M output, $0.02 / 1M cached input |
| `supports_temperature` | `false` — only the default value of 1 is accepted |

The digest issues plain completions with no tools, so the one documented
`gpt-5.6-luna` restriction that would bite — function tools combined with
`reasoning_effort` are rejected on `/v1/chat/completions`, requiring
`/v1/responses` or `reasoning_effort: none` — does not apply here. It is
recorded because it is the same shape as the failure in
`docs/openai-api-setup.md`, where a `reasoning_effort` default merged into
every per-call `OpenAiChatOptions` silently disabled the topic guardrail, and
because it constrains any future move of the tool-enabled chat path onto this
model.

These values still want confirming with one live call before the first
scheduled run, since a wrong `supports_temperature` surfaces as a 400 rather
than as degraded output.

## Error handling

The pipeline degrades stage by stage rather than aborting:

| Failure | Behaviour |
| --- | --- |
| Scrape fails or returns null | Fall back to stored `fullContent`, then `summary`. A quality gradient, not an error path. |
| One section's LLM call fails | That section's body becomes the article's stored summary and `fallback` is set; other sections unaffected; the digest still publishes. |
| Every section has `fallback` set | Indicates a broad LLM outage. A post of nothing but stored one-liners is worse than silence: log at error, publish nothing. |
| Synthesis fails or fails validation | Use the deterministic assembled document. |
| Metadata generation fails | Existing `DigestMetadataGenerator` fallback. |
| Image generation fails | Existing behaviour; a null featured image URL is already tolerated. |

## Testing

Three unit suites, all Mockito, matching the existing `WeeklyDigestAgentTest`
style:

**`ArticleSectionWriterTest`**
- Scrape succeeds — scraped content is what reaches the model.
- Scrape returns null — stored `fullContent` is used.
- Scrape returns null and `fullContent` is empty — stored `summary` is used.
- LLM call throws — the section body falls back to the stored summary and
  `fallback` is set.

**Model registration**
- A Spring context test asserting the `gpt-5.6-luna` model bean is registered
  and resolvable, so a future Embabel upgrade that changes the registration API
  fails the build rather than failing silently at 08:00 on a Monday.

**`DigestComposerTest`**
- Synthesis preserves every URL — its output ships.
- Synthesis drops a URL — the deterministic document ships.
- Synthesis throws — the deterministic document ships.

**`WeeklyDigestAgentTest`** (rewritten against favourites)
- No favourites in the window — nothing saved, nothing published.
- A favourite whose article id no longer resolves — skipped.
- A favourite pointing at a `visible=false` article — skipped.
- Window boundary — a favourite hearted 8 days ago is excluded, one hearted 6
  days ago is included.
- Every section came back with `fallback` set — nothing saved, nothing
  published.
- Happy path — a `Blog` is saved with `DIGEST` content type and the "Weekly
  Digest" tag, and a content-change event is published.

Checkstyle runs through the existing pre-commit hook.
