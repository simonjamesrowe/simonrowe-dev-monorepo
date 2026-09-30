import { ExternalLink } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import { API_BASE_URL } from '../../config/api'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import {
  AdminValidationError,
  fetchAdminHomePage,
  fetchAdminProfile,
  updateAdminHomePage,
  type AdminProfile,
} from '../../services/adminApi'
import type { HomePageContent } from '../../types/homePage'

/** Mirrors `HomePageValidator` on the backend, which is what actually enforces them. */
const LIMITS = {
  headline: 60,
  lede: 240,
  ctaLabel: 32,
  tourLabel: 32,
  pillLead: 40,
  pillLabel: 40,
  pillButton: 20,
} as const

const LINK_HINT = 'A site path such as /about#roles, or a full https:// address.'

interface FieldProps {
  id: string
  label: string
  value: string
  max?: number
  required?: boolean
  multiline?: boolean
  hint?: string
  error?: string
  onChange: (value: string) => void
}

function Field({ id, label, value, max, required, multiline, hint, error, onChange }: FieldProps) {
  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null]
    .filter(Boolean).join(' ') || undefined
  const props = {
    'aria-describedby': describedBy,
    'aria-invalid': error ? true : undefined,
    className: `admin-form__input${error ? ' admin-form__input--invalid' : ''}`,
    id,
    maxLength: max,
    onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(event.target.value),
    required,
    value,
  }
  return (
    <div className="blog-editor__section">
      <label className="blog-editor__section-label home-page-editor__label" htmlFor={id}>
        <span>{label}{required ? null : <span className="home-page-editor__optional"> (optional)</span>}</span>
        {max ? <span className="home-page-editor__counter">{value.length} / {max}</span> : null}
      </label>
      {multiline ? <textarea rows={3} {...props} /> : <input type="text" {...props} />}
      {hint ? <p className="admin-form__hint" id={`${id}-hint`}>{hint}</p> : null}
      {error ? <p className="home-page-editor__error" id={`${id}-error`} role="alert">{error}</p> : null}
    </div>
  )
}

/**
 * The Home page editor: the landing hero's statement, sentence, calls to action, tour link
 * and Ask pill wording. The name, title, location and background images the hero also shows
 * are the profile's, and are shown here read-only with a link to where they are edited.
 *
 * Saving sends every field back, so nothing is dropped by omission; the server's field errors
 * are shown beside their inputs.
 */
