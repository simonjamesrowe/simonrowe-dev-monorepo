# Contract: Internal Factory Operations API

These endpoints remain on factory port 8090 and are not routed by nginx.

## Read-only status

`GET /api/factory/status` requires no factory token, matching the existing internal version
endpoint. It returns local effective flags and global Temporal poller/schedule facts, contains no
secret values, and is callable on both `software-factory` and `deployer`.

## Action authentication

Every action and progress endpoint requires `X-Factory-Token`, compared in constant time. A blank
configured token returns `503`; a missing/wrong token returns `401`. Action controllers are
inactive on `deployer`, which is not given the token and continues to accept side effects only via
Temporal.

## Feedback

- `POST /api/feedback` keeps its current validated owner/repository/pull-number request and is
  additionally gated by the feedback enabled flag.
- `GET /api/feedback/{workflowId}` keeps its typed progress query.

## Vulnerability scans

- `POST /api/vulnerability-scans` accepts no caller-supplied repository or workflow settings.
- The server creates a unique `cve-scan-manual-{timestamp-or-id}` identity and snapshots the Linear
  enabled flag into the workflow request.
- `GET /api/vulnerability-scans/{workflowId}` returns typed scan progress.

## Deploy

- `POST /api/deploys` accepts a server-validated 40-character SHA and trigger `manual`.
- It uses the existing fixed deploy workflow id and signal-with-start coalescing.
- `GET /api/deploys/deploy-prod` returns the existing progress query.
- Automatic webhook gating remains independent; manual start does not require the automatic
  trigger flag.

## Platform backup

- `POST /api/platform-backups` accepts only `{"dryRun": boolean}`.
- It starts a uniquely identified workflow on `platform-backup` with conflict prevention.
- `GET /api/platform-backups/{workflowId}` returns terminal/running status without waiting for the
  backup method result in the initiating request.

## Linear contract evolution

`FiledIssue` adds nullable `issueId`; old histories deserialize it as null. `LinearActivities`
adds an idempotent URL-attachment operation accepting `issueId`, URL, and title. Feedback calls it
only when a real issue id was returned; dry-run and suppressed decisions create no PR.
