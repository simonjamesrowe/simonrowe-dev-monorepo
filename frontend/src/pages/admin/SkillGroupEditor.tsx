import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import {
  createAdminSkillGroup,
  fetchAdminSkillGroupById,
  fetchAdminSkills,
  updateAdminSkillGroup,
  type AdminSkill,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'

interface SkillGroupFormState {
  name: string
  rating: number
  description: string
  image: string
  order: number
  skills: string[]
}

const emptyForm: SkillGroupFormState = {
  name: '',
  rating: 0,
  description: '',
  image: '',
  order: 0,
  skills: [],
}

function toggleArrayItem(arr: string[], item: string): string[] {
  return arr.includes(item) ? arr.filter((i) => i !== item) : [...arr, item]
}

export function SkillGroupEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [availableSkills, setAvailableSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<SkillGroupFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  useEffect(() => {
    const loadSkills = async () => {
      try {
        const skillPage = await fetchAdminSkills(getAccessToken, 0, 100)
        setAvailableSkills(skillPage.content)
      } catch {
        // non-fatal
      }
    }
    loadSkills()
  }, [getAccessToken])

  const loadGroup = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const group = await fetchAdminSkillGroupById(getAccessToken, id)
      setForm({
        name: group.name,
        rating: group.rating ?? 0,
        description: group.description ?? '',
        image: group.image ?? '',
        order: group.order,
        skills: group.skills ?? [],
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load skill group')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadGroup()
  }, [loadGroup])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const payload = {
        name: form.name,
        rating: form.rating,
        description: form.description,
        image: form.image,
        order: form.order,
        skills: form.skills,
      }
      if (isNew) {
        await createAdminSkillGroup(getAccessToken, payload)
      } else {
        await updateAdminSkillGroup(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/skills')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div>Loading...</div>

  return (
    <div>
      <h1>{isNew ? 'New Skill Group' : 'Edit Skill Group'}</h1>
      {error && <div className="error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label>Name</label>
          <input
            type="text"
            value={form.name}
            onChange={(e) => { setForm({ ...form, name: e.target.value }); setDirty(true) }}
            required
          />
        </div>
        <div className="form-group">
          <label>Rating (0-10)</label>
          <input
            type="number"
            min={0}
            max={10}
            value={form.rating}
            onChange={(e) => { setForm({ ...form, rating: Number(e.target.value) }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Description</label>
          <textarea
            value={form.description}
            onChange={(e) => { setForm({ ...form, description: e.target.value }); setDirty(true) }}
            rows={4}
          />
        </div>
        <div className="form-group">
          <label>Image URL</label>
          <input
            type="text"
            value={form.image}
            onChange={(e) => { setForm({ ...form, image: e.target.value }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Order</label>
          <input
            type="number"
            min={0}
            value={form.order}
            onChange={(e) => { setForm({ ...form, order: Number(e.target.value) }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Skills</label>
          <div className="checkbox-list">
            {availableSkills.map((skill) => (
              <label key={skill.id}>
                <input
                  type="checkbox"
                  checked={form.skills.includes(skill.id)}
                  onChange={() => {
                    setForm({ ...form, skills: toggleArrayItem(form.skills, skill.id) })
                    setDirty(true)
                  }}
                />
                {' '}{skill.name}
              </label>
            ))}
          </div>
        </div>
        <div className="form-actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Saving...' : 'Save'}
          </button>
          <button type="button" onClick={() => navigate('/admin/skills')}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
