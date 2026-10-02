import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { MediaPicker } from '../../components/admin/MediaPicker'
import { HighlightsEditor, PagesEditor, TextField } from '../../components/admin/PortfolioPageFields'
import { RichMarkdownEditor } from '../../components/admin/RichMarkdownEditor'
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
import { STATUS_LABELS, type ProjectDemo, type ProjectStatus } from '../../types/portfolio'
import { besideField, chaptersToText, questionsToText, textToChapters, textToQuestions } from './portfolioEditorText'

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
  headline: '',
  summary: '',
  statement: null,
  exampleQuestions: [],
  highlights: [],
  demo: null,
  pages: [],
}

type DemoFields = Omit<ProjectDemo, 'chapters'>

/** Tall enough to show every line of a one-per-line list, plus one to type into. */
function rowsFor(text: string, minimum: number): number {
  return Math.max(minimum, text.split('\n').length + 1)
}

const EMPTY_DEMO: DemoFields ={ title: '', summary: '', videoUrl: '', captionsUrl: '', posterUrl: '' }

function isBlank(value: string | null | undefined): boolean {
  return !value || value.trim().length === 0
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
  // Edited as text and turned into lists only on save; see portfolioEditorText.
  const [questionsText, setQuestionsText] = useState('')
  const [chaptersText, setChaptersText] = useState('')
  const [demo, setDemo] = useState<DemoFields>(EMPTY_DEMO)

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
        headline: project.headline ?? '',
        summary: project.summary ?? '',
        statement: project.statement,
        exampleQuestions: project.exampleQuestions ?? [],
        highlights: project.highlights ?? [],
        demo: project.demo,
        pages: project.pages ?? [],
      })
      setQuestionsText(questionsToText(project.exampleQuestions ?? []))
      setChaptersText(chaptersToText(project.demo?.chapters ?? []))
      setDemo(project.demo
        ? {
            title: project.demo.title ?? '',
            summary: project.demo.summary ?? '',
            videoUrl: project.demo.videoUrl,
            captionsUrl: project.demo.captionsUrl ?? '',
            posterUrl: project.demo.posterUrl ?? '',
          }
        : EMPTY_DEMO)
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

  const statement = form.statement ?? { label: '', text: '', points: [] }
  const updateStatement = (next: typeof statement) => update('statement', next)
  const updateDemo = (next: DemoFields) => {
    setDemo(next)
    setDirty(true)
  }

  /** The form as the API takes it, or the name of the one field that cannot be read. */
  const toPayload = (): AdminPortfolioProjectInput | { invalid: string; message: string } => {
    const chapters = textToChapters(chaptersText)
    if (!chapters.ok) {
      return { invalid: 'demo.chapters', message: `Chapter line ${chapters.line} should look like "1:05 What happens".` }
    }
    const demoIsEmpty = Object.values(demo).every(value => isBlank(value)) && chapters.chapters.length === 0
    const statementIsEmpty = isBlank(statement.label) && isBlank(statement.text) && statement.points.length === 0
    return {
      ...form,
      statement: statementIsEmpty ? null : statement,
      exampleQuestions: textToQuestions(questionsText),
      demo: demoIsEmpty ? null : { ...demo, chapters: chapters.chapters },
    }
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    const payload = toPayload()
    if ('invalid' in payload) {
      setFieldErrors({ [payload.invalid]: payload.message })
      setError('Some fields need attention.')
      return
    }
    setSaving(true)
    try {
      if (isNew) {
        await createAdminPortfolioProject(getAccessToken, payload)
      } else {
        await updateAdminPortfolioProject(getAccessToken, id!, payload)
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
    ? <p className="home-page-editor__error" role="alert">{besideField(field, fieldErrors[field])}</p>
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
          <RichMarkdownEditor
            label="Description"
            markdown={form.description ?? ''}
            onChange={value => update('description', value)}
            placeholder="What it is, who it is for, how it is built. Hidden while Coming soon."
          />
          {fieldError('description')}
        </div>

        <h2 className="portfolio-editor__heading">Project page</h2>
        <p className="admin-form__hint">
          Everything below is optional, and hidden while the project is Coming soon. A section with
          nothing in it is left off the page.
        </p>

        <div className="blog-editor__section">
          <TextField
            error={fieldError('headline')}
            hint="Shown instead of the name. A second line is shown in the project's colour."
            id="project-headline" label="Headline" multiline onChange={value => update('headline', value)}
            rows={2} value={form.headline} />
          <TextField
            error={fieldError('summary')} hint="Shown under the headline instead of the tagline."
            id="project-summary" label="Summary" multiline onChange={value => update('summary', value)}
            value={form.summary} />
        </div>

        <fieldset className="portfolio-editor__group">
          <legend>Statement</legend>
          <TextField error={fieldError('statement.label')} id="statement-label" label="Label"
            onChange={label => updateStatement({ ...statement, label })} placeholder="Why I built it"
            value={statement.label} />
          <TextField error={fieldError('statement.text')} id="statement-text" label="Statement" multiline
            onChange={text => updateStatement({ ...statement, text })} value={statement.text} />
          <HighlightsEditor
            field="statement.points" fieldError={fieldError} items={statement.points} max={6} noun="Point"
            onChange={points => updateStatement({ ...statement, points })} withImages={false} />
        </fieldset>

        <fieldset className="portfolio-editor__group">
          <legend>Example questions</legend>
          <TextField
            error={fieldError('exampleQuestions')}
            hint="One per line. With a live link, each opens the project with the question already asked."
            id="project-questions" label="Questions" multiline
            onChange={value => {
              setQuestionsText(value)
              setDirty(true)
            }}
            rows={rowsFor(questionsText, 6)} value={questionsText} />
        </fieldset>

        <fieldset className="portfolio-editor__group">
          <legend>Highlights</legend>
          <HighlightsEditor
            field="highlights" fieldError={fieldError} items={form.highlights} max={6} noun="Highlight"
            onChange={highlights => update('highlights', highlights)} withImages />
        </fieldset>

        <fieldset className="portfolio-editor__group">
          <legend>Demo video</legend>
          <div className="blog-editor__three-col">
            <div className="blog-editor__section">
              <span className="blog-editor__section-label">Video</span>
              <MediaPicker kind="video" label="Demo video"
                onChange={videoUrl => updateDemo({ ...demo, videoUrl })} value={demo.videoUrl || null} />
              {fieldError('demo.videoUrl')}
            </div>
            <div className="blog-editor__section">
              <span className="blog-editor__section-label">Captions</span>
              <MediaPicker kind="captions" label="Demo captions"
                onChange={captionsUrl => updateDemo({ ...demo, captionsUrl })} value={demo.captionsUrl || null} />
              <p className="admin-form__hint">Offered on the player, off until a visitor turns them on.</p>
              {fieldError('demo.captionsUrl')}
            </div>
            <div className="blog-editor__section">
              <span className="blog-editor__section-label">Poster</span>
              <MediaPicker kind="image" label="Demo poster"
                onChange={posterUrl => updateDemo({ ...demo, posterUrl })} value={demo.posterUrl || null} />
              {fieldError('demo.posterUrl')}
            </div>
          </div>
          <TextField error={fieldError('demo.title')} id="demo-title" label="Demo title"
            onChange={title => updateDemo({ ...demo, title })} value={demo.title} />
          <TextField error={fieldError('demo.summary')} id="demo-summary" label="Demo summary" multiline
            onChange={summary => updateDemo({ ...demo, summary })} value={demo.summary} />
          <TextField
            error={fieldError('demo.chapters')} hint='One per line, as "1:05 What happens".'
            id="demo-chapters" label="Chapters" monospace multiline
            onChange={value => {
              setChaptersText(value)
              setDirty(true)
            }}
            rows={rowsFor(chaptersText, 5)} value={chaptersText} />
        </fieldset>

        <fieldset className="portfolio-editor__group">
          <legend>Sub-pages</legend>
          <PagesEditor
            fieldError={fieldError} items={form.pages} max={6} onChange={pages => update('pages', pages)}
            projectSlug={form.slug} />
        </fieldset>

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
