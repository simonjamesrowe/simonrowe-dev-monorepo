import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { TextField } from '../../components/admin/PortfolioPageFields'
import { RichMarkdownEditor } from '../../components/admin/RichMarkdownEditor'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import {
  AdminValidationError,
  createAdminBook,
  fetchAdminBook,
  fetchAdminBooks,
  toAdminBookInput,
  updateAdminBook,
  type AdminBookInput,
} from '../../services/adminApi'
import { besideField } from './portfolioEditorText'

/** Mirrors `BookValidator` on the backend, which is what actually enforces them. */
const LIMITS = { slug: 80, title: 160, subtitle: 200, category: 60, summary: 500 } as const

const EMPTY: AdminBookInput = {
  slug: '',
  title: '',
  subtitle: '',
  authors: [],
  publishedYear: null,
  category: '',
  summary: '',
  description: '',
  note: '',
  coverImage: null,
  purchaseUrl: '',
  sourceLabel: '',
  sourceUrl: '',
  displayOrder: null,
  published: false,
}

/** A slug suggestion from the title, only while the slug has not been typed by hand. */
function slugify(title: string): string {
  return title.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, LIMITS.slug)
}

/** One author per line; blank lines are dropped, and surrounding spaces trimmed on the server. */
function textToAuthors(text: string): string[] {
  return text.split('\n').map(line => line.trim()).filter(line => line.length > 0)
}

/**
 * Create or edit a book on the Reading list. Title, address, authors and category beside the
 * cover, as the portfolio editor lays out; then the card summary, the drawer's note and longer
 * description, and the links. Saving sends every field, and the server's field errors are shown
 * beside their inputs.
 */
