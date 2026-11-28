# 057: CoParent documents

**Status:** Draft for review · **Created:** 2026-11-28
**Mockup:** `mockup/index.html` (open in a browser; the top bar switches data, parent, device and theme)
**Decisions:** `decisions.md` records every answer from the design grilling; this spec builds on it.

## Context

CoParent has had a `/documents` route since the migration (048), rendering a placeholder
(`frontend/src/coparent/pages/DocumentsPage.tsx`). Parents keep the children's important papers
(passports, birth certificates, red book, care plans, school reports, the child arrangements
order) in drawers at two houses, and each parent asks the other for them. This feature gives the
family one shared library of those papers, warns both parents before something expires, and
offers the letters Term Time already reads from the school mailbox.

It is a **reference library**: no evidence timeline (Timeline owns that), no "reply by" dates, no
calendar entries, nothing private to one parent.

## 1. Domain model

### Collection `documents` (coparent DB), record `coparent/model/FamilyDocument.java`

`Document` is taken by Spring Data's annotation, hence `FamilyDocument`.

| Field | Type | Notes |
|---|---|---|
| `id` | ObjectId | |
| `familyId` | ObjectId | every query is scoped to it |
| `childIds` | List&lt;ObjectId&gt; | **empty means the whole family**. A letter for both children is one document with both ids, so it shows under each child's filter, with one file |
| `section` | enum `IDENTITY`, `HEALTH`, `SCHOOL`, `AGREEMENTS`, `OTHER` | fixed, no custom sections |
| `title` | String | 1–120 chars |
| `datedOn` | LocalDate | issue or letter date |
| `dueOn` | LocalDate, nullable | an expiry or review date |
| `dueKind` | enum `EXPIRES`, `REVIEW`, nullable | set exactly when `dueOn` is |
| `note` | String, nullable | ≤ 1,000 chars |
| `versions` | List&lt;DocumentVersion&gt; | newest last; never empty |
| `source` | `{ kind: UPLOAD \| QUICK_ADD \| SCHOOL_LETTER \| SCHOOL_REPORT, schoolDocumentId }` | `schoolDocumentId` links back to Term Time's `SchoolDocument` |
| `archivedAt`, `archivedBy` | Instant, ObjectId, nullable | soft delete |
| `remindersSent` | Set&lt;String&gt; | keys like `2027-03-14:6m`, so a reminder is never sent twice for one date |
| `createdBy`, `createdAt`, `updatedAt` | | |
| `version` | long | optimistic guard, exactly as expenses: every write is a `findAndReplace` on the version the parent was looking at; stale is a 409 |

`DocumentVersion`: `versionId`, `files` (ordered `{fileId, mimeType, sizeBytes}`; photos of one
document are its pages), `datedOn`, `dueOn`, `dueKind`, `uploadedBy`, `uploadedAt`. The document's
own `datedOn`/`dueOn` always mirror the newest version, so reminders follow a renewal without anyone
editing the old one.

**Never extracted:** passport, NHS or any other identifying numbers. There is no field for them and
the reading schema (§2.3) has none either. They stay inside the file.

### Files: `CoparentDocumentStore`

