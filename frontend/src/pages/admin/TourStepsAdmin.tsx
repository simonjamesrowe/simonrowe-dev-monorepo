import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { GripVertical, Pencil, Trash2 } from 'lucide-react'
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
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)
  const dragIndex = useRef<number | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)

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

  const handleDragStart = (index: number) => {
    dragIndex.current = index
  }

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault()
    setOverIndex(index)
  }

  const handleDrop = async (e: React.DragEvent, dropIndex: number) => {
    e.preventDefault()
    const from = dragIndex.current
    if (from === null || from === dropIndex) {
      dragIndex.current = null
      setOverIndex(null)
      return
    }

    const newSteps = [...steps]
    const [moved] = newSteps.splice(from, 1)
    newSteps.splice(dropIndex, 0, moved)
    setSteps(newSteps)
    dragIndex.current = null
    setOverIndex(null)

    try {
      await reorderAdminTourSteps(getAccessToken, { orderedIds: newSteps.map((s) => s.id) })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder tour steps')
      loadSteps()
    }
  }

  const handleDragEnd = () => {
    dragIndex.current = null
    setOverIndex(null)
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
              <th style={{ width: 40 }}></th>
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
              <tr
                key={step.id}
                draggable
                onDragStart={() => handleDragStart(index)}
                onDragOver={(e) => handleDragOver(e, index)}
                onDrop={(e) => handleDrop(e, index)}
                onDragEnd={handleDragEnd}
                className={overIndex === index ? 'admin-table__row--drag-over' : ''}
              >
                <td>
                  <span className="admin-table__grip">
                    <GripVertical size={16} />
                  </span>
                </td>
                <td className="admin-table__td">{step.title}</td>
                <td className="admin-table__td admin-table__td--mono">{step.selector}</td>
                <td className="admin-table__td">{step.position ?? '-'}</td>
                <td className="admin-table__td admin-table__td--actions">
                  <button
                    className="admin-btn admin-btn--icon"
                    onClick={() => navigate(`/admin/tour-steps/${step.id}`)}
                    title="Edit"
                    type="button"
                  >
                    <Pencil size={16} />
                  </button>
                  <button
                    className="admin-btn admin-btn--icon admin-btn--danger-icon"
                    onClick={() => setDeleteTarget({ id: step.id, name: step.title })}
                    title="Delete"
                    type="button"
                  >
                    <Trash2 size={16} />
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
