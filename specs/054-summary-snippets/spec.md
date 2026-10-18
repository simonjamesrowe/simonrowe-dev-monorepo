# 054 — Snippets from news summaries

Status: **built, in one pull request.** §6 and §9 record where the build differs from the
first plan, and §10 records the answers to the open questions.
Mockup: [`mockup/index.html`](mockup/index.html). Open it in a browser. You can select text in it.

## 1. The ask

On News & Events, a signed-in Simon opens the AI summary of an article (usually one he has
hearted), selects passages, and keeps them as **snippets**, adding a note and tags if he wants.
Every snippet is collected in one searchable place in the admin console, ready to copy into a
talk, a deck or a post.

## 2. What exists today

| Piece | Where | What matters here |
| --- | --- | --- |
| Summary | `article_summaries`, `com.simonrowe.summary` | One per article, shared by everyone, markdown `body`, id = `sha256(SUMMARY_FORMAT_VERSION + articleId)`. Changing the format version produces a new summary with different text. |
| Summary drawer | `components/news/NewsSummaryDrawer.tsx` | Renders `body` with `react-markdown` inside `.news-summary__prose`. Every place that opens a summary uses this drawer: cards, `?article=` deep links, search results and the favourites view. |
| Favourites | `favourites`, `/api/favourites/**` | **Global, not per user.** Every visitor sees the same hearts. The news page has a favourites-only toggle (`favouritesOnly`). |
| Admin | `/api/admin/**` → `DEV_PORTAL_ADMIN`; `useAdminRole()` in the browser | An existing gate we can reuse unchanged. |

## 3. What it looks like

### 3.1 Making a snippet (News & Events → Favourites → open a summary)

1. Simon switches to **Favourites** and opens a card's summary. For an admin, cards that already
   have snippets show a small `✂ 3` badge, so he can see what he has already worked through.
2. He selects text in the summary. A small toolbar appears just **below** the selection, so it
   clears iOS's own callout, which sits above:
   **Highlight** · **Add note** · **Copy with citation**.
3. **Highlight** saves the snippet straight away and marks the text in place. **Add note** saves
   it and opens a popover with a note field and tags. Tags autocomplete from the ones he has
   used before.
4. Clicking an existing highlight opens the same popover, where he can edit the note and tags,
   copy the snippet or delete it.
5. Under the summary, a **Your snippets (n)** section lists this article's snippets in reading
   order, so they are visible without hunting for the marks.

Nobody but an admin sees any of this. Everyone else gets today's drawer, and the snippets API is
never called for them.

**Hearted articles are where this is used, but not the only place it works.** The drawer is the
same everywhere, so snippets are available on any summary. Limiting them to hearted articles
would mean an extra check that protects nothing. §10 (Q1) covers hearting an article
automatically.

### 3.2 Finding them again (Admin → Snippets, `/admin/snippets`)

* A search box over the quote, note, tags, article title and source.
* Tag chips and a source dropdown that each select several values, a date range on the day
  the snippet was kept, and a sort (newest snippet or newest article).
* Each card shows the quote, the note under it, tags, then the source line (source · article
  title · date, linked to the summary).
* Actions on one card: **Copy** (quote and citation), **Open summary**, **Edit**, **Delete**.
* Tick several cards, or "Select all shown", then **Copy as Markdown** or **Download .md**. A tag
  becomes a talk's reading list: filter on `#platform-eng-talk`, select all, download.
* A right-hand panel edits the selected snippet's note and tags.

### 3.3 The citation

The quote is **AI-written text about someone else's article**, not words from the original
author. If a slide presents it as a quote from Meta's engineers, that misattributes it. The
copied form says what it is:

```markdown
> Requirements and UAT remain human responsibilities; guardrails are written before the
> code exists; and review models should differ from the model that produced the implementation.

— AI summary of "Human PR Review Is Next" (William Martin, TLDR Dev, 12 Oct 2026)
  https://… (original article)

Note: good framing for the "review the what, not the how" slide
```

The original article's URL goes in the citation, not the summary's, so whoever uses the snippet
can go to the source and quote the author instead.

## 4. Key decisions

1. **Private to the admin role, no per-user ownership.** Routes live under
   `/api/admin/snippets/**`, so the existing `hasRole(DEV_PORTAL_ADMIN)` rule covers them with no
   change to `SecurityConfig`. Each snippet records `createdBy` (the Auth0 subject), as meal
   ratings do, so adding owners later is a query change rather than a migration. Unlike
   favourites, nothing here is public.
2. **A snippet is a copy of the text, not just a pointer into the summary.** It stores the quote
   verbatim, so it still makes sense after the summary is regenerated, the article is pruned or
   the format version changes. This is also the whole point: it is going into a deck.
3. **Highlights are re-found by text, not by character offsets.** We store a W3C-style text-quote
   selector: `exact` plus ~32 characters of `prefix` and `suffix`, taken from the rendered text,
   not the markdown source. When the drawer opens, each snippet is searched for in the prose's
   text: first `prefix+exact+suffix`, then `exact` on its own. If neither is found, the snippet
   is listed with **"no longer in the current summary"** and is not marked in the text. Offsets
   would go wrong after the first regeneration without anyone noticing.
