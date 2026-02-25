import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import {
  createAdminSkill,
  fetchAdminSkillById,
  updateAdminSkill,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'

interface SkillFormState {
  name: string
  rating: number
  description: string
  image: string
  order: number
}

const emptyForm: SkillFormState = {
  name: '',
  rating: 0,
  description: '',
  image: '',
  order: 0,
}

export function SkillEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<SkillFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  const loadSkill = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const skill = await fetchAdminSkillById(getAccessToken, id)
      setForm({
        name: skill.name,
        rating: skill.rating ?? 0,
        description: skill.description ?? '',
        image: skill.image ?? '',
        order: skill.order,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load skill')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadSkill()
  }, [loadSkill])

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
      }
      if (isNew) {
        await createAdminSkill(getAccessToken, payload)
      } else {
        await updateAdminSkill(getAccessToken, id!, payload)
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
      <h1>{isNew ? 'New Skill' : 'Edit Skill'}</h1>
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
