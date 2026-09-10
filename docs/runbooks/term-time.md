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
