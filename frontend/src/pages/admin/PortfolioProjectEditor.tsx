import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { MarkdownEditor } from '../../components/admin/MarkdownEditor'
import { ProjectSilhouette } from '../../components/portfolio/ProjectSilhouette'
import { clearPortfolioCache } from '../../hooks/usePortfolio'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import {
  AdminValidationError,
  createAdminPortfolioProject,
  fetchAdminPortfolioProject,
  updateAdminPortfolioProject,
  type AdminPortfolioProjectInput,
} from '../../services/adminApi'
import { STATUS_LABELS, type ProjectStatus } from '../../types/portfolio'

const STATUSES: ProjectStatus[] = ['COMING_SOON', 'IN_DEVELOPMENT', 'BETA', 'LIVE']

/** Mirrors `ProjectValidator` on the backend, which is what actually enforces them. */
const LIMITS = { slug: 60, name: 60, tagline: 140 } as const

const EMPTY: AdminPortfolioProjectInput = {
  slug: '',
  name: '',
  tagline: '',
  description: '',
  status: 'COMING_SOON',
  displayOrder: null,
  published: false,
  image: null,
  liveUrl: '',
  accentHue: 212,
}

/** A slug suggestion from the name, only while the slug has not been typed by hand. */
function slugify(name: string): string {
  return name.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, LIMITS.slug)
}

/**
 * Create or edit a portfolio project. Two-column top — name, slug and tagline beside the image —
 * as the blog editor lays out, with a live silhouette preview for the accent hue. Saving sends
 * every field; the server's field errors are shown beside their inputs, and a slug already in
 * use comes back as a conflict.
 */
export function PortfolioProjectEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [form, setForm] = useState<AdminPortfolioProjectInput>(EMPTY)
  const [slugTouched, setSlugTouched] = useState(!isNew)
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  const load = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const project = await fetchAdminPortfolioProject(getAccessToken, id)
      setForm({
        slug: project.slug,
        name: project.name,
        tagline: project.tagline,
        description: project.description ?? '',
        status: project.status,
        displayOrder: project.displayOrder,
        published: project.published,
        image: project.image,
        liveUrl: project.liveUrl ?? '',
        accentHue: project.accentHue,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load the project')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    void load()
  }, [load])

  const update = <K extends keyof AdminPortfolioProjectInput>(key: K, value: AdminPortfolioProjectInput[K]) => {
    setForm(current => ({ ...current, [key]: value }))
    setDirty(true)
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError(null)
    setFieldErrors({})
    try {
      if (isNew) {
        await createAdminPortfolioProject(getAccessToken, form)
      } else {
        await updateAdminPortfolioProject(getAccessToken, id!, form)
      }
      clearPortfolioCache()
      setDirty(false)
      navigate('/admin/portfolio')
    } catch (err) {
      if (err instanceof AdminValidationError) {
        setFieldErrors(Object.fromEntries(err.fieldErrors.map(fieldError => [fieldError.field, fieldError.message])))
        setError('Some fields need attention.')
      } else {
        setError(err instanceof Error ? err.message : 'Failed to save the project')
      }
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="admin-loading">Loading project...</div>
  }

  const fieldError = (field: string) => (fieldErrors[field]
    ? <p className="home-page-editor__error" role="alert">{fieldErrors[field]}</p>
    : null)

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">{isNew ? 'New project' : `Edit ${form.name}`}</h1>
      {error ? <div className="admin-error-banner">{error}</div> : null}

      <form className="blog-editor" noValidate onSubmit={handleSubmit}>
        <div className="blog-editor__top-row">
          <div className="blog-editor__top-left">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="project-name">Name</label>
              <input
                aria-invalid={fieldErrors.name ? true : undefined}
                className="admin-form__input"
                id="project-name"
                maxLength={LIMITS.name}
                onChange={event => {
                  update('name', event.target.value)
                  if (!slugTouched) update('slug', slugify(event.target.value))
                }}
                value={form.name}
              />
              {fieldError('name')}
            </div>
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="project-slug">Address</label>
              <input
                aria-describedby="project-slug-hint"
                aria-invalid={fieldErrors.slug ? true : undefined}
                className="admin-form__input"
                id="project-slug"
                maxLength={LIMITS.slug}
                onChange={event => {
                  setSlugTouched(true)
                  update('slug', event.target.value)
                }}
                value={form.slug}
              />
              <p className="admin-form__hint" id="project-slug-hint">
                /portfolio/{form.slug || 'your-project'} — lower-case words joined by hyphens.
              </p>
              {fieldError('slug')}
            </div>
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="project-tagline">Tagline</label>
              <input
                aria-invalid={fieldErrors.tagline ? true : undefined}
                className="admin-form__input"
                id="project-tagline"
                maxLength={LIMITS.tagline}
                onChange={event => update('tagline', event.target.value)}
                value={form.tagline}
              />
              {fieldError('tagline')}
            </div>
          </div>
          <div className="blog-editor__top-right">
            <div className="blog-editor__section">
              <span className="blog-editor__section-label">Image</span>
              <ImagePicker
                onChange={url => update('image', url ? { url } : null)}
                value={form.image?.url ?? null}
              />
              <p className="admin-form__hint">Not shown while the project is Coming soon.</p>
            </div>
          </div>
        </div>

        <div className="blog-editor__three-col">
          <div className="blog-editor__section">
            <label className="blog-editor__section-label" htmlFor="project-status">Status</label>
            <select
              className="admin-form__input"
              id="project-status"
              onChange={event => update('status', event.target.value as ProjectStatus)}
              value={form.status}
            >
              {STATUSES.map(status => <option key={status} value={status}>{STATUS_LABELS[status]}</option>)}
            </select>
          </div>
          <div className="blog-editor__section">
            <label className="blog-editor__section-label" htmlFor="project-live-url">Live link</label>
            <input
              aria-invalid={fieldErrors.liveUrl ? true : undefined}
              className="admin-form__input"
              id="project-live-url"
              onChange={event => update('liveUrl', event.target.value)}
              placeholder="https://"
              value={form.liveUrl ?? ''}
            />
            {fieldError('liveUrl')}
          </div>
          <div className="blog-editor__section">
            <label className="blog-editor__section-label" htmlFor="project-hue">
              Silhouette colour ({form.accentHue}°)
            </label>
            <div className="portfolio-editor__hue">
              <input
                id="project-hue"
                max={359}
                min={0}
                onChange={event => update('accentHue', Number(event.target.value))}
                type="range"
                value={form.accentHue}
              />
              <ProjectSilhouette hue={form.accentHue} size="sm" />
            </div>
          </div>
        </div>

        <div className="admin-form__field admin-form__field--checkbox">
          <label className="admin-form__label admin-form__label--checkbox">
            <input
              checked={form.published}
              onChange={event => update('published', event.target.checked)}
              type="checkbox"
            />
            Published
          </label>
        </div>

        <div className="blog-editor__section">
          <span className="blog-editor__section-label">Description</span>
          <MarkdownEditor
            onChange={value => update('description', value)}
            placeholder="What it is, who it is for, how it is built. Hidden while Coming soon."
            value={form.description ?? ''}
          />
          {fieldError('description')}
        </div>

        <div className="admin-form__actions">
          <button className="admin-btn" onClick={() => navigate('/admin/portfolio')} type="button">Cancel</button>
          <button className="admin-btn admin-btn--primary" disabled={saving} type="submit">
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </form>
    </div>
  )
}
