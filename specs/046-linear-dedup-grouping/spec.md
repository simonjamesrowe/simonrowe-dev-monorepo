# Feature Specification: Ticket dedup — group by source, update in place

**Feature Branch**: `046-linear-dedup-grouping`

**Created**: 2026-09-06

**Status**: Draft

**Input**: User description: "I was looking at all the Linear tickets, they're in triage. I think
lots of them are duplicates so maybe we could do something where, instead of creating duplicate
tickets, we just update the current ticket if that's appropriate."

## Summary

Sixteen tickets sat in Linear Triage; roughly eleven distinct problems. This specification fixes
the cause and changes what a recurrence does to a ticket that already exists.

Two changes, in two modules:

1. **`logwatch` groups log lines by the code that emitted them**, not by the whole normalised
   line. The distinct message templates within a group are carried inside the ticket body so
   nothing is lost to the coarser key.
2. **The `linear` sink can update an existing ticket in place**, rewriting its description to
   current state, and can reopen a completed rolling ticket rather than filing a linked
   replacement.

## Diagnosis

**The sink's deduplication was not at fault and is not being repaired.** `FilingDecider` already
resolves every issue carrying a fingerprint under the precedence open > (canceled or duplicate) >
completed, and already comments on the open one rather than filing again. It was working
correctly throughout.

The duplicates came from the fingerprint *key*. `LogWatchWorkflowImpl` files with key parts
`(container, normalised-line)`, and `SignatureExtractor.normalise` masks timestamps, UUIDs,
numbers, paths, addresses, quantities and URLs — but not free text inside the message. Any varying
prose therefore forks a new fingerprint and so a new ticket.

Observed in production on 2026-09-06:

| Cluster | Tickets | Why they forked |
|---|---|---|
| Embabel agent validation failing at backend startup | SIM-13, SIM-24, SIM-25 | `Validation failed with N errors:` vs `Agent 'ContentAggregation' must have…` vs `Agent 'WeeklyDigest' must have…` |
| Alloy cannot ship logs to Grafana Cloud | SIM-16, SIM-23 | identical message, different `error=` payload — a DNS lookup failure and an HTTP 400 `timestamp too old` |

SIM-11 (`org.springframework.boot.SpringApplication`) is part of the same startup incident as the
Embabel cluster but is emitted by different code. It is **not** merged by this work, and that is
recorded rather than hidden: grouping by incident is not something a deterministic rule can do,
and the alternative — merging on proximity in time — would hide unrelated faults inside each
other during any noisy window.

A second, unrelated duplicate class: SIM-9 was completed, the next CVE scan found findings again,
and the sink filed SIM-10 as a fresh `FILED_REGRESSION` ticket. That is correct under today's
rules, but wrong for a report whose title is literally `Current vulnerabilities in <repo>` — a
rolling report should be reopened, not replaced.

## Scope

**In scope**: the `logwatch` grouping key and ticket body; the `linear` sink's behaviour on an
existing issue; the `cvefix` and `logwatch` producers' opt-in to that behaviour; a one-off cleanup
of the current Linear triage queue.

**Out of scope**, deliberately:

- Fixing any of the problems the tickets describe. This work changes how they are reported.
- `FilingDecider`'s precedence rules. They are correct and stay pure and untouched.
- The `deploy` and `review-feedback` producers' filing behaviour, which stays exactly as it is.
- Any change to the `source-health` filing, whose key parts (`source-health`, status) are already
  structural and already deduplicate correctly.

## Requirements

### FR-001 — Source key extraction

A new pure class `SourceKeyExtractor`, sitting alongside `SignatureExtractor` in
`com.simonrowe.factory.logwatch.signature`, MUST reduce a raw log line to the identity of the code
that emitted it, returning an empty result when it cannot.

Handlers are tried in order and cover the six formats observed in production:

