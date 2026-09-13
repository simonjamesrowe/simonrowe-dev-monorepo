# Term Time (school assistant)

`simonrowe.dev/school`. A public assistant answering questions about Kilmorie Primary School
(Lewisham) from the school's own published data, with a private tier over the school mailbox.

Spec and plan: `specs/047-term-time/`.

**Production hostname: `term-time.simonrowe.dev`**, served by the same `frontend` container as
the main site — Term Time is a second Vite entry point, not a separate deployment. The proxy
block maps only `location = /` onto `/school/`; everything else passes through unrewritten,
because both bundles share `/assets/` and rewriting all paths would send `/assets/school-*.js`
to a path that does not exist. The page would render blank with nothing useful in the logs.

`https://simonrowe.dev/school/` continues to work as well.

**Not affiliated with the school.** The footer says so and the system prompt says so. If the school
ever asks for it to stop, the kill switch is `SCHOOL_ENABLED=false` and a redeploy.

## The two tiers

| Tier | Who | Sources |
|---|---|---|
| `PUBLIC` | anyone | calendar feed, website pages, PDFs |
| `RESTRICTED` | nobody — unreachable from the browser | everything above, plus school email |

Enforcement is at **two independent boundaries**, both derived from one `SchoolAudience` resolved
server-side from the JWT:

1. **Tool boundary** — `SchoolTools` is constructed per request with the audience baked in. The
   tier is not a tool parameter, so a prompt injection in a retrieved newsletter cannot ask for it.
2. **Retrieval boundary** — `SchoolRetrievalService` is the only caller of the vector store and
   applies a `visibility` filter on every search.

Plus an **output-side backstop**: an answer to an anonymous visitor that names anyone outside the
published staff list is withheld. That catches a mistake in either boundary above.

`SchoolCredentialConfinementTest` and `SchoolToolsTierTest` fail the build if any of this drifts.

## Fail-closed rules worth knowing before changing anything

- `Visibility` defaults to `RESTRICTED` in the record's compact constructor. Content cannot become
  public through a missing value, a null, or a forgotten branch.
- A classifier only ever writes `proposedVisibility`. Only `withApproval` writes `PUBLIC`.
- `withNameGateBlocked()` **outranks approval** — `withApproval` on a blocked document is a no-op.
  An approver cannot click past the gate; that is what makes it a gate rather than advice.
- An empty `StaffDirectory` treats every name as non-staff, so the gate blocks everything until the
  crawl has run. `SchoolIngestScheduler.primeStaffDirectory()` runs at `ApplicationReadyEvent` for
  this reason — leaving it to the first nightly crawl would make the assistant withhold public
  answers for a day.
- `SCHOOL_DAILY_TOKEN_BUDGET` of `0` means **answer nothing anonymously**, not "unlimited".

## Sources and precedence

`CALENDAR_FEED` > `EMAIL` > `WEBSITE_PAGE` > `PDF`, encoded as `SchoolSourceType`'s declaration
order and applied by `SchoolEventWriter`.

This is not theoretical. The school's own term-dates page still shows the **previous** academic
year while its calendar feed carries the current one, and both are ingested. `SchoolIds.eventId`
keys on academic year, date and normalised title — deliberately *not* on the source — so the two
arrive as writes to the same row and the more authoritative one wins.

**Reordering `SchoolSourceType`'s members silently changes which source wins.** There is no other
declaration of the ordering.

## The sitemap does not list every page

`SchoolWebsiteCrawler` enumerates pages from `/googlesitemap.asp`. That is what keeps the crawl
bounded and out of the calendar's infinite date-parameterised URL space, and it has one cost:
**a page missing from the sitemap is invisible to Term Time, permanently and silently.**

Two things close that gap, and they are separate on purpose: a seed list of known-missing pages
(below), and one hop of same-host link following out of those seeds (next section).

Found live on 2026-09-10. The sitemap returns 218 `<loc>` entries and **not one of them is a
year-group page**, while all seven return `200`:

```
/year-group-pages   /year-one   /year-two   /year-three   /year-4   /year-five   /year-six
```

Note the slugs are inconsistent (`/year-4` beside `/year-six`) and the hub's children are
**top-level paths** — `/year-group-pages/year-6` is a 404.

