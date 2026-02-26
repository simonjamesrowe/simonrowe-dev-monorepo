import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import {
  createAdminJob,
  fetchAdminJobById,
  fetchAdminSkills,
  updateAdminJob,
  type AdminSkill,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'

interface JobFormState {
  title: string
  company: string
  companyUrl: string
  companyImage: string
  startDate: string
  endDate: string
  location: string
  shortDescription: string
  longDescription: string
  education: boolean
  includeOnResume: boolean
  skills: string[]
}

const emptyForm: JobFormState = {
  title: '',
  company: '',
  companyUrl: '',
  companyImage: '',
  startDate: '',
  endDate: '',
  location: '',
  shortDescription: '',
  longDescription: '',
  education: false,
  includeOnResume: true,
  skills: [],
}

function toggleArrayItem(arr: string[], item: string): string[] {
  return arr.includes(item) ? arr.filter((i) => i !== item) : [...arr, item]
}

export function JobEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<JobFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  useEffect(() => {
    const loadSkills = async () => {
      try {
        const skillPage = await fetchAdminSkills(getAccessToken, 0, 100)
        setSkills(skillPage.content)
      } catch {
        // non-fatal
      }
    }
    loadSkills()
  }, [getAccessToken])

  const loadJob = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const job = await fetchAdminJobById(getAccessToken, id)
      setForm({
        title: job.title,
        company: job.company,
        companyUrl: job.companyUrl ?? '',
        companyImage: job.companyImage ?? '',
        startDate: job.startDate,
        endDate: job.endDate ?? '',
        location: job.location ?? '',
        shortDescription: job.shortDescription ?? '',
        longDescription: job.longDescription ?? '',
        education: job.education,
        includeOnResume: job.includeOnResume,
        skills: job.skills ?? [],
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load job')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadJob()
  }, [loadJob])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const payload = {
        title: form.title,
        company: form.company,
        companyUrl: form.companyUrl,
        companyImage: form.companyImage,
        startDate: form.startDate,
        endDate: form.endDate,
        location: form.location,
        shortDescription: form.shortDescription,
        longDescription: form.longDescription,
        education: form.education,
        includeOnResume: form.includeOnResume,
        skills: form.skills,
      }
      if (isNew) {
        await createAdminJob(getAccessToken, payload)
      } else {
        await updateAdminJob(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/jobs')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div>Loading...</div>

  return (
    <div>
      <h1>{isNew ? 'New Job' : 'Edit Job'}</h1>
      {error && <div className="error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label>Title</label>
          <input
            type="text"
            value={form.title}
            onChange={(e) => { setForm({ ...form, title: e.target.value }); setDirty(true) }}
            required
          />
        </div>
        <div className="form-group">
          <label>Company</label>
          <input
            type="text"
            value={form.company}
            onChange={(e) => { setForm({ ...form, company: e.target.value }); setDirty(true) }}
            required
          />
        </div>
        <div className="form-group">
          <label>Company URL</label>
          <input
            type="text"
            value={form.companyUrl}
            onChange={(e) => { setForm({ ...form, companyUrl: e.target.value }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Company Image URL</label>
          <input
            type="text"
            value={form.companyImage}
            onChange={(e) => { setForm({ ...form, companyImage: e.target.value }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Start Date</label>
          <input
            type="date"
            value={form.startDate}
            onChange={(e) => { setForm({ ...form, startDate: e.target.value }); setDirty(true) }}
            required
          />
        </div>
        <div className="form-group">
          <label>End Date</label>
          <input
            type="date"
            value={form.endDate}
            onChange={(e) => { setForm({ ...form, endDate: e.target.value }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Location</label>
          <input
            type="text"
            value={form.location}
            onChange={(e) => { setForm({ ...form, location: e.target.value }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>Short Description</label>
          <textarea
            value={form.shortDescription}
            onChange={(e) => { setForm({ ...form, shortDescription: e.target.value }); setDirty(true) }}
            rows={3}
          />
        </div>
        <div className="form-group">
          <label>Long Description</label>
          <textarea
            value={form.longDescription}
            onChange={(e) => { setForm({ ...form, longDescription: e.target.value }); setDirty(true) }}
            rows={10}
          />
        </div>
        <div className="form-group">
          <label>
            <input
              type="checkbox"
              checked={form.education}
              onChange={(e) => { setForm({ ...form, education: e.target.checked }); setDirty(true) }}
            />
            {' '}Education
          </label>
        </div>
        <div className="form-group">
          <label>
            <input
              type="checkbox"
              checked={form.includeOnResume}
              onChange={(e) => { setForm({ ...form, includeOnResume: e.target.checked }); setDirty(true) }}
            />
            {' '}Include on Resume
          </label>
        </div>
        <div className="form-group">
          <label>Skills</label>
          <div className="checkbox-list">
            {skills.map((skill) => (
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
          <button type="button" onClick={() => navigate('/admin/jobs')}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