| Format | Emitted by | Source key |
|---|---|---|
| ECS JSON | `backend` | the `log.logger` value |
| logfmt | `alloy` | the `component_id` value |
| Spring plain text | `software-factory` | the logger token preceding ` : ` |
| Temporal JSON | `temporal` | the `logging-call-at` value |
| Java exception | `deployer` | the fully-qualified class name preceding the first `:` |
| unrecognised | `elasticsearch`, others | empty |

It MUST be pure over strings — no clock, no client, no Spring — so it is exhaustively testable
from fixtures, on the same terms as `SignatureExtractor`.

### FR-002 — Two-level grouping

`SignatureExtractor.group` MUST group by `container + severity + discriminated source`, where the
discriminated source is `logger:<sourceKey>` when FR-001 yields one and `line:<normalisedLine>`
when it does not.

The prefix is load-bearing: without it a source key whose text happened to equal a normalised line
would silently merge two unrelated groups.

Severity remains part of the key. `WARN slow query` and `ERROR slow query` stay two problems, as
`docs/runbooks/logwatch.md` already records.

### FR-003 — Variants preserved

`LogSignature` MUST carry `sourceKey` and `variants` — the distinct normalised signatures within
the group, each with its own occurrence count, capped and ordered by count descending.

This is what makes the coarser key safe. The stated objection to grouping by emitting code is
that one logger may emit two genuinely different faults; listing the variants in the ticket body
answers it directly, and is the same content that makes an updated ticket worth reading.

### FR-004 — Fingerprint key parts

`LogWatchWorkflowImpl` MUST file with key parts `(container, severity, discriminatedSource)`.

`Fingerprint.VERSION` MUST NOT be bumped. Changing these key parts orphans existing logwatch
fingerprints on its own; bumping the version would additionally orphan `deploy`, `cvefix` and
`review-feedback`, which have no duplicate problem and must not be disturbed.

### FR-005 — Filing modes

`IssueFiling`'s `commentOnly` boolean MUST be replaced by a `FilingMode` enum. One axis, not
three booleans that can contradict each other.

| Mode | On an open issue carrying the fingerprint | On a completed one | Used by |
|---|---|---|---|
| `OCCURRENCE` | add a `Seen again:` comment | file a new linked ticket | `deploy`, `review-feedback` |
| `REFRESH` | rewrite the description to current state; post no comment | file a new linked ticket | `logwatch` |
| `ROLLING` | rewrite the description to current state; post no comment | reopen it into Triage and rewrite its description | `cvefix` (findings report) |
| `STATUS_UPDATE` | add the occurrence detail verbatim as a comment | do nothing | `cvefix` (clean transition) |

`STATUS_UPDATE` MUST preserve today's `commentOnly` semantics exactly: it never creates an issue,
and it uses the occurrence detail as the comment without the `Seen again: ` prefix.

### FR-006 — New decisions

`FilingDecision` MUST gain `UPDATED_EXISTING` (description rewritten, no comment posted) and
`REOPENED_EXISTING` (a completed rolling issue moved back to Triage and rewritten).

They are separate values rather than a reuse of `COMMENTED_EXISTING` because the audit trail in
`linear_issues` and the Software Factory console must be able to tell "we commented" from "we
rewrote the ticket" from "we reopened a closed ticket" — three materially different acts.

### FR-007 — Decider unchanged

`FilingDecider` MUST remain pure, I/O-free and behaviourally unchanged, still returning
`COMMENTED_EXISTING` and `FILED_REGRESSION`. The mapping from a decision to the mode-specific act
MUST happen in `IssueFiler`, in the position `commentOnlySafe` occupies today.

The decider is the exhaustively-tested statement of the precedence rules. Modes are a property of
the producer, not of the tracker's state, and mixing them into the decider would make those rules
untestable without a filing context.

### FR-008 — Gateway support

`LinearGateway` MUST gain `updateIssue(issueId, description, stateId)` over Linear's `issueUpdate`
mutation, with a null `stateId` leaving the state alone.

The reopen target MUST be `teamContext().triageStateId()` — the state a newly filed ticket lands
in — so a reopened rolling ticket re-enters triage rather than appearing in some state nobody
watches.

### FR-009 — Downstream counters

