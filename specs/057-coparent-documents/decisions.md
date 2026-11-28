# CoParent documents: decisions from the design grilling (2026-11-28)

Mockup: `mockup/index.html` (open in a browser; the top bar switches data, parent, device and theme).

## What it is
- A **reference library** of the children's important papers. Not an evidence store (Timeline owns
  that) and not a to-do list: no "reply by" dates, nothing goes on the calendar.
- Documents belong to **one child or the whole family** (child arrangements order, parenting plan).
- Five fixed sections: Identity and travel, Health, School, Agreements, Activities and other.

## Sharing and lifecycle
- **Everything is visible to both parents.** No private documents; private papers stay outside the app.
- Either parent can add, edit details, upload a newer version, archive and restore.
- **Delete is a soft delete: Archive.** Archived documents are hidden from the library, listed under
  an "Archived" filter, and either parent can restore them. A real deletion is done by hand in the backend.
- A renewal (new passport) is a **new version of the same document**; earlier versions stay downloadable
  in its history, and reminders follow the current version's dates.
- Documents can be added before the co-parent joins; they see everything on joining. No email goes to
  a parent who is not `active`.
- The other parent gets **one email per batch** of additions or archives (sent ~15 minutes after the last).
- No who-holds-the-paper-original tracking (judged clutter for now).

## Dates and sensitive data
- Optional expiry or review date per document; both parents are emailed **6 and 3 months** before.
- Expiry warnings also appear in the dashboard briefing.
- Passport, NHS and similar numbers are **not** extracted into fields; they stay in the file only.

## Adding a document
- Photos (several photos of one document stay one document) or a PDF. JPEG, PNG, PDF; type sniffed from
  the bytes as receipts are; up to 20 MB. No PDF stitching in v1. Stored privately like receipts.
- The model reads the upload and **proposes** child, section, title and dates; the parent confirms.
  Images go to OpenAI for this (no training, up to 30 days abuse retention): accepted. A
  **"Fill in myself"** option always skips the model.

## Term Time ("From school")
- Offer both **broadcast letters** (trip, consent forms) and **personal letters** naming a child
  (music reports, absence letters), approve-first, in a drawer like the school-events one.
- Personal letters come only to the family owning the mailbox (`COPARENT_SCHOOL_MAILBOX_FAMILY_ID`),
  straight from the restricted tier, never via the publication judge.
- Matching uses the school-events year-group and class rules; a dismissed letter is never offered
  again (unique ledger, as `school_event_suggestions`); a letter for both children is filed for both.
- **Reports are not emailed**: the school publishes them on Insight Tracking (`system@insighttracking.com`,
  "A report for X is ready", no attachment). That email becomes a prompt with "Open Insight Tracking"
  and "Add it here", which opens the upload pre-filled. CoParent never stores an Insight Tracking login.
  (Mailbox survey, 762 school-system messages, headers and attachment names only.)

## Assistant and viewing
- The CoParent assistant may answer from a document's details ("when does Mia's passport expire?")
  but never reads file contents.
- Documents open in a full-screen in-app viewer (images and PDFs); no offline copy in v1.

## Quick add
- A document photo dropped into Quick add becomes a draft document card, reviewed and approved like
  every other Quick add card (Q21).
- Broadcast letters are offered only when a classifier calls them keepable (consent, medical, trip,
  report, certificate); guides, menus and booking forms are not (O1).