A copy of `CoparentReceiptStore`'s pattern, for the same reason: `uploads/` is served without
authorisation.
- `<coparent.document-path>/<familyId>/<fileId>`, default `coparent-documents/`, in production
  `/workspace/coparent-documents/` on a new `coparent-documents` volume mounted on `backend` only.
  `backend` is in `FACTORY_DEPLOY_RECREATABLE`, so the deploy is not held back. No new service and no
  chown init: the backend container runs as `0:0` (see the `school-attachments` mount's comment), so
  a root-owned new volume is writable, as `coparent-receipts` and `school-attachments` already are.
- Types are **sniffed from the bytes**, never the name or `Content-Type`: JPEG, PNG, WebP, PDF. WebP
  is accepted (unlike receipts) because Quick add accepts it and its approved photo becomes the file.
  Stored as the original; no variants.
- Limits: 20 MB per file, 12 files per version.
- Files are never deleted by the app, including on archive. A real deletion is done by hand.

### Collection `document_school_offers` (coparent DB)

The ledger and queue for "From school" (§4), mirroring `school_event_suggestions`:
`familyId`, `schoolDocumentId`, `kind` (`LETTER` or `REPORT_READY`), `childIds`, `section`,
`title`, `sentAt`, `personal` (boolean), `reportLink` (REPORT_READY only), `status`
(`PENDING`, `KEPT`, `DISMISSED`), `documentId` (once kept), `decidedBy`, `decidedAt`.
**Unique index `(familyId, schoolDocumentId)`** is what makes "a dismissed letter is never offered
again" hold.

### Collection `document_notices` (coparent DB)

The email batch, one per recipient parent: `familyId`, `recipientParentId`, `items`
(`{documentId, change: ADDED | NEW_VERSION | ARCHIVED | RESTORED, byParentId, at}`), `firstAt`,
`sendAfter`. Every change upserts and pushes `sendAfter` to now + 15 minutes, capped at `firstAt` +
2 hours so a long sitting still sends.

### Migration and backup

- `V069CreateCoparentDocuments`: the indexes on `documents` (`familyId, archivedAt, section`;
  `familyId, dueOn`), the unique offer index, and `document_notices.recipientParentId` (unique per
  family). `auto-index-creation` is off, so they exist only here.
- All three collections go in `BackupService.COPARENT_BACKUP_COLLECTIONS` and
  `RestoreService.COPARENT_IMPORT_ORDER`, and `ensureCoparentIndexes()` calls
  `V069.createIndexes`. The file directory is zipped under `coparent-documents/` and restored with
  the zip-slip guard, exactly as receipts. `CoparentBackupCoverageTest` pins it.

## 2. Backend: package `com.simonrowe.coparent.document`

### 2.1 API, under `/api/coparent/families/{familyId}/documents`

Membership is checked with `CoparentAccessPolicy` on every call; only `active` parents of the family
pass.

| Method | Path | |
|---|---|---|
| GET | `` | `?archived=false` (default) or `true`. Details only, no bytes |
| POST | `` | multipart: `details` JSON + `files[]`. Creates the document with version 1 |
| GET | `/{id}` | details plus history (from audit records) |
| PUT | `/{id}` | details only (children, section, title, dates, note), version-guarded |
| POST | `/{id}/versions` | multipart: new files + dates. Becomes the current version |
| POST | `/{id}/archive`, `/{id}/restore` | version-guarded; either parent |
| GET | `/{id}/files/{fileId}` | `?download=true` for `attachment`, otherwise `inline` for the viewer. `nosniff`, `sandbox` CSP, `private, no-store` |
| POST | `/read` | multipart files, returns a proposal (§2.3). Stores nothing |

Every create, edit, version, archive and restore writes a `CoparentAuditService` record. That is the
drawer's history. Downloads are not recorded.

### 2.2 Email: `DocumentMailer`

Best-effort and behind `coparent.email-enabled`, like `ExpenseMailer`: a failed send is logged, never
thrown. Nothing goes to a parent who is not `active`.
- **Batch notice**: a minute-by-minute job sends each `document_notices` row once `sendAfter` has
  passed: "Alex added 6 documents and archived 1", listing them with links to `/documents?doc=<id>`.
  The acting parent is never the recipient.
- **Reminders**: a daily job (08:00 `Europe/London`) emails both active parents when an unarchived
  document's `dueOn` is 6 months or 3 months away (`EXPIRES`), or 1 month or 1 week away (`REVIEW`).
  Each key goes into `remindersSent` with a conditional update, so two instances or a re-run cannot
  double-send.

### 2.3 Reading an upload: `DocumentReader`

Fills in the form. The model only proposes; nothing is written until the parent saves.
- Input: the files the parent picked (images, or the first 5 pages of a PDF). Output, through
  structured output (`response_format` JSON schema, no tools): `childIds` (only from the family's
  children, checked after), `section`, `title` (≤ 120), `datedOn`, `dueOn`, `dueKind`, `confidence`.
  The prompt forbids numbers and names beyond the child's own; there is nowhere to put them anyway.
- The same model and client as Quick add's photo reading, bounded at 45 s, like the statement
  classifier.
- **"Fill in myself" skips this endpoint entirely.** It is the parent's way to keep a passport photo
  away from the model provider (OpenAI keeps API inputs up to 30 days for abuse monitoring and does
  not train on them; accepted in the grilling).

### 2.4 Quick add (049): new actions

- `CREATE_DOCUMENT`: children, section, title, dates. As with receipts, the analysed photo stays in
  the browser and is uploaded as the document's file only when the parent approves the card.
- `ADD_DOCUMENT_VERSION`: target document id plus new dates, for "Leo's new passport arrived".
- A read-only `findDocuments` lookup tool (title, children, section, dates; never bytes, never
  archived ones), so the model can target an existing document instead of creating a duplicate.
  An ambiguous target blocks the card, as Quick add already does for events.

CoParent has no question-answering assistant; Quick add is the only model surface, so "the assistant
answers from a document's details" (Q18) is delivered as this lookup. A Q&A assistant is out of scope.

## 3. UI (the mockup is the reference; this lists what it must keep)

- **Page** `/documents`: heading, **From school** button with a count, **Add document**. A
  **needs-attention strip**: letters waiting in From school, anything expiring within 6 months,
  anything due for review within 2 months. Search (title, child, section; client-side over the
  loaded list). Filter chips **Everyone / each child / Family / Archived**, with counts. Cards grouped
  by the five sections, newest `datedOn` first.
- **Card**: page-count thumbnail, title, child dot(s) and date, then tags: expiry (amber when within 6
  months), review, From school, `N versions`.
- **Document drawer** (right drawer; bottom sheet on a phone): preview, expiry callout, details,
  versions (previous ones downloadable), history, then **View full screen**, Download,
  **Upload renewed/newer version**, Edit details, **Archive** (or **Restore** when archived, with an
  "archived by X on date, hidden, not deleted" callout). Deep link `/documents?doc=<id>`.
- **Full-screen viewer**: images and PDFs in the app (pinch-zoom on a phone, page through photos).
  No offline copy.
- **Add drawer**: drop zone with **Take photos** and **Choose file**, a **Fill in myself** link, then a
  review form with model-filled fields highlighted, the "one email for everything you add in the next
  15 minutes" hint, and Save. A first visit shows an empty state listing what goes in each section.
- **From school drawer** (§4): one card per offer with why it was matched; letters get
  **Keep in &lt;child&gt;'s &lt;section&gt;**, a section picker and **Not needed**; report prompts get
  **Open Insight Tracking** and **Add it here** (opens the add drawer prefilled).
- **Dashboard briefing**: an expiry line when anything expires within 3 months, linking to the
  document.
- **Nav badge** on Documents: pending school offers.
- Hand-written BEM under `.coparent-app` (`document-*`), as the expenses CSS is, because the frozen
  utility blob's preflight outranks a bare class. Drawers keep the `coparent-app` wrapper inside the
  portal.

## 4. From school (Term Time integration): `SchoolDocumentOfferSync`

Listens to `SchoolEventsRefreshed`, as `SchoolSuggestionSync` does, and considers Term Time
`SchoolDocument`s from the last 60 days that have not been offered to that family. It only reads Term
Time; it never changes a school document's visibility or approval.

- **Broadcast letters**: `PUBLIC` email documents with a stored PDF or DOCX attachment. Matched to
  children with the school-events rules (year group from the date of birth, a possessive class in the
  title narrows to that class; `SchoolEventMatcher`), and only those the classifier in §7 (O1) calls keepable.
- **Personal letters**: `RESTRICTED` email documents, **only for the family in
  `COPARENT_SCHOOL_MAILBOX_FAMILY_ID`** (the mailbox is theirs). Matched by a linked child's first
  name together with the family surname, or full name, in the subject or attachment filename (the
  shapes seen in the mailbox, with synthetic names: `Mia and Leo Taylor.pdf`, `Instrumental lessons reports - Y4 Mia.pdf`). Rules only,
  bounded and possessive regexes, with the 100k-character test. Kept letters become visible to the
  co-parent, which is the point.
- **Report prompts**: the "A report for &lt;first name&gt; is ready" email from
  `system@insighttracking.com` (one per child, July and occasionally November). Offered as
  `REPORT_READY` with the link, accepted only if its parsed origin is `https://app.insighttracking.com`.
  Add it here prefills child, section School, and a title from the month (July "End-of-year report",
  September to December "Autumn report", January to April "Spring report") plus the academic year.
  CoParent never signs in to Insight Tracking.
  - **Prerequisite:** Term Time does not ingest this sender today. The allowlist is
    `kilmorie.lewisham.sch.uk`, and the shared env file pins the same value, so both the compose
    default and the env line must gain `insighttracking.com`. Those emails then arrive in Term Time as
    `RESTRICTED`; a `PublicationRules` decline for that sender keeps them out of the public tier for
    certain (they name a child), checked by a test.
- **Keep** copies the attachment bytes from `SchoolAttachmentStore` into `CoparentDocumentStore` (so
  Term Time's own retention never affects the family's copy), creates the document with
  `source.kind = SCHOOL_LETTER`, and marks the offer `KEPT`. A letter for both children is one
  document with both ids. **Not needed** marks it `DISMISSED`; it is never offered again.
- New offers add to the batch notice for the mailbox family's parents only when there is something to
  decide; no separate school email (the school-events mailer already emails about the calendar).

## 5. Delivery: five PRs, each behind `coparent.enabled`, each through `pr-review-loop`

1. **Backend core**: model, store, volume, `V069`, CRUD/versions/archive/files API, audit history,
   `DocumentMailer` (batch and reminders), backup and restore with coverage test.
2. **Library UI**: page, cards, filters, search, document drawer, full-screen viewer, add drawer with
   **Fill in myself** only, archive and restore, empty state, mobile, dashboard expiry line. Replaces
   the placeholder page and its test entry.
3. **Reading**: `DocumentReader` and `/read`, the prefilled review step.
4. **Quick add**: `CREATE_DOCUMENT`, `ADD_DOCUMENT_VERSION`, `findDocuments`, approve-time upload.
5. **From school**: offers ledger and sync, the allowlist change (compose default plus a note for the
   env line), the publication-judge decline rule, the drawer, the nav badge.

## 6. Verification

- Unit: due-date reminder windows (each threshold, no double-send, archived skipped, renewal moves
  them), notice batching (debounce and 2-hour cap), child matching for personal letters (positive and
  negative, plus the 100k-character regex test), report-link origin check (forged suffix host
  refused), type sniffing (a PDF named `.jpg` is a PDF; HTML refused).
- Integration (Testcontainers Mongo): version guard 409, archive and restore by either parent,
  non-member 403, file served with `nosniff` and `sandbox`, backup round-trip including files, the
  unique offer index refusing a second offer.
- Frontend (Vitest): filter and search, archived view, drawer actions per state, add flow both ways,
  From school keep and dismiss, report prompt prefill. Tests that render the page mock its API module.
- Manual on a local stack with synthetic children (never real documents or statements: the repo is
  public), then a production check that the volume is mounted and a test document survives a backend
  recreate.

## 7. Decided after the grilling

- **O1 — Which broadcast letters are offered.** The school sends 27–45 emails with attachments a term
  (trip letters, consent forms, wraparound booking guides, menus), so offering all of them would make
  From school a second inbox. A broadcast is offered only when a cheap structured-output classifier
  (no tools, bounded, failure means "not offered") says it is a keepable document about the child:
  consent, medical, trip, report or certificate. Guides, menus and booking forms are not offered.
  Personal letters and report prompts are always offered.
