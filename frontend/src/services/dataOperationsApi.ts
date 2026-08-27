import { API_BASE_URL } from '../config/api'

const DATA_OPS_URL = `${API_BASE_URL}/api/admin/data-operations`

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface DataOperation {
  id: string
  // Must list every OperationType the backend can emit. A missing member makes that
  // operation's SSE progress events typed as impossible, so the UI silently stops
  // narrating them - REEMBED_CONTENT was missing for exactly that reason.
  //
  // No PLATFORM_BACKUP: the platform capture runs in the `deployer` container, not as
  // a backend data operation, so it emits nothing here.
  type: 'BACKUP' | 'RESTORE' | 'CLEAR' | 'REBUILD_INDEX' | 'REEMBED_CONTENT'
  status: 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
  startedAt: string
  completedAt: string | null
  progressMessage: string
  progressPercent: number
  errorMessage: string | null
  resultSummary: string | null
}

export interface BackupMetadata {
  fileId: string
  fileName: string
  createdAt: string
  fileSize: number
  fileSizeFormatted: string
}

export interface DataOperationsStatus {
  googleDriveConnected: boolean
  googleDriveError: string | null
  operationInProgress: boolean
  currentOperation: DataOperation | null
  lastOperation: DataOperation | null
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

export type GetAccessToken = () => Promise<string>

async function authFetch(url: string, token: string, options?: RequestInit): Promise<Response> {
  return fetch(url, {
    ...options,
    headers: {
      ...options?.headers,
      Authorization: `Bearer ${token}`,
    },
  })
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = 'Request failed.'
    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default fallback message when the response has no JSON payload.
    }
    throw new Error(message)
  }
  return (await response.json()) as T
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

export async function fetchDataOpsStatus(
  getAccessToken: GetAccessToken,
): Promise<DataOperationsStatus> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/status`, token)
  return handleResponse<DataOperationsStatus>(response)
}

// ---------------------------------------------------------------------------
// Backup
// ---------------------------------------------------------------------------

export async function startBackup(
  getAccessToken: GetAccessToken,
): Promise<DataOperation> {
  const token = await getAccessToken()
  const url = `${DATA_OPS_URL}/backup`
  const response = await authFetch(url, token, { method: 'POST' })
  return handleResponse<DataOperation>(response)
}

// ---------------------------------------------------------------------------
// Backups list
// ---------------------------------------------------------------------------

export async function fetchBackups(
  getAccessToken: GetAccessToken,
): Promise<BackupMetadata[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/backups`, token)
  return handleResponse<BackupMetadata[]>(response)
}

// ---------------------------------------------------------------------------
// Platform backup (Postgres + ClickHouse)
//
// A separate Drive folder from the application backups above, with its own retention
// window, so that neither backup type can evict the other.
//
// Read-only, deliberately. The capture runs in the `deployer` container as
// scripts/backup-platform.sh - constitution 2.0.0 forbids the backend from holding
// Docker access - and restore is scripts/restore-platform.sh on the host. So there is
// no start call here, only a listing, which is what makes a stalled nightly job
// visible on the admin page.
// ---------------------------------------------------------------------------

export async function fetchPlatformBackups(
  getAccessToken: GetAccessToken,
): Promise<BackupMetadata[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/platform-backups`, token)
  return handleResponse<BackupMetadata[]>(response)
}

// ---------------------------------------------------------------------------
// Restore
// ---------------------------------------------------------------------------

export async function startRestore(
  getAccessToken: GetAccessToken,
  backupFileId: string,
): Promise<DataOperation> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/restore`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ backupFileId }),
  })
  return handleResponse<DataOperation>(response)
}

// ---------------------------------------------------------------------------
// Clear
// ---------------------------------------------------------------------------

export async function startClear(
  getAccessToken: GetAccessToken,
  confirmationPhrase: string,
): Promise<DataOperation> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/clear`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirmationPhrase }),
  })
  return handleResponse<DataOperation>(response)
}

// ---------------------------------------------------------------------------
// Rebuild index
// ---------------------------------------------------------------------------

export async function startRebuildIndex(
  getAccessToken: GetAccessToken,
): Promise<DataOperation> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/rebuild-index`, token, { method: 'POST' })
  return handleResponse<DataOperation>(response)
}

// ---------------------------------------------------------------------------
// Re-embed content
// ---------------------------------------------------------------------------

export async function startReembed(
  getAccessToken: GetAccessToken,
): Promise<DataOperation> {
  const token = await getAccessToken()
  const response = await authFetch(`${DATA_OPS_URL}/reembed`, token, { method: 'POST' })
  return handleResponse<DataOperation>(response)
}

// ---------------------------------------------------------------------------
// SSE progress stream
// ---------------------------------------------------------------------------

export function connectProgress(
  token: string,
  onEvent: (operation: DataOperation) => void,
  onError?: (error: unknown) => void,
): { close: () => void } {
  const controller = new AbortController()

  fetch(`${DATA_OPS_URL}/progress`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: controller.signal,
  })
    .then((response) => {
      if (!response.ok || !response.body) {
        onError?.(new Error(`SSE connection failed: ${response.status}`))
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function read(): void {
        reader.read().then(({ done, value }) => {
          if (done) return
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() ?? ''
          for (const line of lines) {
            if (line.startsWith('data:')) {
              try {
                const operation = JSON.parse(line.slice(5).trim()) as DataOperation
                onEvent(operation)
              } catch {
                // Ignore malformed events
              }
            }
          }
          read()
        }).catch((err) => {
          if (!controller.signal.aborted) {
            onError?.(err)
          }
        })
      }
      read()
    })
    .catch((err) => {
      if (!controller.signal.aborted) {
        onError?.(err)
      }
    })

  return { close: () => controller.abort() }
}
