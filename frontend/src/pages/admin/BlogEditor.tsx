import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import {
  createAdminBlog,
  fetchAdminBlogById,
  fetchAdminSkills,
  fetchAdminTags,
  updateAdminBlog,
  type AdminSkill,
  type AdminTag,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'

interface BlogFormState {
  title: string
  shortDescription: string
  content: string
  published: boolean
  featuredImage: string
  tags: string[]
  skills: string[]
}

const emptyForm: BlogFormState = {
  title: '',
  shortDescription: '',
  content: '',
  published: false,
  featuredImage: '',
  tags: [],
  skills: [],
}

function toggleArrayItem(arr: string[], item: string): string[] {
  return arr.includes(item) ? arr.filter((i) => i !== item) : [...arr, item]
}

export function BlogEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tags, setTags] = useState<AdminTag[]>([])
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<BlogFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  useEffect(() => {
    const loadOptions = async () => {
      try {
        const [tagList, skillPage] = await Promise.all([
          fetchAdminTags(getAccessToken),
          fetchAdminSkills(getAccessToken, 0, 100),
        ])
        setTags(tagList)
        setSkills(skillPage.content)
      } catch {
        // non-fatal: options just won't appear
      }
    }
    loadOptions()
  }, [getAccessToken])

  const loadBlog = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const blog = await fetchAdminBlogById(getAccessToken, id)
      setForm({
        title: blog.title,
        shortDescription: blog.shortDescription,
        content: blog.content ?? '',
        published: blog.published,
        featuredImage: blog.featuredImage ?? '',
        tags: blog.tags ?? [],
        skills: blog.skills ?? [],
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load blog')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadBlog()
  }, [loadBlog])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const payload = {
        title: form.title,
        shortDescription: form.shortDescription,
        content: form.content,
        published: form.published,
        featuredImage: form.featuredImage,
        tags: form.tags,
        skills: form.skills,
      }
      if (isNew) {
        await createAdminBlog(getAccessToken, payload)
      } else {
        await updateAdminBlog(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/blogs')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div>Loading...</div>

  return (
    <div>
      <h1>{isNew ? 'New Blog' : 'Edit Blog'}</h1>
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
          <label>Short Description</label>
          <textarea
            value={form.shortDescription}
            onChange={(e) => { setForm({ ...form, shortDescription: e.target.value }); setDirty(true) }}
            required
            rows={3}
          />
        </div>
        <div className="form-group">
          <label>Content (Markdown)</label>
          <textarea
            value={form.content}
            onChange={(e) => { setForm({ ...form, content: e.target.value }); setDirty(true) }}
            rows={20}
          />
        </div>
        <div className="form-group">
          <label>Featured Image URL</label>
          <input
            type="text"
            value={form.featuredImage}
            onChange={(e) => { setForm({ ...form, featuredImage: e.target.value }); setDirty(true) }}
          />
        </div>
        <div className="form-group">
          <label>
            <input
              type="checkbox"
              checked={form.published}
              onChange={(e) => { setForm({ ...form, published: e.target.checked }); setDirty(true) }}
            />
            {' '}Published
          </label>
        </div>
        <div className="form-group">
          <label>Tags</label>
          <div className="checkbox-list">
            {tags.map((tag) => (
              <label key={tag.id}>
                <input
                  type="checkbox"
                  checked={form.tags.includes(tag.id)}
                  onChange={() => { setForm({ ...form, tags: toggleArrayItem(form.tags, tag.id) }); setDirty(true) }}
                />
                {' '}{tag.name}
              </label>
            ))}
          </div>
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
          <button type="button" onClick={() => navigate('/admin/blogs')}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
