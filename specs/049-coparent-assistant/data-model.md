# Data Model: CoParent Assistant Action Proposals

## AssistantProposalBatch (`assistantproposalbatches`)

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Server generated |
| `familyId` | ObjectId | Required; owning family |
| `submittedBySubject` | String | Required; Auth0 subject used for private visibility |
| `submittedByParentId` | ObjectId | Required; submitting parent snapshot |
| `status` | enum | `READY`, `NO_ACTION`, `FAILED` |
| `model` | String | Required; operational metadata only |
| `inputKinds` | set | `TEXT`, `IMAGE`; contains no source data |
| `actions` | array | Ordered embedded `AssistantProposalAction` values, capped at 25 |
| `createdAt` | Instant | Required |
| `updatedAt` | Instant | Required |
| `expiresAt` | Instant | Required; createdAt + seven days, TTL indexed |

Indexes:

- `{familyId: 1, submittedBySubject: 1, createdAt: -1}` named `idx_coparent_assistant_owner_recent`.
- `{expiresAt: 1}` with `expireAfterSeconds: 0` named `idx_coparent_assistant_expiry`.

## AssistantProposalAction (embedded)

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Preallocated on normalization |
| `actionType` | enum | One of the eleven supported action types |
| `status` | enum | `BLOCKED`, `PENDING`, `APPLYING`, `APPLIED`, `REJECTED`, `FAILED` |
| `payload` | typed object | Normalized editable payload; no raw provider arguments retained |
| `fieldErrors` | array | `{field, message}` safe validation details |
| `revision` | long | Starts at 0; compare-and-set for edits/decisions |
| `targetSnapshot` | object? | `{entityType, entityId, observedUpdatedAt, hint}` |
| `result` | object? | `{entityType, entityId, route}` |
| `operationId` | ObjectId | Preallocated idempotency/audit marker |
| `failureMessage` | String? | Safe retry-facing failure text, never source content |
| `claimedAt` | Instant? | Used to identify stale `APPLYING` actions |
| `decidedAt` | Instant? | Terminal decision time |

## Payload union

- `CREATE_EVENT`: complete event create fields.
- `UPDATE_EVENT`: `eventId` or unresolved `targetHint`, plus editable event patch fields.
- `DELETE_EVENT`: `eventId` or unresolved `targetHint`.
- `CREATE_CATEGORY`: name, colour, icon.
- `UPDATE_CATEGORY`: `categoryId` or hint, plus editable category patch.
- `DELETE_CATEGORY`: `categoryId` or hint.
- `CREATE_SCHEDULE_CHANGE`: existing schedule-change create fields.
- `WITHDRAW_SCHEDULE_CHANGE`: `requestId` or hint.
- `START_MESSAGE_CONVERSATION`: subject, recipient parent IDs, initial message.
- `SEND_MESSAGE`: `conversationId` or hint, message.
- `CREATE_PERMISSION_REQUEST`: child, activity, timing/location/details, recipient parent IDs.

Payloads use strings at the persistence boundary and are converted into existing Calendar and Messaging request records only after authorization and validation.

## State transitions

```text
BLOCKED --edit(valid)--> PENDING
BLOCKED --reject-------> REJECTED
PENDING --edit---------> PENDING (revision + 1)
PENDING --reject-------> REJECTED
PENDING --claim--------> APPLYING
APPLYING --success-----> APPLIED
APPLYING --safe error--> FAILED
APPLYING --stale target> BLOCKED
FAILED --claim/retry---> APPLYING
```

`APPLIED` and `REJECTED` are terminal. A stale revision never transitions state.

## Domain idempotency additions

Domain records affected by assistant approval carry an internal `assistantActionId` marker (not exposed in DTOs). Embedded messages use the preallocated action result ID as their message ID. Audit metadata records a redacted receipt containing action/batch identifiers and action type but not proposal or message content.