4. **Marks are drawn with the CSS Custom Highlight API (`CSS.highlights`), not by wrapping text in
   `<mark>`.** React owns the prose's DOM, and inserting elements into what `react-markdown`
   rendered breaks on the next render. Highlight ranges change no DOM at all. It is supported in
   Chrome, Safari 17.2+ and Firefox 140+. **We check for the feature at runtime**: where it is
   missing, the marks are skipped and the "Your snippets" list still works. Clicking a mark uses
   `caretPositionFromPoint`/`caretRangeFromPoint` to find which range was hit.
5. **A selection can cross paragraphs**, and the quote keeps the paragraph break (`\n\n`). A
   selection that starts or ends outside `.news-summary__prose` (in the title or the disclosure)
   is clipped to the prose, and nothing appears if no prose is left. Quotes are limited to 2,000
   characters, with a message beyond that rather than silently cutting the text.
6. **Overlapping snippets are allowed.** Two snippets can share text, and the overlap is drawn
   once, slightly stronger. Merging them would mean guessing which note to keep.
7. **Article details are copied onto the snippet when it is made** (`articleTitle`, `sourceName`,
   `author`, `originalUrl`, `articlePublishedAt`). That keeps the citation intact if the article
   is pruned or re-scraped, and lets search and filtering work on one collection with no join.
8. **Search uses Mongo, the same way `ArticleQueryService` does**: each term is `Pattern.quote`d,
   terms are ANDed together, fields are ORed, with the same caps on term count and length. A few
   hundred snippets do not justify Elasticsearch, and a literal match is what a "find that quote"
   search needs. Semantic search is in phase 3.
9. **Tags, not folders or collections.** One snippet often belongs to two talks. Tags are
   lower-case, trimmed and de-duplicated on the server. `GET /api/admin/snippets/tags` returns
   each tag with its count for the chips and the autocomplete.
10. **Unhearting an article never deletes its snippets.** They are separate records: a heart says
    the article is worth reading, a snippet says this passage is worth using.
11. **Snippets are backed up.** `snippets` goes into `BackupService.BACKUP_COLLECTIONS` and
    `RestoreService.IMPORT_ORDER_INDEPENDENT`, and `RestoreService` re-creates its indexes by
    calling the change unit's `createIndexes`. A backup-coverage test, like
    `MealsBackupCoverageTest`, pins this. These are hand-written notes that cannot be re-derived.

## 5. Data model

Collection `snippets`, created along with its indexes by `V061CreateSnippets` (indexes only, no
data).

```java
@Document(collection = "snippets")
public record Snippet(
    @Id String id,
    SnippetSourceType sourceType,   // ARTICLE_SUMMARY for now; BLOG later fits without a migration
    String contentId,               // aggregated article id
    String summaryId,               // the summary it was taken from (stale detection)
    String exact,                   // the quote, rendered text
    String prefix,                  // ≤32 chars before
    String suffix,                  // ≤32 chars after
    String note,                    // optional, plain text, ≤2,000
    List<String> tags,              // normalised
    String articleTitle,
    String sourceName,
    String author,
    String originalUrl,
    Instant articlePublishedAt,
    String createdBy,               // Auth0 subject
    Instant createdAt,
    Instant updatedAt) {}
```

Indexes: `{contentId: 1, createdAt: 1}` (the drawer's lookup), `{createdAt: -1}` (the console
listing), `{tags: 1}` (multikey, for tag filters and counts).