export function BookEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const [form, setForm] = useState<AdminBookInput>(EMPTY)
  // Edited as text and turned into a list only on save, so typing a new line is not undone.
  const [authorsText, setAuthorsText] = useState('')
  const [yearText, setYearText] = useState('')
  const [categories, setCategories] = useState<string[]>([])
  const [slugTouched, setSlugTouched] = useState(!isNew)
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  const load = useCallback(async () => {
    try {
      const all = await fetchAdminBooks(getAccessToken)
      setCategories([...new Set(all.map(book => book.category))])
      if (isNew || !id) return
      setLoading(true)
      const book = await fetchAdminBook(getAccessToken, id)
      setForm(toAdminBookInput(book))
      setAuthorsText(book.authors.join('\n'))
      setYearText(book.publishedYear === null ? '' : String(book.publishedYear))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load the book')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    void load()
  }, [load])

  const update = <K extends keyof AdminBookInput>(key: K, value: AdminBookInput[K]) => {
    setForm(current => ({ ...current, [key]: value }))
    setDirty(true)
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    const year = yearText.trim()
    if (year !== '' && !/^\d{1,4}$/.test(year)) {
      setFieldErrors({ publishedYear: 'publishedYear must be a year, such as 2004' })
      setError('Some fields need attention.')
      return
    }
    const payload: AdminBookInput = {
      ...form,
      authors: textToAuthors(authorsText),
      publishedYear: year === '' ? null : Number(year),
    }
    setSaving(true)
    try {
      if (isNew) {
        await createAdminBook(getAccessToken, payload)
      } else {
        await updateAdminBook(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/books')
    } catch (err) {
      if (err instanceof AdminValidationError) {
        setFieldErrors(Object.fromEntries(err.fieldErrors.map(fieldError => [fieldError.field, fieldError.message])))
        setError('Some fields need attention.')
      } else {
        setError(err instanceof Error ? err.message : 'Failed to save the book')
      }
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="admin-loading">Loading book...</div>
  }

  const fieldError = (field: string) => {
    // A message for any one author is shown under the authors box.
    const key = field === 'authors'
      ? Object.keys(fieldErrors).find(name => name === 'authors' || name.startsWith('authors['))
      : field
    return key && fieldErrors[key]
      ? <p className="home-page-editor__error" role="alert">{besideField(key, fieldErrors[key])}</p>
      : null
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">{isNew ? 'New book' : `Edit ${form.title}`}</h1>
      {error ? <div className="admin-error-banner">{error}</div> : null}

      <form className="blog-editor" noValidate onSubmit={handleSubmit}>
        <div className="blog-editor__top-row">
          <div className="blog-editor__top-left">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="book-title">Title</label>
              <input
                aria-invalid={fieldErrors.title ? true : undefined}
                className="admin-form__input"
                id="book-title"
                maxLength={LIMITS.title}
                onChange={event => {
                  update('title', event.target.value)
                  if (!slugTouched) update('slug', slugify(event.target.value))
                }}
                value={form.title}
              />
              {fieldError('title')}
            </div>
            <TextField
              error={fieldError('subtitle')} id="book-subtitle" label="Subtitle"
              onChange={value => update('subtitle', value)} value={form.subtitle} />
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="book-slug">Address</label>
              <input
                aria-describedby="book-slug-hint"
                aria-invalid={fieldErrors.slug ? true : undefined}
                className="admin-form__input"
                id="book-slug"
                maxLength={LIMITS.slug}
                onChange={event => {
                  setSlugTouched(true)
                  update('slug', event.target.value)
                }}
                value={form.slug}
              />
              <p className="admin-form__hint" id="book-slug-hint">
                /reading?book={form.slug || 'your-book'} — lower-case words joined by hyphens.
              </p>
              {fieldError('slug')}
            </div>
            <TextField
              error={fieldError('authors')} hint="One per line." id="book-authors" label="Authors" multiline
              onChange={value => {
                setAuthorsText(value)
                setDirty(true)
              }}
              rows={Math.max(2, authorsText.split('\n').length + 1)} value={authorsText} />
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="book-category">Category</label>
              <input
                aria-describedby="book-category-hint"
                aria-invalid={fieldErrors.category ? true : undefined}
                className="admin-form__input"
                id="book-category"
                list="book-categories"
                maxLength={LIMITS.category}
                onChange={event => update('category', event.target.value)}
                value={form.category}
              />
              <datalist id="book-categories">
                {categories.map(category => <option key={category} value={category} />)}
              </datalist>
              <p className="admin-form__hint" id="book-category-hint">
                The shelf it sits on. Pick an existing one to keep books together.
              </p>
              {fieldError('category')}
            </div>
          </div>
          <div className="blog-editor__top-right">
            <div className="blog-editor__section">
              <span className="blog-editor__section-label">Cover</span>
              <ImagePicker
                onChange={url => update('coverImage', url ? { url } : null)}
                value={form.coverImage?.url ?? null}
              />
              {fieldError('coverImage')}
            </div>
            <TextField
              error={fieldError('publishedYear')} hint="The year the work first appeared."
              id="book-year" label="First published" onChange={value => {
                setYearText(value)
                setDirty(true)
              }}
              placeholder="2004" value={yearText} />
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

        <TextField
          error={fieldError('summary')} hint={`On the card. At most ${LIMITS.summary} characters.`}
          id="book-summary" label="Summary" multiline onChange={value => update('summary', value)}
          value={form.summary} />
        <TextField
          error={fieldError('note')} hint="Shown in the drawer as “Why it’s on my list”. Markdown."
          id="book-note" label="Why it's on my list" multiline onChange={value => update('note', value)}
          rows={4} value={form.note} />

        <div className="blog-editor__section">
          <span className="blog-editor__section-label">Description</span>
          <RichMarkdownEditor
            label="Description"
            markdown={form.description ?? ''}
            onChange={value => update('description', value)}
            placeholder="What the book covers and its key ideas. Shown in the drawer."
          />
          {fieldError('description')}
        </div>

        <div className="blog-editor__three-col">
          <TextField
            error={fieldError('purchaseUrl')} hint="Where to buy it, such as its Amazon page."
            id="book-purchase-url" label="Buy link" onChange={value => update('purchaseUrl', value)}
            placeholder="https://www.amazon.co.uk/dp/…" value={form.purchaseUrl} />
          <TextField
            error={fieldError('sourceLabel')} hint="Who recommended it, shown as “Found via …”."
            id="book-source-label" label="Found via" onChange={value => update('sourceLabel', value)}
            value={form.sourceLabel} />
          <TextField
            error={fieldError('sourceUrl')} id="book-source-url" label="Found via link"
            onChange={value => update('sourceUrl', value)} placeholder="https://" value={form.sourceUrl} />
        </div>

        <div className="admin-form__actions">
          <button className="admin-btn" onClick={() => navigate('/admin/books')} type="button">Cancel</button>
          <button className="admin-btn admin-btn--primary" disabled={saving} type="submit">
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </form>
    </div>
  )
}
