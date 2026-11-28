# 057: HTTP contract

The wire contract between the backend (`com.simonrowe.coparent.document`) and the frontend
(`frontend/src/coparent`). Ids are strings (ObjectId hex), dates are `YYYY-MM-DD`, instants are
ISO-8601 UTC. Every path is under `/api/coparent/families/{familyId}` and needs an `active` parent of
that family. A caller who is not one gets **404** ("Family not found"), not 403, the same as every
other CoParent endpoint (`CoparentAccessPolicy`): it does not reveal whether the family exists. An
id from another family is also a 404. A stale `version` is a 409.

Enums:
- `section`: `IDENTITY` | `HEALTH` | `SCHOOL` | `AGREEMENTS` | `OTHER`
  (labels: Identity and travel, Health, School, Agreements, Activities and other)
- `dueKind`: `EXPIRES` | `REVIEW` (present exactly when `dueOn` is)
- `source.kind`: `UPLOAD` | `QUICK_ADD` | `SCHOOL_LETTER` | `SCHOOL_REPORT`

## Documents

### `Document` (response body)

```json
{
  "id": "…", "familyId": "…",
  "childIds": ["…"],            // empty = the whole family
  "section": "IDENTITY",
  "title": "Passport",
  "datedOn": "2025-08-02",
  "dueOn": "2030-08-02", "dueKind": "EXPIRES",
  "note": null,
  "versions": [                 // oldest first, newest last, never empty
    { "versionId": "…",
      "files": [{ "fileId": "…", "mimeType": "image/jpeg", "sizeBytes": 123456 }],
      "datedOn": "2025-08-02", "dueOn": "2030-08-02", "dueKind": "EXPIRES",
      "uploadedBy": "<parentId>", "uploadedAt": "2026-11-02T10:00:00Z" }
  ],
  "source": { "kind": "UPLOAD", "schoolDocumentId": null },
  "archivedAt": null, "archivedBy": null,
  "createdBy": "<parentId>", "createdAt": "…", "updatedAt": "…",
  "version": 3
}
```

The document's `datedOn`/`dueOn`/`dueKind` always equal the newest version's.

### Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/documents?archived=false` | | `Document[]` (unarchived; `archived=true` gives only archived) |
| POST | `/documents` | multipart: `details` (JSON `DocumentDetails`), `files` (1–12) | 201 `Document` |
| GET | `/documents/{id}` | | `DocumentDetail` = `Document` + `history` |
| PUT | `/documents/{id}` | JSON `DocumentDetails` + `version` | `Document` |
| POST | `/documents/{id}/versions` | multipart: `details` (JSON `{datedOn, dueOn, dueKind, version}`), `files` (1–12) | `Document` |
| POST | `/documents/{id}/archive` | JSON `{version}` | `Document` |
| POST | `/documents/{id}/restore` | JSON `{version}` | `Document` |
| GET | `/documents/{id}/files/{fileId}` | `?download=true` → `Content-Disposition: attachment`, else `inline` | bytes |
| POST | `/documents/read` | multipart: `files` (1–12) | `DocumentProposal`, or 503 when reading is unavailable |

`DocumentDetails` (create and edit):

```json
{ "childIds": ["…"], "section": "HEALTH", "title": "Asthma care plan",
  "datedOn": "2026-01-10", "dueOn": "2027-01-10", "dueKind": "REVIEW",
  "note": "optional, ≤ 1000",
  "schoolOfferId": null }       // create only: an offer this document fulfils (report prompts)
```

Validation (400 with a message): title 1–120, `section` and `datedOn` required, note ≤ 1000,
`dueOn` and `dueKind` both or neither, every child id one of the family's children, files
JPEG/PNG/WebP/PDF by their bytes, ≤ 20 MB each, 1–12 per version, and **≤ 60 MB for all the files
in one request** (so 12 full-size files cannot go in one request; add the rest as a new version).
Spring's multipart limit is 61 MB and the CoParent nginx location's `client_max_body_size` is 62m,
just above, so the readable 400 is what the page normally sees; anything larger is a bare 413.

Multipart `details` may be sent either as a JSON blob part or as a plain string field; both are
read as JSON text.

Behaviour worth knowing:
- `PUT` with changed dates corrects the **current** version's dates too, so the document keeps
  mirroring its newest version. It never creates a version.
- An archived document refuses `PUT` and `POST …/versions` with 409 ("Restore … first").
  Archiving an archived document, or restoring one that is not archived, is a 409.
- The daily reminder job bumps `version` when it records a reminder, so an edit in flight at
  08:00 London time can get one 409 and must reload.
- `GET …/files/{fileId}` serves files from any version, archived documents included, with
  `Content-Disposition: inline|attachment; filename="<title>[ vN][ page N].<ext>"` (plus an
  RFC 5987 `filename*`), `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`
  and `Cache-Control: private, no-store`.
