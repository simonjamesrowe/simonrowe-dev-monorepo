import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { GripVertical, Pencil, Trash2 } from 'lucide-react'
import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import {
  deleteAdminSkillGroup,
  fetchAdminSkillGroups,
  reorderAdminSkillGroups,
  type AdminSkillGroup,
} from '../../services/adminApi'

export function SkillsAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()
  const [groups, setGroups] = useState<AdminSkillGroup[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)
  const dragIndex = useRef<number | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)

  const loadData = useCallback(async () => {
    try {
      setLoading(true)
      const page = await fetchAdminSkillGroups(getAccessToken, 0, 100)
      setGroups([...page.content].sort((a, b) => a.order - b.order))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load skill groups')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminSkillGroup(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      loadData()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete skill group')
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

    const newGroups = [...groups]
    const [moved] = newGroups.splice(from, 1)
    newGroups.splice(dropIndex, 0, moved)
    setGroups(newGroups)
    dragIndex.current = null
    setOverIndex(null)

    try {
      await reorderAdminSkillGroups(getAccessToken, { orderedIds: newGroups.map((g) => g.id) })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder skill groups')
      loadData()
    }
  }

  const handleDragEnd = () => {
    dragIndex.current = null
    setOverIndex(null)
  }

  if (loading) return <div>Loading...</div>
  if (error) return <div className="error">{error}</div>

  return (
    <div>
      <div className="admin-header">
        <h1>Skill Groups</h1>
        <button
          className="admin-btn admin-btn--primary"
          onClick={() => navigate('/admin/skill-groups/new')}
        >
          New Skill Group
        </button>
      </div>

      <table className="admin-table">
        <thead>
          <tr>
            <th style={{ width: 40 }}></th>
            <th>Name</th>
            <th>Skills</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((group, index) => (
            <tr
              key={group.id}
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
              <td>
                <Link to={`/admin/skill-groups/${group.id}`}>{group.name}</Link>
              </td>
              <td>{group.skills?.length ?? 0}</td>
              <td>
                <button
                  className="admin-btn admin-btn--icon"
                  onClick={() => navigate(`/admin/skill-groups/${group.id}`)}
                  title="Edit"
                >
                  <Pencil size={16} />
                </button>
                <button
                  className="admin-btn admin-btn--icon admin-btn--danger-icon"
                  onClick={() => setDeleteTarget({ id: group.id, name: group.name })}
                  title="Delete"
                >
                  <Trash2 size={16} />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Skill Group"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
