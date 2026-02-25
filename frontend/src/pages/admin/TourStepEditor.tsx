import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminTourStepById,
  createAdminTourStep,
  updateAdminTourStep,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'

type Position = 'top' | 'bottom' | 'left' | 'right' | 'center'

interface TourStepFormState {
  title: string
  selector: string
  description: string
  titleImage: string
  position: Position | ''
  order: number
}

const emptyForm = (): TourStepFormState => ({
  title: '',
  selector: '',
  description: '',
  titleImage: '',
  position: '',
  order: 0,
})

const POSITIONS: Position[] = ['top', 'bottom', 'left', 'right', 'center']

export function TourStepEditor() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()

  const isNew = !id || id === 'new'

  const [form, setForm] = useState<TourStepFormState>(emptyForm())
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  const loadStep = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      setError(null)
      const data = await fetchAdminTourStepById(getAccessToken, id)
      setForm({
        title: data.title,
        selector: data.selector,
        description: data.description ?? '',
        titleImage: data.titleImage ?? '',
        position: (data.position as Position) ?? '',
        order: data.order,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load tour step')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadStep()
  }, [loadStep])

  const handleChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) => {
    const { name, value } = e.target
    setForm((prev) => ({
      ...prev,
      [name]: name === 'order' ? Number(value) : value,
    }))
    setDirty(true)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const payload: Record<string, unknown> = {
        ...form,
        position: form.position || null,
        description: form.description || null,
        titleImage: form.titleImage || null,
      }
      if (isNew) {
        await createAdminTourStep(getAccessToken, payload)
      } else {
        await updateAdminTourStep(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/tour-steps')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save tour step')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="admin-loading">Loading tour step...</div>
  }

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">{isNew ? 'New Tour Step' : 'Edit Tour Step'}</h1>
        <button
          className="admin-btn"
          onClick={() => navigate('/admin/tour-steps')}
          type="button"
        >
          Back to List
        </button>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      <form className="admin-form" onSubmit={handleSubmit}>
        <div className="admin-form__field">
          <label className="admin-form__label" htmlFor="title">Title</label>
          <input
            className="admin-form__input"
            id="title"
            name="title"
            onChange={handleChange}
            required
            type="text"
            value={form.title}
          />
        </div>

        <div className="admin-form__field">
          <label className="admin-form__label" htmlFor="selector">
            CSS Selector
          </label>
          <input
            className="admin-form__input admin-form__input--mono"
            id="selector"
            name="selector"
            onChange={handleChange}
            placeholder=".my-element or #element-id"
            required
            type="text"
            value={form.selector}
          />
        </div>

        <div className="admin-form__field">
          <label className="admin-form__label" htmlFor="description">Description</label>
          <textarea
            className="admin-form__textarea"
            id="description"
            name="description"
            onChange={handleChange}
            rows={4}
            value={form.description}
          />
        </div>

        <div className="admin-form__field">
          <label className="admin-form__label" htmlFor="titleImage">Title Image URL</label>
          <input
            className="admin-form__input"
            id="titleImage"
            name="titleImage"
            onChange={handleChange}
            type="text"
            value={form.titleImage}
          />
        </div>

        <div className="admin-form__field">
          <label className="admin-form__label" htmlFor="position">Position</label>
          <select
            className="admin-form__select"
            id="position"
            name="position"
            onChange={handleChange}
            value={form.position}
          >
            <option value="">-- none --</option>
            {POSITIONS.map((pos) => (
              <option key={pos} value={pos}>
                {pos.charAt(0).toUpperCase() + pos.slice(1)}
              </option>
            ))}
          </select>
        </div>

        <div className="admin-form__field">
          <label className="admin-form__label" htmlFor="order">Order</label>
          <input
            className="admin-form__input admin-form__input--narrow"
            id="order"
            min={0}
            name="order"
            onChange={handleChange}
            type="number"
            value={form.order}
          />
        </div>

        <div className="admin-form__actions">
          <button
            className="admin-btn admin-btn--primary"
            disabled={saving}
            type="submit"
          >
            {saving ? 'Saving...' : isNew ? 'Create Step' : 'Save Changes'}
          </button>
          <button
            className="admin-btn"
            onClick={() => navigate('/admin/tour-steps')}
            type="button"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
