# Runbook: on-demand article summaries and summary audio

Covers the AI-generated in-depth summaries on `/news-events` and their optional spoken
narration. See `specs/034-article-summary-audio/` for the spec, plan and contracts.

## What exists where

| Thing | Where |
|---|---|
| Summary documents | MongoDB `article_summaries`, one per article per prompt version |
| Summary audio | MongoDB `narrations` (`contentType: "ARTICLE_SUMMARY"`), MP3 at `uploads/narrations/{id}/narration.mp3` |
| Backend | `com.simonrowe.summary` (+ `ArticleSourceTextProvider` in `com.simonrowe.aggregation`) |
| Frontend | `components/news/NewsSummaryDrawer.tsx`, `SummaryButton.tsx`, `SummaryNarration.tsx`, `hooks/useArticleSummaries.ts` |

## Endpoints

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/news/{id}/summary` | any valid JWT | Blocks ~15–30s and returns the finished summary. `202` means someone else is generating. |
| `GET /api/news/{id}/summary` | public | `?afterVersion=&waitSeconds=` long-poll, `waitSeconds` capped at 25 |
| `GET /api/news/summaries/ids` | public | Article ids with a `READY` summary |
| `POST /api/news/{id}/summary/narration` | any valid JWT | Queues TTS |
| `GET /api/news/{id}/summary/narration` | public | Same long-poll contract as blog narration |
| `POST /api/blogs/{id}/narration` | any valid JWT | Queues TTS. **Authenticated since 035-listen-from-listing** — it was public before that |
| `GET /api/blogs/{id}/narration` | public | Rate-limited 10/min per IP on `GET` as well as `POST` |
| `GET /api/narrations/ready?contentType=BLOG\|ARTICLE_SUMMARY` | public | Bulk `[{contentId, audioUrl, durationSeconds}]`, newest `READY` per content id. For `ARTICLE_SUMMARY` the `contentId` is the **article** id |

Reads are public because the artefact is globally shared; writes need a session because
they cost money.

**The blog/summary narration asymmetry is gone.** `POST /api/blogs/{id}/narration` was public
for most of its life, and this runbook used to record that as deliberate. It stopped being
defensible when the listing pages gained a Listen control on every card: a render draws on the
same 1,000,000 chars/month budget as summary narration, so gating only the listing would have
left the identical post anonymously narratable from its detail page. Both writes now require any
valid JWT (no admin role), and `SecurityConfigTest` asserts the new posture — restoring the old
behaviour means changing that test on purpose.

**`GET /api/narrations/ready` is a necessity, not an optimisation.** `RateLimitInterceptor`'s
POST-only exemption lives in the summary branch only, so `/api/blogs/*/narration` is metered at
10/min per IP for `GET` too. A listing page whose cards each polled their own narration status
would exhaust that bucket on first render and then 429 the reader's actual click. The bulk path
deliberately does not match `/api/blogs/*/narration`, so a page load spends nothing from it —
do not move it under `/api/blogs/…`.

## Failure codes

| Code | Retryable | What it means | What to do |
|---|---|---|---|
| `INSUFFICIENT_SOURCE_TEXT` | no | Best available source text is under 200 characters — the fresh scrape, the stored `fullContent` and the stored blurb are all too thin. No model call was made. | Nothing. The article genuinely cannot be summarised honestly. Usually an RSS-only source, a paywall, or a consent wall. If the source should be scrapable, fix the scraper (`content-source-add`), then delete the failed document to allow a retry. |
| `MODEL_ERROR` | yes | The model call threw or returned nothing usable. | The drawer offers "Try again". If it recurs, check the model name in `ARTICLE_SUMMARY_MODEL` and the Langfuse traces. |
| `ARTICLE_NOT_FOUND` | no | The article is gone or `visible: false`. | Nothing. |

A stored non-retryable failure is returned as-is on every later request, so a visitor
hammering the button never re-spends. That is intentional.

## Forcing regeneration

**One article** — delete its document:

```js
db.article_summaries.deleteOne({ articleId: "<articleId>" })
```

**Every article** — bump `SUMMARY_FORMAT_VERSION` in `ArticleSummaryService`. The document
id is `sha256(SUMMARY_FORMAT_VERSION + articleId)`, so a new version means a clean miss on
every article and the old documents simply become unreachable.

> **Changing the prompt without bumping that constant serves stale summaries forever.** The
> constant sits immediately above the prompt for that reason, and
> `ArticleSummaryServiceTest` recomputes the id independently to catch a drift.

## Costs and limits

- **One LLM call per article, ever** (per prompt version), enforced by the unique `_id` and
  the insert-first guard. `ArticleSummaryConcurrencyTest` proves it under load.
- **One TTS render per summary text.** Draws on the same 1,000,000 characters/month budget
  as blog narration (`NARRATION_MONTHLY_CHARACTER_LIMIT`). Exhausting it fails narrations
  with `BUDGET_EXHAUSTED`, surfaced as `UNAVAILABLE`.
- **Rate limit:** `SUMMARY_RATE_LIMIT_REQUESTS_PER_MINUTE`, default 5 per client IP,
  applied to the two `POST`s only. The status `GET`s are deliberately exempt: one drawer
  session is an initial read plus up to four long-polls, so metering reads out of the same
  small allowance would 429 a reader in the middle of the generation they just paid for.
  Reads are public, cheap and idempotent; the writes are what spend.

## Stuck in GENERATING

A summary whose generating process died is reclaimed automatically by the next request
after `aggregation.summary.generation-timeout` (3m). There is no scheduler — the reclaim is
a conditional `findAndModify` guarded on both `status` and `updatedAt`, so two callers
cannot both revive it.

If summaries are being reclaimed repeatedly, look for `Reclaimed an abandoned summary
generation` in Loki: it means the backend is restarting mid-generation, or the model call is
outrunning the timeout.

```logql
{container="simonrowe-dev-monorepo-backend-1"} |= "Reclaimed an abandoned summary"
```

## Audio reports "temporarily unavailable"

Expected when text-to-speech is not configured. Check, in order:

1. `NARRATION_ENABLED` — **defaults to `false`** in `application.yml`.
2. `GOOGLE_CLOUD_TTS_PROJECT_ID`, `_PROJECT_NUMBER`, `_LOCATION`, `_VOICE_NAME`,
   `_LANGUAGE_CODE`, `_OUTPUT_BUCKET` — `NarrationProperties.isProviderConfigured()`
   requires all of them non-blank.

Summaries themselves are unaffected by any of this: the text works, only the audio reports
unavailable.

## Metrics

- `article.summary.requests{result=generated|reused|deduped|reclaimed|failed}`
  (`failed` also carries `reason`)
- `article.summary.generation.duration`
- Narration reuses the existing `narration.*` metrics for both content types.

## Backup and restore

`article_summaries` is in `BackupService.BACKUP_COLLECTIONS` and
`RestoreService.IMPORT_ORDER_INDEPENDENT`. A restore drops each collection — taking its
indexes with it — so `RestoreService.ensureArticleSummaryIndexes()` recreates them, exactly
as `ensureFavouriteIndexes()` does for favourites. `NarrationRestoreValidator.ensureIndexes()`
does the same for the narration indexes and **must stay in step with `V021`**.

It is deliberately **not** in `ClearService.COLLECTIONS`: its parent content,
`aggregated_articles`, is not cleared either.

## Migrations

| Change unit | What it does |
|---|---|
| `V020CreateArticleSummaryIndexes` | Indexes on `article_summaries`. Auto-index-creation is off, so the annotations alone are decorative. |
| `V021GeneraliseNarrationContentType` | `narrations.blogId` → `contentType` + `contentId`, and swaps the index. Idempotent, no external I/O. |

`V021` does not touch `fingerprint`, which is the narration `_id` and the audio directory
name, so no stored MP3 is orphaned.