- `POST /documents/read`: 503 while Quick add (`coparent.assistant.enabled`) is off, 504 after
  45 s, 502 when the model's answer is unusable, 400 for a PDF that cannot be rendered. Images go
  to the model as they are; a PDF as its first 5 pages rendered (at most 12 images in all).

`history` items: `{ "action": "CREATED" | "EDITED" | "NEW_VERSION" | "ARCHIVED" | "RESTORED",
"parentId": "…", "at": "…" }`, oldest first.

`DocumentProposal`: `{ childIds, section, title, datedOn, dueOn, dueKind, confidence }`, every field
nullable. It is a suggestion for the form, never saved.

## From school offers

Offers are made by `SchoolDocumentOfferSync` after every school sync. On create,
`schoolOfferId` must name an offer in this family (404 otherwise) that is `PENDING` and
`REPORT_READY` (409 otherwise); it is claimed atomically, so a second upload for the same prompt
is a 409.

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/documents/school-offers` | | `SchoolOffer[]`, pending only, newest first |
| POST | `/documents/school-offers/{offerId}/keep` | JSON `{childIds, section, title}` | 201 `Document` (letters only; 409 for a report prompt) |
| POST | `/documents/school-offers/{offerId}/dismiss` | | 204 |

- **Keep** (letters only) copies the letter's PDF from Term Time's attachment store into the
  family's document store and creates one document, whichever children are chosen, with
  `source.kind = SCHOOL_LETTER` and `source.schoolDocumentId`. `datedOn` is the date the school
  sent it. The offer is claimed atomically: a second keep, by either parent, is a 409, as is
  keeping a `REPORT_READY` prompt or an offer already kept or dismissed. 404 when the offer is not
  this family's, or when Term Time no longer has the letter (its file is gone, or a broadcast has
  left the public tier). The body is validated as `DocumentDetails` is (title, section, children).
- **Dismiss** marks a `PENDING` offer `DISMISSED` (204). It is never offered again; dismissing
  one that is not pending is a 409, one that is not this family's a 404.
- Offers the family never saw (a broadcast the classifier judged not worth keeping, or a report
  email that did not come from the reporting system) are stored `DISMISSED` with no `decidedBy`,
  so they are not judged again; they never appear in the list.

`SchoolOffer`:

```json
{ "id": "…", "kind": "LETTER" | "REPORT_READY",
  "childIds": ["…"], "section": "SCHOOL", "title": "Year 6 residential: consent form",
  "sender": "Kilmorie Primary School", "sentAt": "2026-11-26T09:00:00Z",
  "personal": false,
  "reason": "Sent to Year 6 parents. Mia is in Year 6 (Elis's class).",
  "attachmentName": "Kingswood-consent.pdf",   // LETTER; null for REPORT_READY
  "reportLink": null,                          // REPORT_READY: https://app.insighttracking.com/…
  "suggestedTitle": "Autumn report 2026/27",   // REPORT_READY: prefill for the add drawer
  "suggestedDatedOn": "2026-11-24" }
```

A report prompt is fulfilled by creating a document with `details.schoolOfferId` set; that marks the
offer kept and sets `source.kind` to `SCHOOL_REPORT`.

## Quick add (existing `/assistant/batches/...`)

New `actionType`s and payloads:
- `CREATE_DOCUMENT`: `{ childIds, section, title, datedOn, dueOn, dueKind, note }`
- `ADD_DOCUMENT_VERSION`: `{ documentId, targetHint, datedOn, dueOn, dueKind }` (`targetHint` is
  what the note said when no single document matched, as on every other targeted action; the card
  hides it)

A card is `BLOCKED` with field errors when the title, section or `datedOn` is missing or invalid,
when `dueOn` and `dueKind` are not both set or both empty, when a child is not the family's, when
the title or note holds six digits in a row (an identifying number, which stays in the file), or,
for a new version, when `documentId` does not name an unarchived document of the family.

Both need files to apply, so their approval is **multipart** on the existing path:
`POST /assistant/batches/{batchId}/actions/{actionId}/approve` with form field `version` and `files`
(1–12; the analysed photo by default), checked as document uploads are. The JSON approve of these
two types is a 400 ("This needs the document's file"), and a multipart approve of any other type
is a 400 too. Every other action type keeps the JSON approve unchanged. The applied action's
result is `{ entityType: "document", entityId: <document id>, route: "/documents?doc=<id>" }`, as
`CREATE_EXPENSE`'s carries the expense id. The created document's id (and a new version's id) is
the action's operation id, so a retried approval never adds a second one.

The model also gets a read-only `find_documents` function (`{ query, childId, section }`), whose
calls are answered with the family's unarchived documents' id, title, children, section, dates and
version count (never notes, never files), so a renewal targets the existing document.
