import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminTourSteps,
  deleteAdminTourStep,
  reorderAdminTourSteps,
  type AdminTourStep,
} from '../../services/adminApi'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'

export function TourStepsAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()

  const [steps, setSteps] = useState<AdminTourStep[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reordering, setReordering] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  const loadSteps = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const data = await fetchAdminTourSteps(getAccessToken)
      const sorted = [...data].sort((a, b) => a.order - b.order)
      setSteps(sorted)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load tour steps')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    loadSteps()
  }, [loadSteps])

  const moveStep = async (index: number, direction: 'up' | 'down') => {
    const newSteps = [...steps]
    const targetIndex = direction === 'up' ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= newSteps.length) return

    const temp = newSteps[index]
    newSteps[index] = newSteps[targetIndex]
    newSteps[targetIndex] = temp

    const reordered = newSteps.map((step, i) => ({ id: step.id, order: i + 1 }))

    try {
      setReordering(true)
      setError(null)
      await reorderAdminTourSteps(getAccessToken, { steps: reordered })
      await loadSteps()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder tour steps')
    } finally {
      setReordering(false)
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      setError(null)
      await deleteAdminTourStep(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      await loadSteps()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete tour step')
      setDeleteTarget(null)
    }
  }

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">Tour Steps</h1>
        <button
          className="admin-btn admin-btn--primary"
          onClick={() => navigate('/admin/tour-steps/new')}
          type="button"
        >
          Add Step
        </button>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      {loading ? (
        <div className="admin-loading">Loading tour steps...</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th className="admin-table__th">Order</th>
              <th className="admin-table__th">Title</th>
              <th className="admin-table__th">Selector</th>
              <th className="admin-table__th">Position</th>
              <th className="admin-table__th">Actions</th>
            </tr>
          </thead>
          <tbody>
            {steps.length === 0 && (
              <tr>
                <td className="admin-table__td admin-table__td--empty" colSpan={5}>
                  No tour steps found.
                </td>
              </tr>
            )}
            {steps.map((step, index) => (
              <tr key={step.id} className="admin-table__row">
                <td className="admin-table__td admin-table__td--order">
                  <div className="admin-reorder">
                    <button
                      aria-label="Move up"
                      className="admin-btn admin-btn--sm admin-btn--icon"
                      disabled={index === 0 || reordering}
                      onClick={() => moveStep(index, 'up')}
                      type="button"
                    >
                      Up
                    </button>
                    <span className="admin-reorder__number">{step.order}</span>
                    <button
                      aria-label="Move down"
                      className="admin-btn admin-btn--sm admin-btn--icon"
                      disabled={index === steps.length - 1 || reordering}
                      onClick={() => moveStep(index, 'down')}
                      type="button"
                    >
                      Down
                    </button>
                  </div>
                </td>
                <td className="admin-table__td">{step.title}</td>
                <td className="admin-table__td admin-table__td--mono">{step.selector}</td>
                <td className="admin-table__td">{step.position ?? '-'}</td>
                <td className="admin-table__td admin-table__td--actions">
                  <button
                    className="admin-btn admin-btn--sm"
                    onClick={() => navigate(`/admin/tour-steps/${step.id}`)}
                    type="button"
                  >
                    Edit
                  </button>
                  <button
                    className="admin-btn admin-btn--sm admin-btn--danger"
                    onClick={() => setDeleteTarget({ id: step.id, name: step.title })}
                    type="button"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Tour Step"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