`CveFixWorkflowImpl`'s outcome tally MUST be updated in the same change to count
`UPDATED_EXISTING` and `REOPENED_EXISTING`.

It counts `COMMENTED_EXISTING` today to report "updated". Once `cvefix` files as `ROLLING` that
counter reports zero for every run in which the sink did exactly what it was asked to — a green
build, a correct-looking run and a wrong number, with nothing anywhere reporting an error.

### FR-010 — Missing labels

The `factory:logwatch` and `factory:feedback` labels MUST exist in the Linear team.

Neither does today. `LinearGateway.teamContext()` warns and files the issue unlabelled when a
label is absent, which is why all fourteen logwatch tickets carry no label — and why SIM-21 is
`logwatch` filing a ticket about its own missing label. This is an operator action against Linear,
not a code change; the code already behaves correctly.

## Changeover

Changing the key parts orphans every existing logwatch fingerprint. The first scan after deploy
therefore re-files each problem once, and any cancellation of an old ticket stops suppressing
anything.

**This is accepted rather than migrated.** The alternatives — rewriting each open ticket's
attachment URL, or dual-reading old and new fingerprints for a grace period — are throwaway code
that must be exactly right, guarding a one-time cost of one noisy morning.

The fourteen logwatch tickets are cancelled ahead of the deploy. Under the current code a
cancelled ticket suppresses its fingerprint, so the nightly scan stays quiet in the interval
between the cleanup and the deploy; after the deploy the new fingerprints file fresh.

## Cleanup performed

Against the `SIM` team on 2026-09-06, before any code change:

- Cancelled SIM-11 through SIM-21 and SIM-23 through SIM-25 — the fourteen logwatch tickets.
- Left SIM-10 (`cvefix`) and SIM-22 (`review-feedback`) untouched. Their fingerprints are **not**
  orphaned by this work, so cancelling them would be a real, lasting suppression: `cvefix` would
  stop reporting vulnerabilities entirely until SIM-10 was reopened. Cleanup and silencing are
  different acts and only the first was wanted.
- Created the `factory:logwatch` and `factory:feedback` labels (FR-010), and applied
  `factory:feedback` to SIM-22, which had been filed unlabelled for the same reason.
- Left `linear_issues` in Mongo alone. It is the audit trail, and the new fingerprints create new
  records beside the old ones rather than colliding with them.

## Testing

`SourceKeyExtractorTest` is fixture-driven from the fourteen cancelled tickets. Those are real
production lines **read from Loki**, covering all six formats — which closes a gap
`CLAUDE.md` records explicitly: the existing logwatch fixtures were captured with `docker logs`
because Loki held nothing when 042 was written, so "the signature rules and the occurrence
thresholds are therefore still estimates".

Required coverage:

- Each of the six formats yields its expected source key; an unrecognised line yields empty.
- The three Embabel lines collapse to one group; the two Alloy lines collapse to one group. These
  are regression tests named for the tickets they came from.
- SIM-11 and SIM-13 stay separate, pinning FR-002's deliberate limit so a future "improvement"
  that merges them fails the build instead of shipping.
- A line yielding no source key falls back to the masked signature and groups exactly as it does
  today.
- `IssueFilerTest` covers every `FilingMode` against every `FilingDecider` outcome, including
  that `REFRESH` and `ROLLING` post no comment and that `STATUS_UPDATE` still never creates.
- A dry run reports the same decision a real run would take, for every mode — the property
  `commentOnlySafe` exists to preserve.

## Open, and deliberately not addressed

- SIM-11 is not merged with the Embabel cluster (see Diagnosis).
- SIM-19 (`MailHealthIndicator`) and SIM-20 (`HealthEndpointSupport`) describe one incident from
  two loggers and stay two tickets. Two loggers are two places to look; a rule that merged them
  would merge much else besides.
- A problem that recurs after being moved out of Triage now produces no Linear notification at
  all, because `REFRESH` posts no comment. The occurrence history remains in
  `LinearIssueRecord.decisions`, which is the audit trail, but it is not surfaced.
