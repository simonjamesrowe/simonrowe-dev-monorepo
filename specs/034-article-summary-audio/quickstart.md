# Quickstart: On-demand article summaries with audio

**Feature**: `034-article-summary-audio`

## Configuration

Added to `backend/src/main/resources/application.yml`:

```yaml
aggregation:
  summary:
    model: ${ARTICLE_SUMMARY_MODEL:gpt-5.6-luna}
    generation-timeout: 3m
    max-source-chars: 12000

rate-limit:
  summary:
    requests-per-minute: ${SUMMARY_RATE_LIMIT_REQUESTS_PER_MINUTE:5}
```

Nothing new is required in `.env` — every value has a default. Two existing settings do
matter:

| Variable | Effect if unset |
|---|---|
| `NARRATION_ENABLED` | Defaults to `false`. Summaries work; **summary audio reports "temporarily unavailable"**. |
| `GOOGLE_CLOUD_TTS_*` | Same — `NarrationProperties.isProviderConfigured()` needs project id, project number, location, voice, language and bucket all non-blank. |

Confirm both in production before shipping the audio increment.

## Run it locally

```bash
# Start the stack (sources env vars from .env files)
./scripts/start.sh

# Mongock runs V020 and V021 on startup; watch for them
# (set MONGOCK_ENABLED=false to skip, but then indexes are missing)
```

The two change units are:

- `create-article-summary-indexes` (order `020`) — indexes on the new collection
- `generalise-narration-content-type` (order `021`) — `blogId` → `contentType` + `contentId`

Verify the migration landed:

```bash
mongosh simonrowe --quiet --eval '
  printjson(db.narrations.findOne({}, {contentType:1, contentId:1, blogId:1}));
  printjson(db.narrations.getIndexes().map(i => i.name));
  printjson(db.article_summaries.getIndexes().map(i => i.name));
'
```

Expect `contentType: "BLOG"`, a `contentId`, **no** `blogId`, and
`idx_narration_content_updated` present with `idx_narration_blog_updated` gone.

## Verify end to end

### 1. Nobody has a summary yet

```bash
curl -s localhost:8080/api/news/summaries/ids
# []
```

### 2. Unauthenticated write is refused, unauthenticated read is not

```bash
ARTICLE=$(curl -s 'localhost:8080/api/news?size=1' | jq -r '.content[0].id')

curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  "localhost:8080/api/news/$ARTICLE/summary"
# 401

curl -s "localhost:8080/api/news/$ARTICLE/summary" | jq -r .state
# NOT_REQUESTED
```

### 3. Generate one

```bash
# TOKEN: any valid Auth0 access token — not admin-role gated
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/news/$ARTICLE/summary" | jq '{state, version, model, body}'
```

Expect `state: "READY"` after ~15–30 s, with `body` holding 4–6 paragraphs of neutral
third-person Markdown, no heading, and no restatement of the title.

An article whose source text is under the 200-character hard floor instead returns
`state: "FAILED"`, `failureCode: "INSUFFICIENT_SOURCE_TEXT"`, `retryable: false` — and
makes **no** model call. Confirm that by watching the log for
`No usable source text for ...`.

### 4. The second caller pays nothing

```bash
time curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/news/$ARTICLE/summary" | jq -r .state
# READY, well under a second

curl -s localhost:8080/api/news/summaries/ids | jq
# ["<the article id>"]
```

### 5. Concurrent callers produce exactly one summary

```bash
for i in 1 2 3; do
  curl -s -o "/tmp/sum-$i.json" -w "%{http_code} " -X POST \
    -H "Authorization: Bearer $TOKEN" \
    "localhost:8080/api/news/$ARTICLE/summary" &
done
wait; echo
# one 200 and two 202 (order not guaranteed)

mongosh simonrowe --quiet --eval \
  'print(db.article_summaries.countDocuments({articleId: "'"$ARTICLE"'"}))'
# 1
```

### 6. Long-poll wakes on a state change

```bash
# Returns immediately: version has already moved past 1
curl -s "localhost:8080/api/news/$ARTICLE/summary?afterVersion=1&waitSeconds=25" \
  | jq '{state, version}'

# Holds up to 25s: version matches and the state is non-terminal
# (run against an article mid-generation)
```

### 7. Summary audio

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/news/$ARTICLE/summary/narration" | jq '{state, version}'
# 202 QUEUED (or 503 UNAVAILABLE when NARRATION_ENABLED=false)

curl -s "localhost:8080/api/news/$ARTICLE/summary/narration?afterVersion=1&waitSeconds=25" \
  | jq '{state, audioUrl, durationSeconds}'
# eventually READY with /uploads/narrations/<fingerprint>/narration.mp3

curl -s -o /dev/null -w '%{http_code} %{content_type}\n' \
  "localhost:8080/uploads/narrations/<fingerprint>/narration.mp3"
# 200 audio/mpeg
```

### 8. Blog narration is unchanged

The whole point of the generalisation is that this still behaves exactly as before,
including the **public** `POST`:

```bash
BLOG=$(curl -s 'localhost:8080/api/blogs?size=1' | jq -r '.content[0].id')

curl -s -X POST "localhost:8080/api/blogs/$BLOG/narration" | jq '{state, version}'
curl -s "localhost:8080/api/blogs/$BLOG/narration" | jq '{state, audioUrl}'
```

Any previously generated blog MP3 must still resolve at its original path — the
fingerprint is unchanged, so the path is too.

## Verify in the browser

```bash
./scripts/start.sh    # frontend on :5173
open http://localhost:5173/news-events
```

1. **Logged out**, a card with an existing summary shows **Read summary** and opens the
   drawer with no login prompt. A card without one shows **Summarise** and opens the login
   popup.
2. Dismiss the login popup — no `POST` fires (check the network tab). This is the
   `auth0-react`-resolves-on-cancel trap that `useEnsureAuthenticated` guards.
3. **Logged in**, choose **Summarise**: the drawer shows a generating state, then the
   prose, above the **"AI-generated summary"** disclosure label.
4. The drawer shows, in order: source name and date; the title linking to the original; the
   disclosure label; the prose; the audio panel; "Read the original"; the heart.
5. Escape, a click on the overlay, and the close button all dismiss it. The page behind
   keeps its source filter, its loaded pages and its scroll position. Background scrolling
   is locked while it is open.
6. Start audio playing, then close the drawer — playback stops (the `<audio>` element is
   unmounted).
7. Event cards in the timeline have **no** summary control.

## Tests

```bash
cd backend && ../gradlew test                 # all backend tests
cd backend && ../gradlew checkstyleMain checkstyleTest
cd frontend && npm test                       # vitest
cd frontend && npm run lint
```

Two suites are the regression nets for the refactors and must pass **without being
rewritten to fit the new code**:

- `frontend/src/components/blog/BlogNarration.test.tsx` — untouched
- `backend/.../agents/ArticleSectionWriterTest.java` — unchanged assertions

`NarrationBackupCoverageTest` will need one edit: its `new Narration(...)` call at line ~84
gains the `contentType` argument. That is a compile-time break by design — loud, not
silent.

## Rollback

Both change units declare `@RollbackExecution`. `V021`'s rollback restores `blogId` from
`contentId`, unsets both new fields, and swaps the indexes back. `V020`'s drops the two new
indexes. Neither deletes data. Rolling back the code without rolling back `V021` leaves
`BlogNarrationService` reading a field that no longer exists — so roll back the migration
first, or forward-fix.
