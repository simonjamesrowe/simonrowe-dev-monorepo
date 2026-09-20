# CoParent HTTP Contract

All routes are below `/api/coparent`. Except where noted, they require a bearer token accepted by
the existing simonrowe.dev resource server. Every family/entity lookup is scoped to a membership
derived from the token subject.

Production browser calls use the relative base URL `/api/coparent` on
`https://coparents.simonrowe.dev`.

## Common conventions

- JSON request and response bodies use camelCase.
- Entity identifiers are 24-character Mongo ObjectId strings. Malformed values return `400`.
- Dates without time use `YYYY-MM-DD`; instants use UTC ISO-8601 strings.
- Validation failures return `400` with a stable code, message, and field errors.
- Missing authentication returns `401`.
- A missing record and a record outside the caller's families both return `404`; callers cannot use
  response differences to enumerate another family.
- A known record for which the caller lacks an operation-specific role/ownership rule returns `403`.
- Private responses include `Cache-Control: no-store`.
- Persistence-only fields (`_class`, `__v`) are never returned.

Error shape:

```json
{
  "code": "validation_failed",
  "message": "The request could not be completed",
  "fieldErrors": {
    "name": "must not be blank"
  }
}
```

## Current user and families

| Method | Path | Behaviour |
| --- | --- | --- |
| `GET` | `/me` | Return subject, verified email, `profiles[]`, and `isNewUser`; create one unassigned initial profile when none exists. |
| `POST` | `/me` | Create the current user's initial profile. |
| `PATCH` | `/me` | Update current user's name/email fields allowed by the existing contract. |
| `POST` | `/families` | Create a family and link the caller as primary parent. |
| `GET` | `/families` | List active families in which the caller has a parent profile. |
| `GET` | `/families/{familyId}` | Get one authorised active family. |
| `PATCH` | `/families/{familyId}` | Update family name/time zone. |
| `DELETE` | `/families/{familyId}` | Soft-delete an authorised family. |
| `GET` | `/families/{familyId}/parents` | List family parents. |
| `PATCH` | `/parents/{parentId}/role` | Primary-parent-only role update with invariant checks. |

Family response:

```json
{
  "id": "66f000000000000000000001",
  "name": "The Example Family",
  "timeZone": "Europe/London",
  "parentIds": ["66f000000000000000000002"],
  "childIds": [],
  "invitationIds": [],
  "createdAt": "2026-09-19T10:00:00Z"
}
```

## Children and onboarding

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/families/{familyId}/children` | Add child; validate family membership. |
| `GET` | `/families/{familyId}/children` | List active children. |
| `GET` | `/children/{childId}` | Get an authorised active child. |
| `PATCH` | `/children/{childId}` | Update supported child fields. |
| `DELETE` | `/children/{childId}` | Soft-delete child and remove active family reference. |
| `GET` | `/onboarding/{familyId}` | Get or initialise family onboarding state. |
| `PATCH` | `/onboarding/{familyId}` | Update current/completed steps and completion flag. |
| `POST` | `/onboarding/{familyId}/complete-step` | Complete one valid step and advance consistently. |

Child responses render `dateOfBirth` as `YYYY-MM-DD` and never expose another family's medical
notes through identifiers or search.

## Invitations

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/families/{familyId}/invitations` | Create a pending seven-day invitation and attempt email delivery. |
| `GET` | `/families/{familyId}/invitations` | List invitations without token values. |
| `POST` | `/invitations/{invitationId}/resend` | Rotate token/expiry and resend if still eligible. |
| `POST` | `/invitations/{invitationId}/cancel` | Cancel pending invitation. |
| `POST` | `/invitations/accept` | Accept `{ "token": "..." }` for the authenticated user's verified email. |

