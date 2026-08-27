import { useCallback, useEffect, useRef, useState } from 'react'
import {
  CloudUpload,
  CloudDownload,
  RefreshCw,
  Trash2,
  CheckCircle,
  XCircle,
  AlertCircle,
  Loader,
} from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchDataOpsStatus,
  startBackup,
  fetchBackups,
  startRestore,
  startClear,
  startRebuildIndex,
  startReembed,
  connectProgress,
  type DataOperation,
  type DataOperationsStatus,
  type BackupMetadata,
} from '../../services/dataOperationsApi'

export function DataOperationsAdmin() {
  const { getAccessToken } = useAuth()

  const [status, setStatus] = useState<DataOperationsStatus | null>(null)
  const [operation, setOperation] = useState<DataOperation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  // Restore-specific state
  const [backups, setBackups] = useState<BackupMetadata[]>([])
  const [showBackups, setShowBackups] = useState(false)
  const [loadingBackups, setLoadingBackups] = useState(false)
  const [selectedBackup, setSelectedBackup] = useState<BackupMetadata | null>(null)
  const [showRestoreConfirm, setShowRestoreConfirm] = useState(false)

  // Clear-specific state
  const [showClearConfirm, setShowClearConfirm] = useState(false)
  const [clearPhrase, setClearPhrase] = useState('')

  // Rebuild confirm state
  const [showRebuildConfirm, setShowRebuildConfirm] = useState(false)

  // Reembed state
  const [showReembedConfirm, setShowReembedConfirm] = useState(false)


  const eventSourceRef = useRef<{ close: () => void } | null>(null)

  const loadStatus = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchDataOpsStatus(getAccessToken)
      setStatus(data)
      if (data.currentOperation) {
        setOperation(data.currentOperation)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load status')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    loadStatus()
  }, [loadStatus])


  const connectSse = useCallback(async () => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
    }
    const token = await getAccessToken()
    const es = connectProgress(
      token,
      (op) => {
        setOperation(op)
        if (op.status === 'COMPLETED') {
          setSuccess(op.resultSummary || 'Operation completed successfully')
          setError(null)
          loadStatus()
          es.close()
          eventSourceRef.current = null
        } else if (op.status === 'FAILED') {
          setError(op.errorMessage || 'Operation failed')
          setSuccess(null)
          loadStatus()
          es.close()
          eventSourceRef.current = null
        }
      },
    )
    eventSourceRef.current = es
  }, [getAccessToken, loadStatus])

  useEffect(() => {
    return () => {
      eventSourceRef.current?.close()
    }
  }, [])

  const operationInProgress = operation?.status === 'IN_PROGRESS'
  const driveConnected = status?.googleDriveConnected ?? false

  // --- Backup ---
  const handleBackup = async () => {
    try {
      setError(null)
      setSuccess(null)
      await connectSse()
      await startBackup(getAccessToken)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start backup')
    }
  }

  // --- Restore ---
  const handleShowBackups = async () => {
    try {
      setLoadingBackups(true)
      setError(null)
      const data = await fetchBackups(getAccessToken)
      setBackups(data)
      setShowBackups(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load backups')
    } finally {
      setLoadingBackups(false)
    }
  }

  const handleRestoreConfirm = async () => {
    if (!selectedBackup) return
    try {
      setShowRestoreConfirm(false)
      setShowBackups(false)
      setError(null)
      setSuccess(null)
      await connectSse()
      await startRestore(getAccessToken, selectedBackup.fileId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start restore')
    } finally {
      setSelectedBackup(null)
    }
  }

  // --- Clear ---
  const handleClearConfirm = async () => {
    try {
      setShowClearConfirm(false)
      setClearPhrase('')
      setError(null)
      setSuccess(null)
      await connectSse()
      await startClear(getAccessToken, 'DELETE ALL DATA')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start clear')
    }
  }

  // --- Rebuild ---
  const handleRebuildConfirm = async () => {
    try {
      setShowRebuildConfirm(false)
      setError(null)
      setSuccess(null)
      await connectSse()
      await startRebuildIndex(getAccessToken)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start index rebuild')
    }
  }

  // --- Reembed ---
  const handleReembedConfirm = async () => {
    try {
      setShowReembedConfirm(false)
      setError(null)
      setSuccess(null)
      await connectSse()
      await startReembed(getAccessToken)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start re-embedding')
    }
  }

  if (loading) {
    return <div className="admin-loading">Loading...</div>
  }

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">Data Operations</h1>
      </div>

      {/* Google Drive Connection Status */}
      <div className="data-ops__status">
        <div className={`data-ops__status-badge ${driveConnected ? 'data-ops__status-badge--connected' : 'data-ops__status-badge--disconnected'}`}>
          {driveConnected ? <CheckCircle size={16} /> : <XCircle size={16} />}
          <span>Google Drive: {driveConnected ? 'Connected' : 'Not Connected'}</span>
        </div>
        {status?.googleDriveError && (
          <p className="data-ops__status-error">{status.googleDriveError}</p>
        )}
      </div>

      {/* Notifications */}
      {error && <div className="admin-error-banner"><AlertCircle size={16} /> {error}</div>}
      {success && <div className="admin-success-banner"><CheckCircle size={16} /> {success}</div>}

      {/* Progress */}
      {operationInProgress && operation && (
        <div className="data-ops__progress">
          <div className="data-ops__progress-header">
            <Loader size={16} className="data-ops__spinner" />
            <span>{operation.type.replace('_', ' ')} in progress</span>
          </div>
          <div className="data-ops__progress-bar">
            <div
              className="data-ops__progress-fill"
              style={{ width: `${operation.progressPercent}%` }}
            />
          </div>
          <p className="data-ops__progress-message">{operation.progressMessage}</p>
        </div>
      )}

      {/* Action Cards */}
      <div className="data-ops__actions">
        <div className="data-ops__card">
          <div className="data-ops__card-icon"><CloudUpload size={24} /></div>
          <h3 className="data-ops__card-title">Backup to Google Drive</h3>
          <p className="data-ops__card-desc">
            Create a full backup to Google Drive. Includes the database, uploaded media files,
            and generated blog narration audio.
          </p>
          <div className="data-ops__card-actions">
            <button
              className="admin-btn admin-btn--secondary"
              disabled={operationInProgress || !driveConnected}
              onClick={() => handleBackup()}
              type="button"
            >
              Backup Now (full)
            </button>
          </div>
        </div>

        <div className="data-ops__card">
          <div className="data-ops__card-icon"><CloudDownload size={24} /></div>
          <h3 className="data-ops__card-title">Restore from Google Drive</h3>
          <p className="data-ops__card-desc">
            Restore data from a previous backup. A safety backup is created first.
          </p>
          <button
            className="admin-btn admin-btn--primary"
            disabled={operationInProgress || !driveConnected || loadingBackups}
            onClick={handleShowBackups}
            type="button"
          >
            {loadingBackups ? 'Loading...' : 'Choose Backup'}
          </button>
        </div>

        <div className="data-ops__card">
          <div className="data-ops__card-icon data-ops__card-icon--danger"><Trash2 size={24} /></div>
          <h3 className="data-ops__card-title">Clear All Data</h3>
          <p className="data-ops__card-desc">
            Permanently delete all local data. Google Drive backups are not affected.
          </p>
          <button
            className="admin-btn admin-btn--danger"
            disabled={operationInProgress}
            onClick={() => setShowClearConfirm(true)}
            type="button"
          >
            Clear All Data
          </button>
        </div>

        <div className="data-ops__card">
          <div className="data-ops__card-icon"><RefreshCw size={24} /></div>
          <h3 className="data-ops__card-title">Rebuild Search Index</h3>
          <p className="data-ops__card-desc">
            Rebuild Elasticsearch indices from current database content.
          </p>
          <button
            className="admin-btn admin-btn--primary"
            disabled={operationInProgress}
            onClick={() => setShowRebuildConfirm(true)}
            type="button"
          >
            Rebuild Index
          </button>
        </div>

        <div className="data-ops__card">
          <div className="data-ops__card-icon"><RefreshCw size={24} /></div>
          <h3 className="data-ops__card-title">Re-embed Content</h3>
          <p className="data-ops__card-desc">
            Re-generate all vector embeddings for blogs, jobs, skills, and code examples.
          </p>
          <button
            className="admin-btn admin-btn--primary"
            disabled={operationInProgress}
            onClick={() => setShowReembedConfirm(true)}
            type="button"
          >
            Re-embed All
          </button>
        </div>
      </div>

      {/* Backup List Panel */}
      {showBackups && (
        <div className="confirm-dialog-backdrop" onClick={() => setShowBackups(false)}>
          <div className="data-ops__backup-panel" onClick={(e) => e.stopPropagation()}>
            <h2 className="data-ops__backup-panel-title">Available Backups</h2>
            {backups.length === 0 ? (
              <p className="data-ops__backup-empty">No backups found in Google Drive.</p>
            ) : (
              <table className="admin-table">
                <thead>
                  <tr>
                    <th className="admin-table__th">File Name</th>
                    <th className="admin-table__th">Date</th>
                    <th className="admin-table__th">Size</th>
                    <th className="admin-table__th">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {backups.map((backup) => (
                    <tr key={backup.fileId} className="admin-table__row">
                      <td className="admin-table__td">{backup.fileName}</td>
                      <td className="admin-table__td">
                        {new Date(backup.createdAt).toLocaleString()}
                      </td>
                      <td className="admin-table__td">{backup.fileSizeFormatted}</td>
                      <td className="admin-table__td">
                        <button
                          className="admin-btn admin-btn--sm admin-btn--primary"
                          onClick={() => {
                            setSelectedBackup(backup)
                            setShowRestoreConfirm(true)
                          }}
                          type="button"
                        >
                          Restore
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="data-ops__backup-panel-footer">
              <button
                className="admin-btn admin-btn--sm"
                onClick={() => setShowBackups(false)}
                type="button"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Restore Confirm Dialog */}
      {showRestoreConfirm && selectedBackup && (
        <div className="confirm-dialog-backdrop" onClick={() => setShowRestoreConfirm(false)}>
          <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="confirm-dialog__title">Confirm Restore</h2>
            <p className="confirm-dialog__message">
              All current data will be replaced with the contents of <strong>{selectedBackup.fileName}</strong>.
              A safety backup will be created automatically before restoring.
            </p>
            <div className="confirm-dialog__actions">
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--cancel"
                onClick={() => setShowRestoreConfirm(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--confirm"
                onClick={handleRestoreConfirm}
              >
                Restore
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Clear Confirm Dialog */}
      {showClearConfirm && (
        <div className="confirm-dialog-backdrop" onClick={() => { setShowClearConfirm(false); setClearPhrase('') }}>
          <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="confirm-dialog__title">Clear All Data</h2>
            <p className="confirm-dialog__message">
              This will permanently delete all database collections, uploaded media files, and search indices.
              Google Drive backups will <strong>not</strong> be affected.
            </p>
            <p className="confirm-dialog__message">
              Type <strong>DELETE ALL DATA</strong> to confirm:
            </p>
            <input
              className="data-ops__confirm-input"
              type="text"
              value={clearPhrase}
              onChange={(e) => setClearPhrase(e.target.value)}
              placeholder="DELETE ALL DATA"
            />
            <div className="confirm-dialog__actions">
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--cancel"
                onClick={() => { setShowClearConfirm(false); setClearPhrase('') }}
              >
                Cancel
              </button>
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--confirm"
                disabled={clearPhrase !== 'DELETE ALL DATA'}
                onClick={handleClearConfirm}
              >
                Clear All Data
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Rebuild Confirm Dialog */}
      {showRebuildConfirm && (
        <div className="confirm-dialog-backdrop" onClick={() => setShowRebuildConfirm(false)}>
          <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="confirm-dialog__title">Rebuild Search Index</h2>
            <p className="confirm-dialog__message">
              This will delete and recreate all search indices, then re-index all content from the database.
            </p>
            <div className="confirm-dialog__actions">
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--cancel"
                onClick={() => setShowRebuildConfirm(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--confirm"
                onClick={handleRebuildConfirm}
              >
                Rebuild
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Reembed Confirm Dialog */}
      {showReembedConfirm && (
        <div className="confirm-dialog-backdrop" onClick={() => setShowReembedConfirm(false)}>
          <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="confirm-dialog__title">Re-embed All Content</h2>
            <p className="confirm-dialog__message">
              This will regenerate vector embeddings for all blog posts, jobs, skills, and code examples.
              Chat will continue to work during re-embedding using existing embeddings.
            </p>
            <div className="confirm-dialog__actions">
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--cancel"
                onClick={() => setShowReembedConfirm(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="confirm-dialog__btn confirm-dialog__btn--confirm"
                onClick={handleReembedConfirm}
              >
                Re-embed
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Last Operation Result */}
      {!operationInProgress && status?.lastOperation && (
        <div className="data-ops__last-operation">
          <h3>Last Operation</h3>
          <p>
            <strong>{status.lastOperation.type.replace('_', ' ')}</strong>
            {' — '}
            {status.lastOperation.status === 'COMPLETED'
              ? <span className="data-ops__result--success">{status.lastOperation.resultSummary}</span>
              : <span className="data-ops__result--error">{status.lastOperation.errorMessage}</span>
            }
          </p>
          <p className="data-ops__last-time">
            {status.lastOperation.completedAt
              ? new Date(status.lastOperation.completedAt).toLocaleString()
              : ''}
          </p>
        </div>
      )}
    </div>
  )
}
