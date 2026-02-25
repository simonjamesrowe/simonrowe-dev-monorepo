import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import {
  deleteAdminSkill,
  deleteAdminSkillGroup,
  fetchAdminSkillGroups,
  fetchAdminSkills,
  reorderAdminSkillGroups,
  reorderAdminSkills,
  type AdminSkill,
  type AdminSkillGroup,
} from '../../services/adminApi'

type ActiveTab = 'skills' | 'groups'
type DeleteTarget = { id: string; name: string; type: 'skill' | 'group' }

export function SkillsAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<ActiveTab>('skills')
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [skillGroups, setSkillGroups] = useState<AdminSkillGroup[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null)

  const loadData = useCallback(async () => {
    try {
      setLoading(true)
      const [skillPage, groupPage] = await Promise.all([
        fetchAdminSkills(getAccessToken, 0, 100),
        fetchAdminSkillGroups(getAccessToken, 0, 100),
      ])
      const sortedSkills = [...skillPage.content].sort((a, b) => a.order - b.order)
      const sortedGroups = [...groupPage.content].sort((a, b) => a.order - b.order)
      setSkills(sortedSkills)
      setSkillGroups(sortedGroups)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load data')
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
      if (deleteTarget.type === 'skill') {
        await deleteAdminSkill(getAccessToken, deleteTarget.id)
      } else {
        await deleteAdminSkillGroup(getAccessToken, deleteTarget.id)
      }
      setDeleteTarget(null)
      loadData()
    } catch (err) {
      const label = deleteTarget.type === 'skill' ? 'skill' : 'skill group'
      setError(err instanceof Error ? err.message : `Failed to delete ${label}`)
      setDeleteTarget(null)
    }
  }

  const handleMoveSkill = async (index: number, direction: 'up' | 'down') => {
    const newSkills = [...skills]
    const targetIndex = direction === 'up' ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= newSkills.length) return

    const temp = newSkills[index]
    newSkills[index] = newSkills[targetIndex]
    newSkills[targetIndex] = temp

    const reordered = newSkills.map((s, i) => ({ id: s.id, order: i }))
    setSkills(newSkills)

    try {
      await reorderAdminSkills(getAccessToken, { items: reordered })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder skills')
      loadData()
    }
  }

  const handleMoveGroup = async (index: number, direction: 'up' | 'down') => {
    const newGroups = [...skillGroups]
    const targetIndex = direction === 'up' ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= newGroups.length) return

    const temp = newGroups[index]
    newGroups[index] = newGroups[targetIndex]
    newGroups[targetIndex] = temp

    const reordered = newGroups.map((g, i) => ({ id: g.id, order: i }))
    setSkillGroups(newGroups)

    try {
      await reorderAdminSkillGroups(getAccessToken, { items: reordered })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder skill groups')
      loadData()
    }
  }

  if (loading) return <div>Loading...</div>
  if (error) return <div className="error">{error}</div>

  const dialogTitle = deleteTarget?.type === 'group' ? 'Delete Skill Group' : 'Delete Skill'

  return (
    <div>
      <h1>Skills</h1>
      <div className="admin-tabs">
        <button
          className={activeTab === 'skills' ? 'active' : ''}
          onClick={() => setActiveTab('skills')}
        >
          Skills
        </button>
        <button
          className={activeTab === 'groups' ? 'active' : ''}
          onClick={() => setActiveTab('groups')}
        >
          Skill Groups
        </button>
      </div>

      {activeTab === 'skills' && (
        <div>
          <div className="admin-header">
            <h2>Skills</h2>
            <button onClick={() => navigate('/admin/skills/new')}>New Skill</button>
          </div>
          <table className="admin-table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Name</th>
                <th>Rating</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {skills.map((skill, index) => (
                <tr key={skill.id}>
                  <td>
                    <button
                      onClick={() => handleMoveSkill(index, 'up')}
                      disabled={index === 0}
                      aria-label="Move up"
                    >
                      Up
                    </button>
                    <button
                      onClick={() => handleMoveSkill(index, 'down')}
                      disabled={index === skills.length - 1}
                      aria-label="Move down"
                    >
                      Down
                    </button>
                  </td>
                  <td>{skill.name}</td>
                  <td>{skill.rating ?? '-'}</td>
                  <td>
                    <button onClick={() => navigate(`/admin/skills/${skill.id}`)}>Edit</button>
                    <button
                      onClick={() =>
                        setDeleteTarget({ id: skill.id, name: skill.name, type: 'skill' })
                      }
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {activeTab === 'groups' && (
        <div>
          <div className="admin-header">
            <h2>Skill Groups</h2>
            <button onClick={() => navigate('/admin/skill-groups/new')}>New Skill Group</button>
          </div>
          <table className="admin-table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Name</th>
                <th>Rating</th>
                <th>Skills</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {skillGroups.map((group, index) => (
                <tr key={group.id}>
                  <td>
                    <button
                      onClick={() => handleMoveGroup(index, 'up')}
                      disabled={index === 0}
                      aria-label="Move up"
                    >
                      Up
                    </button>
                    <button
                      onClick={() => handleMoveGroup(index, 'down')}
                      disabled={index === skillGroups.length - 1}
                      aria-label="Move down"
                    >
                      Down
                    </button>
                  </td>
                  <td>{group.name}</td>
                  <td>{group.rating ?? '-'}</td>
                  <td>{group.skills?.length ?? 0}</td>
                  <td>
                    <button onClick={() => navigate(`/admin/skill-groups/${group.id}`)}>
                      Edit
                    </button>
                    <button
                      onClick={() =>
                        setDeleteTarget({ id: group.id, name: group.name, type: 'group' })
                      }
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title={dialogTitle}
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
