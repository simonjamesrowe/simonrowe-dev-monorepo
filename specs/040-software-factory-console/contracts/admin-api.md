# Contract: Admin Software Factory API

All paths are beneath `/api/admin/software-factory` and therefore require the existing admin role.
Responses never contain the internal factory token.

## GET /status

Returns:

```json
{
  "fetchedAt": "2026-08-28T10:00:00Z",
  "frontendCommit": "<commit supplied separately by the frontend>",
  "backendCommit": "40-char sha or unknown",
  "factoryReachable": true,
  "deployerReachable": true,
  "modules": [
    {
      "key": "cvefix",
      "displayName": "Vulnerability scan",
      "configured": true,
      "ready": true,
      "taskQueue": "cve-fix",
      "workflowPollers": 1,
      "activityPollers": 1,
      "trigger": "manual-and-schedule",
      "schedule": {
        "scheduleId": "cve-fix-daily",
        "exists": true,
        "paused": false,
        "overlapPolicy": "SKIP",
        "previousActionAt": null,
        "nextActionAt": "2026-08-29T10:00:00Z",
        "runningActions": 0
      },
      "diagnostic": null
    }
  ]
}
```

Partial downstream failure returns `200` with affected fields null/false and a safe diagnostic.

## POST /feedback

Request: `{"pullNumber": 128}`

Responses: `202` with run identity; `409` already running/completed; `422` invalid/not closed;
`503` module disabled/unready.

## POST /vulnerability-scans

Empty request. Responses: `202` with run identity; `409` scan already running; `503` disabled,
unready, or Linear unavailable by configuration.

## POST /platform-backups

Request: `{"dryRun": true}` or `{"dryRun": false}`. A real run requires a confirmation step in
the UI. Responses: `202`; `409` capture already running; `503` disabled/unready.

## POST /deploys

Request:

```json
{
  "frontendCommit": "40-char sha",
  "confirmation": "REDEPLOY abc1234"
}
```

The backend compares `frontendCommit` with its own fresh build commit and derives the target.
Responses: `202`; `409` deploy already running; `412` frontend/backend drift or stale
confirmation; `503` deploy executor disabled/unready.

## GET /runs/{module}/{workflowId}

Returns normalized `FactoryRunProgress`. `module` is allowlisted; `workflowId` is validated and
sent only to the matching typed workflow query. Returns `404` for an unknown execution and `503`
for a downstream failure.
