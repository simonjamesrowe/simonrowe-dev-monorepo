# Data Model: CoParent Migration

CoParent records live in the dedicated `coparent` database on the existing MongoDB server. The
legacy Mongoose collection names are retained. Java `String` identifiers map to Mongo `ObjectId`
values without changing the stored `_id`. All entities retain Mongoose's `createdAt` and
`updatedAt` fields where present.

`V043CreateCoparentCollections` creates the collections and indexes. `RestoreService` calls its
public index-creation function after restore because restoring a dropped collection does not cause
Mongock to replay an already-recorded change unit.

If a rehearsal uses a temporary source database, `V044MigrateCoparentData` copies from the verified
source collection names (by default `coparent_legacy`) into `coparent`. It preserves identifiers and dates, is safe to rerun, and aborts rather
than overwriting a target record with different content. Its rollback is intentionally non-
destructive: a failed application deploy must not delete migrated family data.

## Collection mapping

Source names are Mongoose-default inferences and MUST be verified with `listCollections` before a
live migration.

| Source database/collection | Target collection |
| --- | --- |
| `coparent_legacy.families` | `coparent.families` |
| `coparent_legacy.parents` | `coparent.parents` |
| `coparent_legacy.children` | `coparent.children` |
| `coparent_legacy.invitations` | `coparent.invitations` |
| `coparent_legacy.onboardingstates` | `coparent.onboardingstates` |
| `coparent_legacy.events` | `coparent.events` |
| `coparent_legacy.eventcategories` | `coparent.eventcategories` |
| `coparent_legacy.schedulechangerequests` | `coparent.schedulechangerequests` |
| `coparent_legacy.conversations` | `coparent.conversations` |
| `coparent_legacy.audits` | `coparent.audits` |

## `families`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `name` | String | Required, trimmed, non-blank |
| `timeZone` | String | Required valid IANA zone |
| `parentIds` | ObjectId[] | Existing order preserved; every ID resolves within family |
| `childIds` | ObjectId[] | Existing order preserved; active rows returned by default |
| `invitationIds` | ObjectId[] | Existing order preserved |
| `deletedAt` | Instant/null | Soft deletion |
| `createdAt` / `updatedAt` | Instant | Preserved |

Indexes: `{deletedAt: 1}`.

## `parents`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `auth0Id` | String | Required verified JWT subject |
| `familyId` | ObjectId/null | Null only for initial unassigned profile |
| `fullName` | String | May be empty during first sign-in |
| `email` | String | Required, normalised for comparisons |
| `role` | enum | `primary` or `co-parent` |
| `status` | enum | `active` or `inactive` |
| `color` / `avatarUrl` | String/null | Optional presentation fields |
| `lastSignedInAt` | Instant/null | Updated on authenticated profile access |
| `createdAt` / `updatedAt` | Instant | Preserved |

Indexes: `{auth0Id: 1}`, `{familyId: 1, auth0Id: 1}` unique when `familyId` is present. One subject
may have profiles in multiple families, so `auth0Id` alone is not unique.

## `children`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required tenant owner |
| `fullName` | String | Required, non-blank |
| `dateOfBirth` | LocalDate-compatible BSON date | Required; contract renders `YYYY-MM-DD` |
| `school` / `medicalNotes` / `avatarUrl` | String/null | Optional private family data |
| `deletedAt` | Instant/null | Soft deletion |
| `createdAt` / `updatedAt` | Instant | Preserved |

Indexes: `{familyId: 1, deletedAt: 1}`.

Existing BSON dates are interpreted as UTC calendar dates rather than through the Raspberry Pi or
browser default time zone, so a date of birth cannot shift by a day during migration or rendering.

## `invitations`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required tenant owner |
| `email` | String | Required, lower-cased canonical address |
| `role` | enum | `primary` or `co-parent` |
| `status` | enum | `pending`, `accepted`, `expired`, `canceled` |
| `token` | String | Unique, single-use secret; never logged or returned by list responses |
| `sentAt` / `expiresAt` | Instant | Seven-day validity retained |
| `acceptedAt` / `canceledAt` | Instant/null | Lifecycle evidence |
| `acceptedByAuth0Id` | String/null | Internal recovery owner for a consumed token; not returned |
| `acceptedParentId` | ObjectId/null | Parent linked by acceptance; not returned |
| `createdAt` / `updatedAt` | Instant | Preserved |

Indexes: `{token: 1}` unique, `{familyId: 1, status: 1}`, `{email: 1, status: 1}`.

State transitions:

```text
pending -> accepted
pending -> canceled
pending -> expired
```

Every terminal state is final. Concurrent acceptance uses an atomic `pending`-and-not-expired
condition that records the accepting subject/parent. If a later family-link write fails, only that
same subject may retry the already-consumed token and complete the idempotent link.