Email links use `/invitations/accept#token=...`; the fragment is not sent in nginx access logs. The
SPA copies it to CoParent-scoped `sessionStorage`, immediately removes it from browser history, and
uses Auth0 `appState` to return to the acceptance route when login is required. After callback it
submits the token in the POST body and deletes the stored value whether acceptance succeeds or
fails. Tokens are redacted by request logging and are never returned by list/detail responses.

## Calendar and event categories

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/families/{familyId}/events` | Create event after validating parent/child references. |
| `GET` | `/families/{familyId}/events` | List active family events; retain current ordering/filter semantics. |
| `GET` | `/families/{familyId}/events/{eventId}` | Get one active event. |
| `PUT` | `/families/{familyId}/events/{eventId}` | Replace supported event values. |
| `DELETE` | `/families/{familyId}/events/{eventId}` | Soft-delete event. |
| `POST` | `/families/{familyId}/event-categories` | Create category. |
| `GET` | `/families/{familyId}/event-categories` | List active categories. |
| `GET` | `/families/{familyId}/event-categories/{categoryId}` | Get category. |
| `PUT` | `/families/{familyId}/event-categories/{categoryId}` | Replace supported category values. |
| `DELETE` | `/families/{familyId}/event-categories/{categoryId}` | Soft-delete category. |

The response DTO always uses `id`; the frontend no longer needs the source compatibility branch
that accepts either `_id` or `id`.

## Schedule-change requests

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/families/{familyId}/schedule-change-requests` | Create pending request. |
| `GET` | `/families/{familyId}/schedule-change-requests` | List active requests. |
| `GET` | `/families/{familyId}/schedule-change-requests/{requestId}` | Get one request. |
| `POST` | `/families/{familyId}/schedule-change-requests/{requestId}/approve` | Other parent atomically approves pending request. |
| `POST` | `/families/{familyId}/schedule-change-requests/{requestId}/decline` | Other parent atomically declines pending request. |
| `DELETE` | `/families/{familyId}/schedule-change-requests/{requestId}` | Original requester withdraws pending request. |

## Messaging and permissions

| Method | Path | Behaviour |
| --- | --- | --- |
| `GET` | `/families/{familyId}/conversations` | List formatted conversations and caller-specific unread count. |
| `POST` | `/families/{familyId}/conversations/message` | Start a message conversation with non-blank first message. |
| `POST` | `/families/{familyId}/conversations/permission` | Start permission conversation for a family child. |
| `POST` | `/conversations/{conversationId}/messages` | Append non-blank message from caller. |
| `POST` | `/conversations/{conversationId}/mark-read` | Set caller unread count to zero and add caller to message `readBy`. |
| `POST` | `/conversations/{conversationId}/mark-unread` | Restore the source contract's caller unread state. |
| `POST` | `/permissions/{permissionId}/approve` | Non-requesting parent approves pending request. |
| `POST` | `/permissions/{permissionId}/deny` | Non-requesting parent denies pending request. |

Conversation response:

```json
{
  "id": "66f000000000000000000020",
  "type": "message",
  "subject": "School pickup",
  "lastMessageAt": "2026-09-19T10:30:00Z",
  "unreadCount": 1,
  "participants": {
    "parent1": { "id": "...", "name": "Alex" },
    "parent2": { "id": "...", "name": "Sam" }
  },
  "messages": [
    {
      "id": "66f000000000000000000021",
      "senderId": "66f000000000000000000002",
      "content": "Can you collect at 4?",
      "timestamp": "2026-09-19T10:30:00Z",
      "isRead": false
    }
  ]
}
```

## Deliberate source-contract changes

1. Every route gains `/api/coparent`.
2. All entity responses consistently expose `id`, never raw Mongoose `_id` documents.
3. Invitation tokens move from an API query parameter to a redacted JSON body; email/browser links
   carry the token in a URL fragment so reverse proxies do not log it.
4. Inaccessible and absent records converge on `404` to prevent tenant enumeration.
5. No Swagger endpoint is migrated. The checked-in contract and automated tests are the supported
   interface; adding a public documentation runtime is not necessary for the browser-only caller.
