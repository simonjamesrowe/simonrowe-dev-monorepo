# Data Model: On-Demand Blog Narration

## Narration

MongoDB collection: `narrations`

| Field | Type | Rules / Purpose |
|---|---|---|
| `id` | String | Generated stable narration identifier; also Kafka key and media directory name |
| `blogId` | String | Required, indexed; references an existing blog by plain ID |
| `fingerprint` | String | Required, unique; SHA-256 of formatter version, title, speech-safe prose, voice, language, encoding, and provider |
| `status` | Enum | `QUEUED`, `PROCESSING`, `READY`, `FAILED`, `UNCERTAIN`, `STALE` |
| `version` | Long | Monotonically incremented for public long-poll change detection |
| `scriptCharacterCount` | Integer | Required, positive and no larger than configured per-blog maximum |
| `providerRequestStarted` | Boolean | True immediately before Long Audio submission; supports uncertain-outcome recovery |
| `providerOperationName` | String? | Durable long-running operation used to resume polling without resubmission |
| `providerOutputObject` | String | Deterministic private Cloud Storage object name for reconciliation and download |
| `voiceName` | String | Voice used to calculate the fingerprint and operational audit |
| `languageCode` | String | Defaults to `en-GB` |
| `audioEncoding` | String | `MP3` in v1 |
| `audioPath` | String? | Public `/uploads/narrations/{id}/narration.mp3` only when ready |
| `fileSize` | Long? | Validated final bytes |
| `checksumSha256` | String? | Integrity value for restore and serving validation |
| `durationSeconds` | Long? | Estimated from fixed provider bitrate and final file size |
| `attemptCount` | Integer | Number of safe processing attempts |
| `reuseCount` | Long | Number of ready responses served after initial creation |
| `leaseUntil` | Instant? | Recovery deadline for processing ownership |
| `requestedAt` | Instant | Initial anonymous request time |
| `startedAt` | Instant? | Latest processing start |
| `completedAt` | Instant? | Ready time |
| `updatedAt` | Instant | Latest state transition |
| `failureCode` | String? | Sanitized stable reason, never provider credentials or response bodies |
| `retryable` | Boolean | Whether an explicit visitor retry may safely requeue the record |

Indexes:

- Unique `fingerprint`
- `blogId, updatedAt desc` for current-status lookup and invalidation
- `status, leaseUntil` for recovery

## NarrationRequestEvent

Kafka topic: `narration-requests`

| Field | Type | Rules / Purpose |
|---|---|---|
| `narrationId` | String | Required; Kafka message key is identical |
| `requestedAt` | Instant | Audit and stale-message diagnostics |

The event carries no blog prose or credential data. Consumers always re-read authoritative state and the published blog.

## Derived narration asset

```text
uploads/
└── narrations/
    └── {narrationId}/
        ├── narration.mp3
        └── .work/
            └── narration.mp3.part
```

- `.work` holds a partial local download and is removed after the final atomic move.
- The public path is assigned only after merge, MIME/type, MPEG-frame, non-zero-size, and checksum validation.
- All paths are constructed server-side from validated narration IDs.

## Public response

| Field | Type | Rules / Purpose |
|---|---|---|
| `state` | Enum | `NOT_REQUESTED`, `QUEUED`, `PROCESSING`, `READY`, `FAILED`, `UNAVAILABLE`, `INELIGIBLE` |
| `version` | Long | Used as `afterVersion` on the next long poll |
| `audioUrl` | String? | Required only for `READY` |
| `durationSeconds` | Long? | Present for `READY` |
| `retryable` | Boolean | True only for public-safe retry |
| `message` | String | Concise public copy; no internal/provider detail |

Internal `UNCERTAIN` maps to public `FAILED` with `retryable=false`; `STALE` maps to `NOT_REQUESTED` for the current blog fingerprint.

## State transitions

```text
absent --explicit request--> QUEUED --atomic claim--> PROCESSING
PROCESSING --provider operation + download + revalidation--> READY
PROCESSING --safe explicit failure--> FAILED(retryable)
PROCESSING --ambiguous provider outcome--> UNCERTAIN(non-retryable)
QUEUED/PROCESSING/READY/FAILED --blog/config changed--> STALE
FAILED(retryable) --explicit retry--> QUEUED
PROCESSING(lease expired, no ambiguous call) --recovery--> QUEUED
PROCESSING(lease expired, operation known) --recovery--> QUEUED(resume operation)
PROCESSING(lease expired, provider start ambiguous) --recovery/reconciliation--> UNCERTAIN
```

Before moving to READY, the consumer reloads the blog, requires `published=true`, rebuilds the fingerprint, and compares it with the record.

## Restore normalization

- `READY` + valid checksum-matching file: remains `READY`.
- `READY` + missing/corrupt file: becomes `FAILED`, `retryable=true`, and clears public media metadata.
- `QUEUED` or safely resumable `PROCESSING`: becomes `FAILED`, `retryable=true` so an explicit request requeues it.
- Ambiguous `PROCESSING` or `UNCERTAIN`: remains non-retryable.
- Missing `narrations` collection in an older archive: accepted as an empty set.
