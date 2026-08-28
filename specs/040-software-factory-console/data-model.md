# Data Model: Software Factory Console

## FactoryModuleStatus

Read model assembled on demand; not persisted.

| Field | Type | Rules |
| --- | --- | --- |
| key | enum | `codereview`, `feedback`, `cvefix`, `deploy`, `linear`, `platformbackup` |
| displayName | string | Stable user-facing name |
| container | enum | `software-factory` or `deployer` for the local configuration source |
| configured | boolean | Effective local feature flag; code review is always true |
| configurationDetail | string? | Safe reason when prerequisites are incomplete |
| taskQueue | string | Temporal queue name |
| workflowPollers | integer? | Null when Temporal cannot be queried |
| activityPollers | integer? | Null when Temporal cannot be queried |
| trigger | enum | webhook, schedule, upstream, manual-and-webhook, manual-and-schedule |
| schedule | FactoryScheduleStatus? | Only CVE scan and platform backup |
| lastRun | FactoryRunSummary? | Most recent known execution when available |
| ready | boolean | Derived from configuration, the required poller type, and schedule state only where automatic readiness is being described |
| diagnostic | string? | Bounded, secret-free explanation |

## FactoryScheduleStatus

Read from Temporal; not persisted separately.

| Field | Type | Rules |
| --- | --- | --- |
| scheduleId | string | Stable id |
| exists | boolean | False when never created or deleted |
| paused | boolean? | Null when absent or unreachable |
| overlapPolicy | enum/string? | Expected `SKIP` for both current schedules |
| previousActionAt | instant? | Most recent action time |
| nextActionAt | instant? | First future action time |
| runningActions | integer | Current scheduled actions |

State: `ABSENT -> ACTIVE` on first enabled initialization; `ACTIVE <-> PAUSED` only through an
operator action outside this feature. Reconciliation preserves the existing state.

## FactoryRunAccepted / FactoryRunProgress

Wire models, not a new persistence collection.

| Field | Type | Rules |
| --- | --- | --- |
| module | enum | One of the manually actionable modules |
| workflowId | string | Stable Temporal workflow id |
| runId | string? | Execution id returned at start |
| acceptedAt | instant | Server time |
| phase | string | Module-specific, rendered as text |
| detail | string? | Secret-free, max 240 characters for UI |
| count | integer? | Findings, lessons, phases, or archives as applicable |
| terminal | boolean | Whether polling may stop |
| outcome | string? | Module-specific terminal result |
| externalUrl | string? | Linear issue, PR, or Temporal UI link when applicable |

## VulnerabilityScanRequest

Workflow input evolves from the existing CVE-fix request. New histories use only:

| Field | Type | Rules |
| --- | --- | --- |
| linearFilingEnabled | boolean | Snapshotted from trigger-side configuration |
| trigger | enum | `schedule` or `manual` |

Legacy input fields remain deserializable but no longer influence behavior, protecting stored
workflow histories during rollout.

## VulnerabilityScanRecord

Stored in the existing `cve_fix_runs` collection.

| Field | Type | Rules |
| --- | --- | --- |
| id | string | Workflow id; one record per scan execution identity |
| workflowId | string | Temporal id |
| runId | string? | Temporal run id for new records |
| startedAt / completedAt | instant | Workflow time |
| trigger | enum | schedule/manual |
| status | enum | `COMPLETED`, `NO_FINDINGS`, `FAILED` |
| findingsSeen | integer | Raw advisory count |
| componentsSeen | integer | Grouped component count |
| filed / updated / suppressed / regressed | integer | Linear decision counts |
| issueUrls | list<string> | Issues acted on, never credentials |
| detail | string | Bounded summary |

Old bump/PR/CI fields may remain on historic documents but are no longer written.

## Feedback Ticket Relationship

The existing `review_learnings` record gains a Linear subdocument:

| Field | Type | Rules |
| --- | --- | --- |
| fingerprint | string? | Existing Linear fingerprint |
| issueId | string? | Linear internal id, required for attachments |
| issueIdentifier | string? | Human identifier |
| issueUrl | string? | User-facing URL |
| filingDecision | enum? | Existing filing decision |
| proposalLinks | list<ProposalLink> | One per generated PR |

### ProposalLink

| Field | Type | Rules |
| --- | --- | --- |
| url | string | GitHub PR URL |
| attachmentPending | boolean | True after PR creation until Linear attachment succeeds |
| attachedAt | instant? | Set when repaired/completed |

Transitions: `LESSONS_RECORDED -> ISSUE_FILED -> DISTILLING -> PR_CREATED -> PR_ATTACHED ->
COMPLETED`. A target failure can end in partial success while retaining every completed link.

## ManualDeployRequest

| Field | Type | Rules |
| --- | --- | --- |
| frontendCommit | 40-char SHA | From the compiled frontend, compared server-side |
| confirmation | string | Exact phrase containing the short SHA |

The backend derives the deploy SHA from its own build metadata; it never accepts an arbitrary
target from the browser. Local/dev `unknown` commits are ineligible.
