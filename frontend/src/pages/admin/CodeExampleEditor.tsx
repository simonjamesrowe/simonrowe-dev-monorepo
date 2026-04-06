import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminCodeExampleById,
  createAdminCodeExample,
  updateAdminCodeExample,
  fetchAdminSkills,
  type AdminSkill,
} from '../../services/adminApi'

const LANGUAGE_OPTIONS = [
  { value: 'java', label: 'Java' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'python', label: 'Python' },
  { value: 'go', label: 'Go' },
  { value: 'kotlin', label: 'Kotlin' },
  { value: 'bash', label: 'Bash' },
  { value: 'sql', label: 'SQL' },
  { value: 'yaml', label: 'YAML' },
  { value: 'json', label: 'JSON' },
  { value: 'other', label: 'Other' },
]

interface CodeExampleFormState {
  title: string
  description: string
  language: string
  code: string
  skills: string[]
}

const emptyForm: CodeExampleFormState = {
  title: '',
  description: '',
  language: 'java',
  code: '',
  skills: [],
}

export function CodeExampleEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [availableSkills, setAvailableSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<CodeExampleFormState>(emptyForm)

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

  useEffect(() => {
    if (isNew || !id) return
    const loadCodeExample = async () => {
      try {
        setLoading(true)
        const example = await fetchAdminCodeExampleById(getAccessToken, id)
        setForm({
          title: example.title,
          description: example.description,
          language: example.language,
          code: example.code,
          skills: example.skills ?? [],
        })
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load code example')
      } finally {
        setLoading(false)
      }
    }
    loadCodeExample()
  }, [getAccessToken, id, isNew])

  const handleSkillToggle = (skillId: string) => {
    const selected = form.skills.includes(skillId)
      ? form.skills.filter((s) => s !== skillId)
      : [...form.skills, skillId]
    setForm({ ...form, skills: selected })
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const payload = {
        title: form.title,
        description: form.description,
        language: form.language,
        code: form.code,
        skills: form.skills,
      }
      if (isNew) {
        await createAdminCodeExample(getAccessToken, payload)
      } else {
        await updateAdminCodeExample(getAccessToken, id!, payload)
      }
      navigate('/admin/code-examples')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div>Loading...</div>

  return (
    <div className="blog-editor">
      <div className="blog-editor__header">
        <h1>{isNew ? 'New Code Example' : 'Edit Code Example'}</h1>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="blog-editor__section">
          <label className="blog-editor__section-label">Title</label>
          <input
            type="text"
            className="admin-form__input"
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            maxLength={200}
            required
          />
        </div>

        <div className="blog-editor__section">
          <label className="blog-editor__section-label">Description</label>
          <textarea
            className="admin-form__input admin-form__textarea"
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            maxLength={2000}
            rows={4}
            required
          />
        </div>

        <div className="blog-editor__section">
          <label className="blog-editor__section-label">Language</label>
          <select
            className="admin-form__input"
            value={form.language}
            onChange={(e) => setForm({ ...form, language: e.target.value })}
          >
            {LANGUAGE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div className="blog-editor__section">
          <label className="blog-editor__section-label">Code</label>
          <textarea
            className="admin-form__input admin-form__textarea"
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value })}
            rows={16}
            required
            style={{ fontFamily: 'monospace', fontSize: '0.875rem' }}
          />
        </div>

        {availableSkills.length > 0 && (
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">Skills</label>
            <div className="admin-form__checkbox-list">
              {availableSkills.map((skill) => (
                <label key={skill.id} className="admin-form__checkbox-item">
                  <input
                    type="checkbox"
                    checked={form.skills.includes(skill.id)}
                    onChange={() => handleSkillToggle(skill.id)}
                  />
                  <span>{skill.name}</span>
                </label>
              ))}
            </div>
          </div>
        )}

        <div className="form-actions">
          <button type="submit" className="admin-btn admin-btn--primary" disabled={saving}>
            {saving ? 'Saving...' : 'Save'}
          </button>
          <button
            type="button"
            className="admin-btn"
            onClick={() => navigate('/admin/code-examples')}
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
