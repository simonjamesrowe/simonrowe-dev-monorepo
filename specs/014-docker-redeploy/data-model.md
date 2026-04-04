# Data Model: Docker Redeploy

## Entity Changes

### OperationType (enum extension)

Existing enum `OperationType` extended with new value:

| Value | Description | Existing? |
|-------|-------------|-----------|
| BACKUP | Google Drive backup | Yes |
| RESTORE | Google Drive restore | Yes |
| CLEAR | Clear all data | Yes |
| REBUILD_INDEX | Rebuild search indices | Yes |
| **REDEPLOY** | **Pull latest images and restart containers** | **New** |

No new collections, documents, or entities are required. The redeploy operation uses the existing `DataOperation` record structure:

```
DataOperation {
  id: String (UUID)
  type: OperationType (REDEPLOY)
  status: OperationStatus (IN_PROGRESS | COMPLETED | FAILED)
  startedAt: Instant
  completedAt: Instant (nullable)
  progressMessage: String
  progressPercent: int (0-100)
  errorMessage: String (nullable)
  resultSummary: String (nullable)
}
```

### Configuration Properties

New configuration for the redeploy service:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `redeploy.compose-file` | String | `/workspace/docker-compose.prod.yml` | Path to Docker Compose file inside container |
| `redeploy.services` | List<String> | `[backend, frontend, nginx]` | Services to redeploy |
| `redeploy.docker-binary` | String | `docker` | Path to Docker CLI binary |
| `redeploy.self-restart-delay-seconds` | int | `5` | Delay before backend restarts itself |

## State Transitions

```
IDLE → [Admin clicks Redeploy + Confirms]
  → IN_PROGRESS (0%: "Starting redeploy...")
  → IN_PROGRESS (10%: "Pulling backend image...")
  → IN_PROGRESS (30%: "Pulling frontend image...")
  → IN_PROGRESS (50%: "Restarting frontend...")
  → IN_PROGRESS (65%: "Restarting nginx...")
  → IN_PROGRESS (80%: "Preparing backend restart...")
  → COMPLETED (100%: "Redeploy complete. Backend restarting...")
  [Backend persists COMPLETED to MongoDB]
  [Backend process exits / container restarts]
  [Admin refreshes page → sees COMPLETED status]

Error path:
  → IN_PROGRESS (any%)
  → FAILED ("Image pull failed: <reason>" | "Container restart failed: <reason>")
```

## Relationships

No new entity relationships. The REDEPLOY operation type integrates into the existing `DataOperationsService` state machine alongside BACKUP, RESTORE, CLEAR, and REBUILD_INDEX. It follows the same one-operation-at-a-time constraint via the existing `AtomicReference` lock.