export function HomePageAdmin() {
  const { getAccessToken } = useAuth()
  const [form, setForm] = useState<HomePageContent | null>(null)
  const [profile, setProfile] = useState<AdminProfile | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  const load = useCallback(async () => {
    setLoadError(null)
    try {
      const [content, adminProfile] = await Promise.all([
        fetchAdminHomePage(getAccessToken),
        // Only for the read-only image strip, so its failure must not block editing.
        fetchAdminProfile(getAccessToken).catch(() => null),
      ])
      setForm(content)
      setProfile(adminProfile)
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'Failed to load the home page')
    }
  }, [getAccessToken])

  useEffect(() => {
    void load()
  }, [load])

  const update = (patch: (current: HomePageContent) => HomePageContent) => {
    setForm(current => (current ? patch(current) : current))
    setDirty(true)
    setSaved(false)
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!form) return
    setSaving(true)
    setSaveError(null)
    setFieldErrors({})
    try {
      const result = await updateAdminHomePage(getAccessToken, form)
      setForm(result)
      setDirty(false)
      setSaved(true)
    } catch (err) {
      if (err instanceof AdminValidationError) {
        setFieldErrors(Object.fromEntries(err.fieldErrors.map(error => [error.field, error.message])))
        setSaveError('Some fields need attention.')
      } else {
        setSaveError(err instanceof Error ? err.message : 'Failed to save the home page')
      }
    } finally {
      setSaving(false)
    }
  }

  if (loadError) {
    return (
      <div className="admin-error">
        <p>{loadError}</p>
        <button className="admin-btn" onClick={() => void load()} type="button">Retry</button>
      </div>
    )
  }

  if (!form) {
    return <div className="admin-loading">Loading home page...</div>
  }

  const secondary = form.secondaryCta ?? { label: '', href: '' }

  return (
    <div className="admin-page home-page-editor">
      <div className="home-page-editor__heading">
        <h1 className="admin-page__title">Home page</h1>
        <a className="admin-btn" href="/" rel="noopener noreferrer" target="_blank">
          <ExternalLink aria-hidden="true" size={16} /> View the home page
        </a>
      </div>
      {form.updatedAt === null ? (
        <p className="admin-form__hint">
          Showing the built-in default copy. Nothing has been saved yet.
        </p>
      ) : null}

      {saveError ? <div className="admin-error-banner">{saveError}</div> : null}
      {saved ? <div className="admin-success-banner">Home page saved.</div> : null}

      <form className="blog-editor" noValidate onSubmit={handleSubmit}>
        <section className="admin-section">
          <h2 className="admin-section__title">Statement</h2>
          <div className="blog-editor__two-col">
            <Field
              error={fieldErrors.headlineLine1}
              id="headlineLine1"
              label="Headline, first line"
              max={LIMITS.headline}
              onChange={value => update(current => ({ ...current, headlineLine1: value }))}
              required
              value={form.headlineLine1}
            />
            <Field
              error={fieldErrors.headlineLine2}
              hint="Shown in the accent colour."
              id="headlineLine2"
              label="Headline, second line"
              max={LIMITS.headline}
              onChange={value => update(current => ({ ...current, headlineLine2: value }))}
              required
              value={form.headlineLine2}
            />
          </div>
          <Field
            error={fieldErrors.lede}
            hint="Hidden on phones, where the photograph needs the room."
            id="lede"
            label="Supporting sentence"
            max={LIMITS.lede}
            multiline
            onChange={value => update(current => ({ ...current, lede: value }))}
            value={form.lede ?? ''}
          />
        </section>

        <section className="admin-section">
          <h2 className="admin-section__title">Calls to action</h2>
          <div className="blog-editor__two-col">
            <Field
              error={fieldErrors['primaryCta.label']}
              id="primaryCtaLabel"
              label="Primary button"
              max={LIMITS.ctaLabel}
              onChange={value => update(current => ({
                ...current, primaryCta: { ...current.primaryCta, label: value },
              }))}
              required
              value={form.primaryCta.label}
            />
            <Field
              error={fieldErrors['primaryCta.href']}
              hint={LINK_HINT}
              id="primaryCtaHref"
              label="Primary button link"
              onChange={value => update(current => ({
                ...current, primaryCta: { ...current.primaryCta, href: value },
              }))}
              required
              value={form.primaryCta.href}
            />
            <Field
              error={fieldErrors['secondaryCta.label']}
              hint="Leave empty to show only the primary button."
              id="secondaryCtaLabel"
              label="Secondary link"
              max={LIMITS.ctaLabel}
              onChange={value => update(current => ({
                ...current, secondaryCta: { ...secondary, label: value },
              }))}
              value={secondary.label}
            />
            <Field
              error={fieldErrors['secondaryCta.href']}
              hint={LINK_HINT}
              id="secondaryCtaHref"
              label="Secondary link target"
              onChange={value => update(current => ({
                ...current, secondaryCta: { ...secondary, href: value },
              }))}
              value={secondary.href}
            />
          </div>
          <div className="admin-form__field admin-form__field--checkbox">
            <label className="admin-form__label admin-form__label--checkbox">
              <input
                checked={form.showTourLink}
                onChange={event => update(current => ({ ...current, showTourLink: event.target.checked }))}
                type="checkbox"
              />
              Show the tour link (desktop only)
            </label>
          </div>
          {form.showTourLink ? (
            <Field
              error={fieldErrors.tourLinkLabel}
              id="tourLinkLabel"
              label="Tour link"
              max={LIMITS.tourLabel}
              onChange={value => update(current => ({ ...current, tourLinkLabel: value }))}
              required
              value={form.tourLinkLabel ?? ''}
            />
          ) : null}
        </section>

        <section className="admin-section">
          <h2 className="admin-section__title">Ask pill</h2>
          <div className="blog-editor__three-col">
            <Field
              error={fieldErrors['askPill.lead']}
              hint="Desktop only."
              id="askPillLead"
              label="Lead-in"
              max={LIMITS.pillLead}
              onChange={value => update(current => ({ ...current, askPill: { ...current.askPill, lead: value } }))}
              value={form.askPill.lead ?? ''}
            />
            <Field
              error={fieldErrors['askPill.label']}
              id="askPillLabel"
              label="Label"
              max={LIMITS.pillLabel}
              onChange={value => update(current => ({ ...current, askPill: { ...current.askPill, label: value } }))}
              required
              value={form.askPill.label}
            />
            <Field
              error={fieldErrors['askPill.buttonLabel']}
              id="askPillButton"
              label="Button"
              max={LIMITS.pillButton}
              onChange={value => update(current => ({
                ...current, askPill: { ...current.askPill, buttonLabel: value },
              }))}
              required
              value={form.askPill.buttonLabel}
            />
          </div>
        </section>

        <section className="admin-section">
          <h2 className="admin-section__title">From the profile</h2>
          <p className="admin-form__hint">
            The name, title and location above the statement, and both background photographs, are
            edited on the <Link to="/admin/profile">Profile</Link> page.
          </p>
          <div className="home-page-editor__images">
            {[
              { label: 'Desktop background', url: profile?.backgroundImage?.url },
              { label: 'Phone background', url: profile?.mobileBackgroundImage?.url },
            ].map(image => (
              <figure className="home-page-editor__image" key={image.label}>
                {image.url
                  ? <img alt="" src={`${API_BASE_URL}${image.url}`} />
                  : <span className="home-page-editor__image-empty">None set</span>}
                <figcaption>{image.label}</figcaption>
              </figure>
            ))}
          </div>
        </section>

        <div className="admin-form__actions">
          <button className="admin-btn admin-btn--primary" disabled={saving || !dirty} type="submit">
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </form>
    </div>
  )
}
