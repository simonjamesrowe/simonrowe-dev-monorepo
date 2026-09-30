import { CheckCircle, GripVertical, Pencil, Trash2, XCircle } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import { clearPortfolioCache } from '../../hooks/usePortfolio'
import {
  deleteAdminPortfolioProject,
  fetchAdminPortfolio,
  reorderAdminPortfolio,
  type AdminPortfolioProject,
} from '../../services/adminApi'
import { STATUS_LABELS } from '../../types/portfolio'

/** Portfolio projects in display order; drag a row to reorder, as the tour steps list does. */
export function PortfolioAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()
  const [projects, setProjects] = useState<AdminPortfolioProject[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<AdminPortfolioProject | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)
  const dragIndex = useRef<number | null>(null)

  const load = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      setProjects(await fetchAdminPortfolio(getAccessToken))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load projects')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    void load()
  }, [load])

  const handleDrop = async (event: React.DragEvent, dropIndex: number) => {
    event.preventDefault()
    const from = dragIndex.current
    dragIndex.current = null
    setOverIndex(null)
    if (from === null || from === dropIndex) return

    const reordered = [...projects]
    const [moved] = reordered.splice(from, 1)
    reordered.splice(dropIndex, 0, moved)
    setProjects(reordered)
    try {
      await reorderAdminPortfolio(getAccessToken, reordered.map(project => project.id))
      clearPortfolioCache()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder projects')
      void load()
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminPortfolioProject(getAccessToken, deleteTarget.id)
      clearPortfolioCache()
      setDeleteTarget(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete the project')
      setDeleteTarget(null)
    }
  }

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">Portfolio</h1>
        <button className="admin-btn admin-btn--primary" onClick={() => navigate('/admin/portfolio/new')} type="button">
          Add project
        </button>
      </div>

      {error ? <div className="admin-error-banner">{error}</div> : null}

      {loading ? (
        <div className="admin-loading">Loading projects...</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th aria-label="Reorder" style={{ width: 40 }} />
              <th className="admin-table__th">Name</th>
              <th className="admin-table__th">Status</th>
              <th className="admin-table__th">Published</th>
              <th className="admin-table__th">Actions</th>
            </tr>
          </thead>
          <tbody>
            {projects.length === 0 ? (
              <tr>
                <td className="admin-table__td admin-table__td--empty" colSpan={5}>No projects yet.</td>
              </tr>
            ) : null}
            {projects.map((project, index) => (
              <tr
                className={overIndex === index ? 'admin-table__row--drag-over' : ''}
                draggable
                key={project.id}
                onDragEnd={() => {
                  dragIndex.current = null
                  setOverIndex(null)
                }}
                onDragOver={event => {
                  event.preventDefault()
                  setOverIndex(index)
                }}
                onDragStart={() => {
                  dragIndex.current = index
                }}
                onDrop={event => void handleDrop(event, index)}
              >
                <td><span className="admin-table__grip"><GripVertical size={16} /></span></td>
                <td className="admin-table__td">
                  {project.name}
                  <div className="admin-table__sub">/portfolio/{project.slug}</div>
                </td>
                <td className="admin-table__td">{STATUS_LABELS[project.status]}</td>
                <td className="admin-table__td">
                  {project.published
                    ? <CheckCircle aria-label="Published" className="icon-published" size={18} />
                    : <XCircle aria-label="Not published" className="icon-draft" size={18} />}
                </td>
                <td className="admin-table__td admin-table__td--actions">
                  <button
                    aria-label={`Edit ${project.name}`}
                    className="admin-btn admin-btn--icon"
                    onClick={() => navigate(`/admin/portfolio/${project.id}`)}
                    title="Edit"
                    type="button"
                  >
                    <Pencil size={16} />
                  </button>
                  <button
                    aria-label={`Delete ${project.name}`}
                    className="admin-btn admin-btn--icon admin-btn--danger-icon"
                    onClick={() => setDeleteTarget(project)}
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
        confirmLabel="Delete"
        message={`Delete "${deleteTarget?.name}"? Its page will stop working. This cannot be undone.`}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => void handleDeleteConfirm()}
        open={deleteTarget !== null}
        title="Delete project"
      />
    </div>
  )
}