That is where the school publishes each year's **class teachers** and its **per-class PE days**,
which are among the most-asked things on the site. Term Time had neither, and said so accurately
("the school's PE page confirms all children have weekly PE but does not list days by year
group") because `/curriculum/subjects/pe` — the generic page — *is* in the sitemap. Nothing
failed, nothing was logged, and every test passed.

The seed list is `school.extra-page-urls`, defaulted in
`SchoolProperties.DEFAULT_EXTRA_PAGE_PATHS` and unioned into `listPages()`. Four things about it:

- **Extras come first in the returned list.** `MAX_PAGES` (250) caps the whole thing, and the
  sitemap's tail is years of old sports reports. Today 218 fits, so nothing is dropped either
  way; the ordering is what keeps that true as the school adds news.
- **An unreadable sitemap still returns nothing**, even with extras configured.
  `SchoolIngestService` reads an empty list as a source failure and records it, and quietly
  returning seven pages would turn a visible outage into a crawl that reports success while
  skipping 96% of the site.
- **Setting the variable replaces the default rather than adding to it.** Entries may be absolute
  URLs or paths resolved against `website-base-url`.
- **A configured page that cannot be fetched logs a WARN**, where a missing sitemap page stays at
  DEBUG. This is the only rot detector: the CMS's slugs are inconsistent enough that a rename is
  plausible, and a renamed page would otherwise take a whole year group's information out of the
  corpus with nothing to show for it.

The hub page is seeded alongside its children: it carries nothing but links today, so it is nearly
free, and it is where a renamed or newly added year page shows up first.

### One hop from the seeds, and no further

The seeds are not the whole answer, because the year pages link on to material that is not on
them. *"What are the spellings for this week?"* is answered on `/year-six-home-learning` — absent
from the sitemap, **not** a child of `/year-group-pages`, and reachable only as a link from
`/year-six`. The spelling list itself is a PDF linked from there in turn.

So `SchoolIngestService.ingestWebsite()` follows same-host links found in the **content** of a
configured extra page, one hop. Six things decide how far that goes:

- **Same host is the whole of the security rule** (`SchoolLinkFilter.isCrawlableWebsitePage`), and
  it is doing real work: `/year-six-home-learning` links to `primaryhomeworkhelp.co.uk`,
  `natgeokids.com`, `dkfindout.com` and `kids.britannica.com` as Ancient Greece research for the
  children. Following those turns a school-information crawler into a general web spider and puts
  third-party pages into a corpus that answers in the school's voice.
- **Links come from the STRIPPED document**, after `nav`, `header` and `footer` are removed — the
  single most effective filter in the path, not a detail. The CMS emits semantic navigation and
  repeats ~60 same-host links on every page: measured on `/year-six`, **68 same-host links in the
  raw markup become 12** once the furniture is gone. Following the raw set makes one hop from any
  page equivalent to crawling the whole site.
- **Only from the seeds, so only one hop.** The sitemap is the school's own 218-page statement of
  what its site contains; following links from all of it would mostly rediscover those 218 plus
  the `/school-news/` and `/photo-gallery/` long tail, at ten seconds a page. **Adding a seed is
  how you widen the crawl** — one config change with the cost visible in the page count, rather
  than a depth setting whose cost depends on someone else's markup.
- **Pages are stored under their declared `<link rel="canonical">`.** Load-bearing, because this
  CMS serves **every page under two URLs**: `/year-six-home-learning` and
  `/page/?title=Home+Learning&pid=158` return byte-identical text, and `SchoolIds.documentId` keys
  on the URL — so ingesting both stores and embeds the same text twice, which surfaces as
  duplicate search results rather than as an error. Sitemap-only crawling never met this. Discovery
  meets it immediately, because **the only link from `/year-six` to home learning is the ugly
  form** — the choice is not "canonicalise or avoid ugly URLs", it is "canonicalise or lose the
  page".
- Cookie/privacy/accessibility pages and the bare homepage are excluded, sharing
  `SchoolLinkFilter`'s existing noise list so there is one list rather than two that drift. Note
  `privacy` and `cookie` are broader than the `privacy-policy`/`cookie-policy` they replaced —
  the school's page is at `/privacy-cookies`, which neither hyphenated form matched.
- `MAX_CRAWL_PAGES` (400) is a backstop against a link cycle, not a tuning knob.

**Discovery beats hardcoding here, and there is a concrete proof of it**: `/year-4`'s home-learning
page is `/year-**four**-home-learning`. A hand-written list would have guessed `/year-4-home-learning`
and got a 404. Measured 2026-09-10: year six, five, three and 4 each link to a home-learning page;
year one and year two publish none.

### PDF discovery runs on every crawl, not only on changed pages

`ingestPdfsLinkedFrom` used to sit inside the `if (result.changed())` branch. `contentHash` is
computed over the extracted **text**, so a page that swaps which PDF it links to without changing a
word of its prose reports UNCHANGED — and that is not a corner case, it is **the weekly spelling
sheet**. The CMS names uploads by content hash, so a new sheet is a new URL sitting behind link text
that still reads "Year 6 Spring 1 spellings". Gated inside `changed()`, the first sheet of the term
is ingested and every later one silently skipped, which presents as Term Time confidently reciting a
month-old spelling list.

It is affordable on every crawl because the page HTML is already in hand and
`ingestPdfsLinkedFrom` now skips any PDF URL it has already stored — without that skip it would be
~133 extra fetches a night, each with its own ten-second pause.

**The matching limit:** a PDF *replaced at the same URL* is never re-read. That is safe against this
CMS specifically, because of the content-hash file naming — but it is a property of their uploader,
not of anything here, and it is the first assumption to check if a stale document ever shows up in
an answer.

**Still flattened, and not addressed here:** `fetchPage` uses jsoup's `body().text()`, so the PE
table arrives as `Class Indoor Outdoor Sarah Friday Thursday Dominic Tuesday Thursday ...` — one
line, row boundaries gone. It is recoverable for a three-column table with its header adjacent,
and it is what every other page already gets. Preserving table structure would change the
extracted text of all 218 pages, so every `contentHash` changes and the whole site re-embeds; that
is a cost decision, not a tidy-up.

## Configuration

Everything is on the `backend` service only, in `${VAR:-}` empty-default form.

| Variable | Default | Notes |
|---|---|---|
| `SCHOOL_ENABLED` | `false` | Master switch. Off means no scheduled ingest, no outbound requests, no index |
| `SCHOOL_SENDER_ALLOWLIST` | `kilmorie.lewisham.sch.uk` | **Addresses, never display names** |
| `SCHOOL_SENDER_DENYLIST` | `parentpay.com` | Financial mail, excluded outright |
| `SCHOOL_INGEST_FROM_DATE` | `2026-07-01` | Earliest source date. Blank = full history — see below, blank is **not** the same as unset |
| `SCHOOL_DAILY_TOKEN_BUDGET` | `0` | Anonymous turns per day. `0` disables anonymous answering |
| `SCHOOL_CHAT_MODEL` | `gpt-5.6-luna` | |
| `SCHOOL_GUARDRAIL_MODEL` | `gpt-5-nano` | Fires every turn |
| `SCHOOL_EXTRA_PAGE_URLS` | *(blank)* | Pages the sitemap omits. Blank = the seven year-group pages; setting it **replaces** that list — see below |
| `SCHOOL_GMAIL_CLIENT_ID` / `_SECRET` / `_REFRESH_TOKEN` | *(blank)* | Phase 2 |

### The ingest cutoff applies in two places, and both are needed

`SCHOOL_INGEST_FROM_DATE` caps how far back Term Time reaches. It is enforced twice, for
different producers:

- `GmailIngestService.buildQuery()` appends `after:YYYY/MM/DD`, so old mail is never fetched.
- `SchoolEventWriter.write()` drops any event finishing before the cutoff, and
  `SchoolIngestService.calendarRangeStart()` narrows the calendar feed request to match.

**Capping Gmail alone does not cap Term Time.** The calendar feed has its own independent
`CALENDAR_LOOKBACK_MONTHS = 6`, which quietly outlived the cutoff and refilled the admin console
with the previous academic year's class trips and assemblies — 53 of them — after a full wipe and
repopulate. The writer-side check is the one that guarantees it, because it also catches a dated
fact that an in-window email states about a past date.

**It deliberately does not apply to website pages.** A page's `publishedAt` is its CMS
last-edited date, not a statement about currency: the live Term Dates page was last edited in
May 2026 and is the current one. Filtering documents on that date would delete current
information. Only *events* and *email* are capped.

**In `docker-compose.prod.yml` the default must be repeated, not left blank.** `${VAR:-}` passes
an empty **string**, which Spring resolves successfully — so the yml default never applies, the
property binds to `""`, and there is no cutoff at all. `SchoolIngestCutoffTest` pins the two
declarations to the same non-blank date.

**Never use `${VAR:?}` here.** An unset required variable fails interpolation for the *whole*
compose file, which wedges `sync-config` and takes `monitor-prod.sh`'s minutely `up -d` down with
it. `trivy-server`'s `--token` set the precedent; `SchoolCredentialConfinementTest` enforces it.

## Operator actions

1. **Auth0**: nothing to do. Term Time is entirely public — the school app has no sign-in, no
   Auth0 provider and no callback URL. The restricted tier still exists in the backend and is
   simply unreachable from the browser, which is deliberate: Gmail content has to live in *some*
   tier, and the approval queue is the only route by which any of it becomes public.

2. **Production `.env`**: add `SCHOOL_ENABLED=true` and a non-zero `SCHOOL_DAILY_TOKEN_BUDGET`.
   Without the budget the public tier answers nothing — correctly, but confusingly.
3. **Gmail credentials** (Phase 2): see below.

## Gmail credentials

Already minted (2026-09-08) into:

- `~/workspace/simonjamesrowe/termtime-gmail-oauth-client.json` (0600)
- `~/workspace/simonjamesrowe/termtime-gmail-token.json` (0600)

OAuth client `termtime-gmail` (Desktop) lives in the existing `simon-james-rowe` Cloud project —
reused deliberately, because that project was already External / In production / unverified and
already carried the restricted `auth/drive` scope, so adding `gmail.readonly` changed nothing about
its posture. A **separate client** from `simonrowe-backup` so that re-consenting Gmail can never
invalidate the Drive backup token.

### Re-minting

```bash
./scripts/termtime-gmail-auth.py
```

Loopback redirect, writes the token to a `0600` file and never prints it. Reports the authorised
mailbox so you can confirm you picked the right account.

### The traps

- **A consent screen in "Testing" issues refresh tokens that expire in 7 days.** Adding yourself as
  a test user does not help — the exemption keys on scopes. The project must stay **In production**.
  Unverified is fine at one user; verification/CASA is not required below 100 users.
- **Changing your Google password revokes any refresh token carrying Gmail scopes.** Silently. Plan
  re-consent as routine, not as an incident.
- **`historyId` has no guaranteed retention window.** A too-old cursor returns 404 and a full
  resync is the correct response — `SchoolSyncState.requiresFullSync()` treats a null cursor as
  normal, not as an error.
- **`scripts/google-drive-auth.sh` is dead code** — it uses the OOB redirect Google removed. If the
  Drive backup token ever needs re-minting, that script will not work; use the loopback pattern in
  `termtime-gmail-auth.py`.

## Corpus shape (measured 2026-09-08)

- 39 school emails since 1 July 2026; **642 all time**; 8 with attachments since July
- **One** school sender: `info@kilmorie.lewisham.sch.uk`
- Calendar carries **17 events for all of 2026/27** — term dates, five INSET days, SEN coffee
  mornings. Website newsletters stop at December 2024.

So the website answers term-structure questions well and almost nothing else. "What day is PE" and
"what is the latest on lunches" need the mailbox. **A thin corpus means refusing is the common
correct answer**, which is why the refusal behaviour is a requirement rather than a nicety.

Two selector gotchas, both learned from the live mailbox:

- `system@insighttracking.com` sends mail whose **display name is "Kilmorie Primary School"**. An
  allowlist matching display names admits it, and anything else willing to set the same name.
- Full-text searching for "kilmorie" is a bad selector — **Kilmorie Road is a street name**, so it
  matches estate agents and a local theatre.

## Cost

Prompt caching is automatic on OpenAI and needs no code, but the prefix must clear **1,024 tokens**
or nothing caches and no error is raised. `SchoolSystemPrompt.TEXT` plus the tool schemas clears it.
Verify with `Usage.getCacheReadInputTokens()` rather than assuming.

Model and cache key are set on the school `ChatClient`'s own options and **never** under
`spring.ai.openai.chat` — anything there is merged into every per-call `OpenAiChatOptions` in the
application. That merge is why `reasoning-effort` is banned from the yml.

## Why the public tier might look empty

Two things have caused this, both fixed, both worth checking first if it recurs:

1. **The name gate.** Gone entirely — see "Name suppression was removed" below. Historically it
   was the single largest cause of an empty public tier: it marked 162 of 162 crawled pages and
   98 of 99 emails restricted.
2. **The staff list URL.** `/our-school/staff` is a 404; the real page is `/our-school/our-staff`.
   This no longer gates anything, but `StaffDirectory` still feeds answers about who teaches
   what, so a wrong URL leaves those unanswerable. The symptom is one `WARN` at startup.

PDFs are ingested from links on crawled pages (`SchoolPdfExtractor`), covering both of the CMS's
URL conventions. The enrichment timetable is a PDF and is the most current document the school
publishes, so a broken PDF path shows up as "I don't know" about clubs.

## Approval workflow

`/admin/school-approvals`, gated on the site admin role — currently the *same* grant that unlocks
the restricted chat tier, since both key on `DEV_PORTAL_ADMIN`. They were separate by design
(approving what the world can see is an editorial act; reading the mailbox is not) and the code
still reads two independent constants, so splitting them again is a one-line change.

Three things happen together on approval, and missing any one produces a quiet inconsistency
rather than an error:

1. the document's tier changes;
2. every event extracted from it changes with it — otherwise a public document's dates stay
   invisible, or a revoked document's dates stay visible;
3. **the chunks are re-embedded.** `visibility` is chunk metadata and the retrieval filter reads
   the copy in Elasticsearch, not the one in Mongo. Approving without re-embedding leaves the
   document public in Mongo and restricted in the index, and the approval appears to do nothing.

Approval is now the **only** control over what reaches the public tier. Nothing re-checks the
text at approval time and nothing can refuse an approval — see below.

## Name suppression was removed

Term Time used to carry a `StaffNameGate`: a heuristic that compared capitalised word pairs
against the published staff directory, blocked any document naming someone it could not place,
and redacted names out of anonymous answers. **All of it is deleted**, on the owner's explicit
instruction, in this order of escalating scope:

1. first it was removed from website pages (it matched "Contact Us" as a person);
2. then narrowed to pupils only (it had redacted "Taylor Shaw", the catering company);
3. then removed altogether, including pupils' names.

Reinstating any part of it would be a reversal of a decision that was made three times, so do not
"restore" it as a safety improvement. What is gone: `StaffNameGate`, the `blocked` count on the
bulk-approval response, the `nameGateBlocked` field on the documents API, the "Publish anyway"
override and the "Name-gate blocked" filter. `SchoolDocument.nameGateBlocked` survives as a
never-written field so that stored documents still deserialize; it is inert.

The control that remains is the tier: email arrives `RESTRICTED` and a human approves it. That was
always doing the real work — the gate blocked so indiscriminately that it was noise, and its
override button was pressed as a matter of routine, which is the definition of a control that has
stopped controlling anything.

## Events come from four sources, not one

`SchoolEventWriter` is the single write point, and `SchoolSourceType` declaration order decides
who wins a collision. What feeds it:

| Source | Extractor | Notes |
|---|---|---|
| Calendar feed | none — rows are already structured | Authoritative for term structure |
| Email bodies | `SchoolEventExtractor` | Where most of school life is announced |
| Email PDF attachments | `SchoolEventExtractor` | The newsletter often *is* the attachment |
| Website pages and site PDFs | `SchoolEventExtractor` | **Added late** — see below |

**The website produced no events at all for the first four phases.** Only the email path called
the extractor, so the enrichment timetable, the term-dates PDF and the lunch menu — the most
current documents the school publishes — contributed nothing to "what is on this week", while
sitting in the index as prose. `SchoolIngestService.extractEventsFrom` closes that.

Two filters run **before** the model, in that order, because both are free and it is not:

1. `DATE_LIKE` — text with no date-like token cannot yield a dated event. Purely a cost control;
   a false positive costs one call that returns nothing. It exists because ~160 website pages are
   re-read on every crawl and most ("Our Vision", "Online Safety") contain no date at all.
2. the ingest cutoff, applied to the model's **output** rather than to the document — a page last
   edited in 2022 can still announce a date this term.

## Answering "what is on this week" reads both stores

`getEventsBetween` returns the dated rows **and** a prose search keyed on the titles it just
found. An event row carries a date and a title; the letter announcing it carries the time, the
venue, what to bring, the booking link and often a PDF, and those live in Elasticsearch rather
than in `school_events`. Answering from the event table alone produced a bare list of titles with
the detail one search away — the same failure mode as the website PDFs above, one layer up.

## One kind of link is followed automatically

The rule is still "record links, never follow them": an email can link anywhere, and the ingester
must not become a general crawler pointed at whatever arrives. `SchoolLinkFilter.isAutoFetchable`
carves out exactly one case — a **newsletter path on the school's own host** — because the website
crawl already reads that host wholesale, so following one reaches nobody new.

Deliberately narrow. Widening it to the whole school domain is defensible on the same reasoning
and is a one-line change, but the parent portal also serves per-family pages, so a blanket rule
would start fetching those. Everything on any other host still waits for a person, and the
homepage footer link is filtered out before it ever becomes a row.

## What ingestion actually does

| Source | Text | Attachments | AI classification | Dated events |
|---|---|---|---|---|
| Calendar feed | n/a | n/a | not needed (already public) | yes, mapped directly |
| Website pages | yes | linked PDFs downloaded | not needed (already public) | no |
| Website PDFs | yes | n/a | not needed | no |
| **Email** | yes | **PDF attachments downloaded and extracted** | yes, proposes a tier | **yes, via Embabel** |
| **Email PDFs** | yes | n/a | yes, inherits then re-proposes | **yes** |

Event extraction uses Embabel's `Ai` (`SchoolEventExtractor`), matching `ArticleSectionWriter`
and `DigestComposer`. It calls `createObjectIfPossible` rather than `createObject`: most emails
contain no dated facts, and that has to be an ordinary null rather than an exception per
newsletter. Individual events with unparseable dates are dropped one at a time, so one "TBC"
does not discard the eleven real dates beside it.

**A PDF attachment becomes its own document**, not text appended to the email — it gets its own
tier decision, its own chunks and its own citation. It inherits the parent email's tier, so an
attachment can never be more visible than the message that carried it.

**Backfill caveat:** `contentHash` makes an unchanged email skip re-ingest entirely, so adding a
new extraction step does *not* retroactively apply. To backfill, delete the affected documents
(`db.school_documents.deleteMany({sourceType:"EMAIL"})`) and restart; the next sync re-reads them.

### Attachment bytes are the one piece of state outside Mongo and Elasticsearch

The original PDF of an email attachment is a file on disk, under `school.attachment-path`, named
by document id and served by `SchoolAttachmentController` after a tier check. Everything *about*
it — the document, the chunks, the `attachmentUrl` in the chunk metadata that becomes the
citation — lives in Mongo and Elasticsearch and is backed up with them. The bytes are not.

Losing them is completely silent, and it happened. `docker-compose.prod.yml` had no volume for
that directory, so it resolved to `/workspace/school-attachments` in the buildpack image's
**writable layer**, right next to the `uploads` volume that does survive — and `backend` is in
`FACTORY_DEPLOY_RECREATABLE`, so every deploy emptied it. The symptom is an answer that cites a
PDF and a link that returns **404**, from an endpoint whose only two other 404s (unknown id,
restricted document) look identical. Nothing is logged.

There are now two halves to the fix and both matter:

- A named `school-attachments` volume, with `SCHOOL_ATTACHMENT_PATH` set **absolutely** to match
  the mount point. Deliberately not inside `backend-uploads`: that path is served by a
  `ResourceHandlerRegistry` mapping with no authorisation at all, so a restricted attachment
  stored there would be readable by anyone who knew a document id.
  `SchoolAttachmentPersistenceTest` reads the compose file and pins all three facts.
- `GmailIngestService.ingestAttachments` runs **before** the parent email's `changed()` check
  and re-fetches whenever the file is missing. Without that a lost attachment is permanent: the
  email never changes, so it is skipped forever while its citation stays in the index. The
  ordinary case still costs nothing — the attachment's document id is derivable from the message
  and attachment ids alone, so a file already on disk is recognised for the price of one `stat`,
  with no download and no text extraction.

### An attachment document is keyed on the FILENAME, never on Gmail's attachment id

Gmail's `attachmentId` is an opaque handle minted per `messages.get` response, **not a durable
identifier**. The same PDF on the same message comes back under a different id on a later fetch.
`SchoolIds.documentId(PDF, "gmail:<messageId>:<attachmentId>")` treated one as a primary key, and
every consequence was silent:

- a brand-new document each pass instead of the unchanged one, so a **paid embedding and a paid
  classifier call** every time;
- a duplicate row in the approval queue;
- and worst, the replacement inheriting the parent email's tier — which **discards an approval a
  human had already given**, leaving the approved copy orphaned and the live copy restricted.

Measured in production on 2026-09-10: fourteen attachments re-ingested on a sync reporting
`0 of 43 messages new or changed`, and an approved letter whose citation 404'd because the live
document was a restricted duplicate of the approved one. It stayed hidden while attachments were
only visited on a *changed* email — which never happens — and surfaced the moment the repair pass
above started running every sync.

`GmailIngestService.attachmentRef` now keys on `gmail:<messageId>:<filename>`. The filename is
stable across fetches and unique within a message in practice; two attachments sharing one
collapse onto a single document, which is right far more often than not. A blank filename (legal
on a part declaring `application/pdf`) falls back to the declared size, because an empty tail
would collide across every such attachment on the message.

`V041RekeyGmailAttachmentDocuments` collapses the duplicates already in production. Three things
about it are load-bearing: an **approval outranks recency** when picking the survivor, or the
migration reproduces the un-approval it exists to repair; `contentHash` is **deliberately nulled**
so the next sync re-embeds the survivor, since leaving it intact means a document sitting in the
index with no chunks and nothing reporting it; and the vector-store deletion is **caught, not
rethrown**, because Mongock runs at startup and a change-unit exception stops the application —
a leftover chunk is a far smaller problem than a backend that will not boot.

**The diagnostic for a recurrence** is an `Ingested attachment …` log line on a sync that also
reports `0 of N messages new or changed`. That line is only reached when the write reported the
document as *changed*, so the two together mean the id churned again.

**To recover after a loss**, once the volume is in place: trigger a mail sync
(`POST /api/admin/school/ingest/gmail`, or the button on `/admin/school/documents`). Only
messages still inside the Gmail query window (`SCHOOL_INGEST_FROM_DATE` onwards) can be
repaired; anything older is gone for good. Confirm from the documents list — the **Open PDF**
button renders only when the file is actually on disk, so its absence on a `PDF` row is the
diagnostic.

## The chat surface

Term Time streams over STOMP on `/ws/chat`, destination `/app/school.send`, replying on
`/topic/school.<sessionId>` — the same transport and the same `ChatResponse` frame type as the
portfolio assistant, which is why the browser reuses `chatStreamReducer`, `ChatMessage`,
`ChatInput` and `ToolActivityBlock` unchanged.

`POST /api/school/chat` still exists and is what the evals drive; the browser uses the socket.

Two traps this arrangement contains:

- **`ChatStreamPublisher` hardcodes `/topic/chat.`.** Tool frames published through it go to the
  portfolio topic, where nothing is subscribed — the answer arrives, the activity lines silently
  never appear. `SchoolStreamPublisher` exists for this reason.
- **A STOMP frame has nowhere for an `Authorization` header**, so the access token travels in the
  message body and is validated server-side by `SchoolAudienceResolver` using the application's
  real `JwtDecoder`. Anything that fails validation resolves to anonymous rather than throwing.

### "It keeps saying it cannot connect"

Term Time's socket was being closed by the infrastructure roughly once a minute and the page was
reporting each closure as a failure. Three separate things had to be true for that:

1. **Spring's simple broker sends no heartbeats unless it is given a `TaskScheduler`.** The
   default is `"0, 0"`, and the broker *advertises* that zero in its CONNECTED frame, which
   switches the client's heartbeat off too however the client is configured. So the socket
   carried no traffic at all between questions — and every proxy on the path treats a silent
   connection as a dead one: nginx's `proxy_read_timeout` defaults to **60s**, Cloudflare's
   WebSocket idle timeout is around 100s, and pinggy has its own. `WebSocketConfig` now sets the
   scheduler and states `{10000, 10000}` explicitly, and `WebSocketHeartbeatTest` pins it. That
   test exists because nothing else notices: a context with heartbeats off starts perfectly and
   passes every other test in the suite.
2. **The nginx block carrying every production chat socket is `api.simonrowe.dev`, not the
   term-time one.** The frontend image is built with `VITE_API_BASE_URL=https://api.simonrowe.dev`,
   so the browser opens `wss://api.simonrowe.dev/ws/chat` *even from `term-time.simonrowe.dev`*.
   The `location /ws/` block in the term-time server block — with its careful
   `proxy_read_timeout 300s` — is same-origin only and is not the one a released build uses.
   `api.simonrowe.dev` now has its own `/ws/` block with the same timeout, and it carries the
   maintenance-flag check that `location /` gave it before the split. Keep the two in step.
3. **The page announced every drop and never took it back.** `onWebSocketError` set an error
   string that nothing cleared, and `sendMessage` was `if (connected) publish(...)` with no
   `else` — so a question typed into a page whose socket had quietly timed out was discarded in
   silence, under a typing indicator that never stopped.

What the browser does now, in `schoolChatService.ts`:

- Exponential reconnect from **1s**, capped at **15s**. The ceiling is low because nobody presses
  anything to recover, so the ceiling *is* how long a reader waits after the service returns.
- `connecting` / `connected` / `reconnecting` / `offline` are four separate states and only
  `offline` renders anything — a quiet "Reconnecting to Term Time…" line, no error styling and
  **no button**. `reconnecting` is deliberately silent: it is typically over inside a second, and
  announcing it is what made a working page feel broken.
- A message sent while the socket is down is **held in one slot** and published on reconnect. If
  that has not happened within 20s the page is told, the empty reply bubble is removed and the
  indicator stops. One slot, not a queue: a second question supersedes the first, because two
  answers arriving at once is worse than one lost draft.
- **Staleness is judged on a connection epoch, not on the session id.** A reconnect reuses the
  same session deliberately, and `deactivate()` is asynchronous, so the outgoing client's close
  event lands after the replacement is live. Compared on session id those two are identical, and
  the stale close would be scored as a failure of a connection that is fine.
- Once `offline`, the page polls `probeSiteStatus()` (`services/siteStatus.ts`) every 10s. A
  **503** means a deploy is running, so it reloads and lets nginx's maintenance page take over;
  anything else leaves the page where it is, because the transcript is still readable and the
  socket is still retrying. It never polls while connected.

### Conversation memory, and why there was none

Term Time shipped with **no conversation memory whatsoever** — not a short window, none. Every
turn went to the model as a bare system + user prompt.

The cause is one line of wiring. `SchoolChatService` injects `ChatClient.Builder` and calls
`.build()` per turn, because it has to set its own model, `reasoningEffort("none")` and prompt
cache key, which the shared `chatClient` bean cannot carry. Those options came at the price of
**every default advisor on that bean**, memory among them. The portfolio chat has a 20-message
window via `MessageChatMemoryAdvisor` (`ChatConfig`); Term Time inherited none of it, and
`sessionId` was used only to name the STOMP reply topic.

What that looked like to a parent:

| Turn | | |
|---|---|---|
| 1 | *what day is PE?* | correctly asks which year group |
| 2 | *year 6* | the word "PE" is nowhere in the model's context — answers with generic Year 6 news |
| 3 | *PE day for year 6* | both facts in one message, so it finally searches properly |

Memory is now attached in `SchoolChatService.remembering()`. Three things are load-bearing:

- **No session id means no memory, deliberately.** `MessageChatMemoryAdvisor` falls back to a
  single default conversation id when none is supplied, so attaching it unconditionally would
  pool every anonymous caller of `POST /api/school/chat` (which carries no session id) into one
  shared history they could all read. The advisor is attached on the STOMP path only.
- **`ToolFilteringChatMemory` is required here, not tidiness.** Term Time is entirely
  tool-driven, and a message window truncates on count — so an unfiltered store will eventually
  cut an assistant message carrying `tool_calls` away from its `ToolResponseMessage`. OpenAI
  rejects a conversation containing one without the other, and the failure would be a `400` that
  appears only after enough turns to push the pair apart.
- **The store is bounded by `BoundedChatMemoryRepository`, not Spring AI's default.** The default
  is a `ConcurrentHashMap` nothing ever removes from. That is survivable behind the portfolio
  chat, which has `ChatSessionCleanupService` sweeping idle sessions; it is not survivable on an
  **unauthenticated** endpoint whose conversation id is whatever the browser puts in the frame.
  A sweeper alone would not close it either — a burst inside one sweep interval still allocates
  without limit — so the bound is an LRU cap plus a TTL, enforced by the store on write, with no
  scheduler. Tunable via `school.chat.memory.max-sessions` (500) and
  `school.chat.memory.ttl-minutes` (30).

`ChatConfig.chatMemory()` is now `@Primary`, because there are two `ChatMemory` beans and they
were otherwise resolving only by injection points happening to name their parameter
`chatMemory`. Anything wanting Term Time's must ask for `@Qualifier("schoolChatMemory")`.

The browser already mints a fresh session id on "clear chat", so clearing genuinely starts a new
conversation rather than reusing one with history behind it.

**Known and left alone:** `SchoolStreamController.sessionMessageCounts` is still an unbounded
`ConcurrentHashMap` on the same public endpoint. Each entry is a string and an `AtomicInteger`
rather than a conversation, so it is a much smaller leak than the one closed here, but it is the
same shape and the same endpoint.

## Evals

`evals/termtime.yaml`, driven by `evals/termTimeProvider.js` — the same custom-provider mechanism
`chatProvider.js` uses for the portfolio chat, since the transports differ (STOMP vs JSON POST) but
the way they are exercised should not.

```bash
cd evals && npx promptfoo eval -c termtime.yaml
```

Defaults to **production over HTTPS**. Pointing it at `https://localhost:8080` cannot work: the
backend serves plain HTTP and TLS terminates at nginx, which is the same reason `chatProvider.js`
uses `ws://` rather than `wss://`. For a local run, override the url — the command is in the
config's header comment.

The case that matters most asks for a Year 4 swimming lesson time that exists in no source. Any
specific time in the answer is a failure: a thin corpus means an assistant that fills gaps
confidently is worse than one that refuses.

## Fully built

All 53 tasks in `specs/047-term-time/tasks.md` are complete.