## 6. API (all under `/api/admin`, admin role)

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/snippets?contentId=` | Snippets for one article, for the drawer. |
| `GET` | `/snippets/counts` | `{articleId: count}` for every article with at least one snippet. One request serves every card on the page; there are a few hundred at most. |
| `GET` | `/snippets?q=&tag=&source=&from=&to=&sort=&page=&size=` | Console search. `tag` and `source` repeat, and are read with `getParameterValues` (see the comma-splitting trap in `news-search-and-source-filter`). `from`/`to` are days, inclusive, on when the snippet was kept. |
| `GET` | `/snippets/tags` | `[{tag, count}]`, most used first. |
| `GET` | `/snippets/sources` | Every publisher a snippet came from, for the source filter. |
| `POST` | `/snippets` | `{contentId, exact, prefix, suffix, note?, tags?}` → 201. **The server fills in the article fields and `summaryId` itself**, from `aggregated_articles` and the current `READY` summary. 404 if there is no such article, 409 if no summary is `READY`, 422 if `exact` is not in that summary's text. This stops a client saving a quote the summary never contained. |
| `PUT` | `/snippets/{id}` | Edit the note and tags only. The quote cannot be changed, because a changed quote is no longer a quote. |
| `DELETE` | `/snippets/{id}` | 204, also for one already gone (use `handleNoContent` on the client, not `handleResponse`; see the known 204 bug). |

Every snippet in a response carries `inCurrentSummary` (the article's current summary still
contains the quote) and `articleAvailable` (the article can still be opened). Both are worked
out per request: one read of the articles and one of the summaries per list.

`POST` validates by rendering the summary's markdown to text with commonmark
(`SnippetText.renderedText`) and checking the quote is in it, with whitespace collapsed on both
sides. The browser's quote and the server's rendering differ only in whitespace: the browser
joins blocks with a blank line, the server with a space.

**No export endpoint.** The first plan had `GET /snippets/export`. Copying a passage before it is
saved needs the citation format in the browser anyway, so download builds the file there too,
with the same formatter as every copy (`snippetCitation.ts`). One formatter cannot drift from
itself.

## 7. Frontend

* `components/news/snippets/SummarySnippets.tsx`: the whole drawer layer. It listens for
  `selectionchange` (settled for 200ms), clips the selection to the prose, shows the toolbar,
  draws the highlights, and lists "Your snippets". `SnippetPopover.tsx` edits the note and
  tags; `SnippetTagInput.tsx` is a free-text tag field (the admin `TagInput` only picks from a
  fixed list).
* The token function is read through a ref in every snippet fetch, so a fetch runs when its
  inputs change and never because the token function's identity changed. A test mock that
  hands out a new function per render otherwise loops forever (the first full run was killed
  for exactly that).
* `snippetText.ts`: the text model. It reads the prose into one string (blocks joined by a
  blank line, react-markdown's newline nodes between blocks skipped), turns a selection into a
  quote, finds a quote again, and maps text offsets back to a `Range`.
* Highlights (in `SummarySnippets.tsx`): finds each snippet in the prose's text nodes and registers
  `new Highlight(...ranges)` under `CSS.highlights.set('snippet', …)`. It **removes the highlight
  when the drawer unmounts** and re-runs whenever the body or the snippets change.
* `NewsSummaryDrawer` gets one optional slot, `snippetLayer`, in the same way as `audioPanel`,
  plus an optional `proseRef` on the rendered summary, so the drawer itself still knows nothing
  about snippets. `NewsEventsPage` fills the slot only when `useAdminRole()` is true.
* The toolbar and the popover are portalled to `<body>` above the drawer's overlay. Escape in the
  popover stops the native event, because the drawer closes on Escape from a `document` listener.
* `pages/admin/SnippetsAdmin.tsx` plus an `AdminLayout` entry, **Snippets**, with Lucide
  `Highlighter`, placed after **News & Events**.
* CSS: BEM `snippet-*` in `styles.css`. The highlight colour is the AI accent at about 30%
  opacity (`::highlight(snippet)`), so it reads as "you marked this", not a link.
* **Tests that render the news page must mock `services/snippetsApi`**, as with `portfolioApi`
  and `playlistApi`.

## 8. Edge cases

| Case | Behaviour |
| --- | --- |
| Summary regenerated or format version bumped | The snippet survives. It is re-found by its text if it can be, and otherwise listed as "no longer in the current summary". |
| Article pruned | The snippet keeps its copied citation. "Open summary" is disabled, with a tooltip. |
| Summary `GENERATING`/`FAILED` | No selection toolbar. Existing snippets are still listed. |
| Selection is only whitespace, or outside the prose | No toolbar. |
| The same text highlighted twice | The existing snippet opens instead, with "Already highlighted. Edit it here.", when it has the same `exact` at the same position. Overlapping but different selections are separate snippets. |
| Phone | The drawer is already a full-screen sheet. The toolbar sits under the selection, and the popover becomes a bottom sheet. |
| Signed out, or not an admin | Today's drawer, unchanged, and no snippet requests at all. |

## 9. Delivery

One pull request: the API, backup and restore, the drawer (selection toolbar, highlights,
popover, "Your snippets", card badges) and Admin → Snippets.

**Later, if wanted**:

* Semantic search ("anything about verification loops") by embedding snippets with the existing
  `EmbeddingService`, skipping unchanged content.
* A private MCP tool (`/api/snippets/mcp`, personal tokens, the same approach as the meals MCP
  server, **never** a `ToolCallbackProvider` bean) so Claude Code can pull a tag's snippets while
  drafting a deck or a blog post.
* Snippets from blog posts and Term Time documents (the `sourceType` field already allows for
  it).
* An **Export as slides** option that builds a `.pptx` of one quote per slide with its citation.
* Notes on a whole article rather than a passage (Q4).

## 10. Decisions on the open questions

* **Q1. Making a snippet does not heart the article.** Favourites are public, so it would publish
  a heart. After the first snippet on an article that is not hearted, the drawer offers a
  one-tap "Heart it" instead.
* **Q2. "Copy with citation" in the drawer copies without saving.** It says so ("not saved").
* **Q3. No highlight colours.** Tags do the same job and can be searched.
* **Q4. Notes on a whole article: not built.** Listed under §9 for later.