## `onboardingstates`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required and unique |
| `currentStep` | enum | `account`, `family`, `child`, `invite`, `review`, `complete` |
| `completedSteps` | enum[] | No duplicates; only known steps |
| `isComplete` | boolean | True only when completion has been recorded |
| `lastUpdated` | Instant | Updated on every transition |
| `createdAt` / `updatedAt` | Instant | Preserved |

Index: `{familyId: 1}` unique.

## `events`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required tenant owner |
| `type` / `title` | String | Required; `type` stays extensible |
| `startDate` / `endDate` | Instant or null | Start required; end cannot precede start |
| `startTime` / `endTime` | String/null | Existing `HH:mm` contract retained |
| `allDay` | boolean | Default true |
| `parentId` | ObjectId/null | Legacy single owner retained |
| `parentIds` / `childIds` | ObjectId[] | Every referenced record must belong to family |
| `location` / `notes` | String/null | Optional |
| `recurring` | object/null | `frequency`: `daily` or `weekly`; optional days |
| `deletedAt` | Instant/null | Soft deletion |
| `createdAt` / `updatedAt` | Instant | Preserved |

Indexes: `{familyId: 1, deletedAt: 1}`, `{familyId: 1, startDate: 1, deletedAt: 1}`,
`{familyId: 1, parentId: 1, deletedAt: 1}`.

## `eventcategories`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required tenant owner |
| `name` / `icon` | String | Required |
| `color` | String/null | Optional presentation value |
| `isDefault` / `isSystem` | boolean | Default false |
| `deletedAt` | Instant/null | Soft deletion |
| `createdAt` / `updatedAt` | Instant | Preserved |

Index: `{familyId: 1, deletedAt: 1}`.

## `schedulechangerequests`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required tenant owner |
| `status` | enum | `pending`, `approved`, `declined` |
| `requestedBy` / `requestedAt` | ObjectId / Instant | Required attribution |
| `resolvedBy` / `resolvedAt` | ObjectId / Instant or null | Written together on decision |
| `originalEventId` | ObjectId/null | If supplied, event must belong to family |
| `proposedChange` | object | Type `swap`, `extend`, `add`, or `remove`; new date range required |
| `reason` | String | Required, non-blank |
| `responseNote` | String/null | Optional decision context |
| `deletedAt` | Instant/null | Requester-only withdrawal while pending |
| `createdAt` / `updatedAt` | Instant | Preserved |

Indexes: `{familyId: 1, deletedAt: 1}`, `{familyId: 1, status: 1, deletedAt: 1}`,
`{requestedBy: 1, status: 1}`.

State transitions:

```text
pending -> approved
pending -> declined
pending -> soft-deleted by requester
```

The requester cannot resolve their own request. Decisions use an atomic pending-state condition.

## `conversations`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId | Required tenant owner |
| `type` | enum | `message` or `permission` |
| `subject` | String | Required |
| `parent1Id` / `parent2Id` | ObjectId | Required members of the family |
| `messages` | embedded[] | Stable `_id`, sender, content, timestamp, and `readBy` parent IDs |
| `permissionRequest` | embedded/null | Stable `_id`, type, child, description, requester, decision state |
| `unreadCounts` | map<ObjectId, integer> | Non-negative count per parent |
| `lastMessageAt` | Instant | Required ordering key |
| `deletedAt` | Instant/null | Retained source field |
| `createdAt` / `updatedAt` | Instant | Preserved |

Permission types: `medical`, `travel`, `schedule`, `extracurricular`. Permission states:
`pending`, `approved`, `denied`.

Indexes: `{familyId: 1}`, `{familyId: 1, lastMessageAt: -1}`.

## `audits`

| Field | Type | Rules |
| --- | --- | --- |
| `_id` | ObjectId | Stable source identifier |
| `familyId` | ObjectId/null | Null only for pre-family profile activity |
| `entityType` / `entityId` | String | Required target description |
| `action` | String | Required stable action name |
| `performedBy` | String | Verified Auth0 subject, never a client value |
| `changes` | object | Default empty; secrets and medical/message bodies excluded unless required |
| `timestamp` | Instant | Required event time |
| `createdAt` / `updatedAt` | Instant | Preserved where source supplied them |

Indexes: `{performedBy: 1}`, `{familyId: 1, timestamp: -1}`,
`{entityType: 1, entityId: 1, timestamp: -1}`.

Audit records have no update/delete application interface.

## Consistency rules

- All repository reads include the family derived through `CoparentAccessPolicy`; controllers never
  retrieve a record globally and authorise it afterwards.
- References supplied in writes are validated in a bounded query against the same family.
- Multi-document workflows are idempotent and retryable on standalone Mongo. They do not assume
  transactions.
- Family creation writes the family and primary parent link before exposing completion; retries
  repair onboarding/audit state rather than create another family.
- Invitation acceptance atomically consumes the pending token before linking the parent and family;
  retries recognise an already-linked intended parent.
- Messages and unread-count changes occur in one atomic update of the owning conversation document.
- Soft-deleted records are excluded mechanically by repository methods rather than by individual
  callers remembering `deletedAt: null`.
