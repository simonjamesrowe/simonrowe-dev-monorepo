import { Plus, Trash2 } from 'lucide-react'
import { useState, type ReactNode } from 'react'

import type { ProjectHighlight, ProjectPage } from '../../types/portfolio'
import { MediaPicker } from './MediaPicker'
import { RichMarkdownEditor } from './RichMarkdownEditor'

/** Shows the server's message for a field, which is named the way the API names it. */
export type FieldErrorFor = (field: string) => ReactNode

interface TextFieldProps {
  id: string
  label: string
  value: string | null | undefined
  onChange: (value: string) => void
  error?: ReactNode
  hint?: string
  multiline?: boolean
  rows?: number
  placeholder?: string
  monospace?: boolean
}

/** One labelled input or textarea in the editor's own style. */
export function TextField({
  id, label, value, onChange, error, hint, multiline, rows = 3, placeholder, monospace,
}: TextFieldProps) {
  const common = {
    'aria-describedby': hint ? `${id}-hint` : undefined,
    'aria-invalid': error ? true : undefined,
    className: `admin-form__input${monospace ? ' portfolio-editor__code' : ''}`,
    id,
    onChange: (event: { target: { value: string } }) => onChange(event.target.value),
    placeholder,
    value: value ?? '',
  }
  return (
    <div className="blog-editor__section">
      <label className="blog-editor__section-label" htmlFor={id}>{label}</label>
      {multiline ? <textarea {...common} rows={rows} /> : <input {...common} />}
      {hint ? <p className="admin-form__hint" id={`${id}-hint`}>{hint}</p> : null}
      {error}
    </div>
  )
}

interface ListEditorProps<T> {
  items: T[]
  onChange: (items: T[]) => void
  empty: () => T
  noun: string
  max: number
  render: (item: T, update: (next: T) => void, index: number) => ReactNode
}

let nextItemId = 0
const newItemId = () => `item-${nextItemId++}`

/**
 * A repeatable group of fields: each item in its own fieldset, with add and remove. Items are
 * keyed by an id that follows the item, not by position, because a rich editor reads its text
 * only when it mounts: keyed by index, removing the first page would leave the second page's
 * slot showing the first page's body.
 */
function ListEditor<T>({ items, onChange, empty, noun, max, render }: ListEditorProps<T>) {
  const [ids, setIds] = useState(() => items.map(newItemId))
  const remove = (index: number) => {
    setIds(current => current.filter((_, i) => i !== index))
    onChange(items.filter((_, i) => i !== index))
  }
  const add = () => {
    setIds(current => [...current, newItemId()])
    onChange([...items, empty()])
  }
  return (
    <div className="portfolio-editor__list">
      {items.map((item, index) => (
        <fieldset className="portfolio-editor__item" key={ids[index] ?? index}>
          <legend>{noun} {index + 1}</legend>
          {render(item, next => onChange(items.map((current, i) => (i === index ? next : current))), index)}
          <button
            className="admin-btn portfolio-editor__remove"
            onClick={() => remove(index)}
            type="button"
          >
            <Trash2 aria-hidden="true" size={14} /> Remove {noun.toLowerCase()} {index + 1}
          </button>
        </fieldset>
      ))}
      {items.length < max ? (
        <button className="admin-btn" onClick={add} type="button">
          <Plus aria-hidden="true" size={14} /> Add {noun.toLowerCase()}
        </button>
      ) : null}
    </div>
  )
}

interface HighlightsEditorProps {
  field: string
  items: ProjectHighlight[]
  onChange: (items: ProjectHighlight[]) => void
  fieldError: FieldErrorFor
  noun: string
  max: number
  withImages: boolean
}

export function HighlightsEditor({ field, items, onChange, fieldError, noun, max, withImages }: HighlightsEditorProps) {
  return (
    <ListEditor
      empty={() => ({ title: '', text: '', imageUrl: '', imageAlt: '' })}
      items={items}
      max={max}
      noun={noun}
      onChange={onChange}
      render={(item, update, index) => {
        const path = `${field}[${index}]`
        const id = `${field.replace('.', '-')}-${index}`
        return (
          <>
            <TextField error={fieldError(`${path}.title`)} id={`${id}-title`} label="Title"
              onChange={title => update({ ...item, title })} value={item.title} />
            <TextField error={fieldError(`${path}.text`)} id={`${id}-text`} label="Text" multiline rows={2}
              onChange={text => update({ ...item, text })} value={item.text} />
            {withImages ? (
              <>
                <div className="blog-editor__section">
                  <span className="blog-editor__section-label">Image</span>
                  <MediaPicker
                    kind="image" label={`${noun} ${index + 1} image`}
                    onChange={imageUrl => update({ ...item, imageUrl })} value={item.imageUrl ?? null} />
                  {fieldError(`${path}.imageUrl`)}
                </div>
                <TextField error={fieldError(`${path}.imageAlt`)} id={`${id}-alt`} label="Image description"
                  onChange={imageAlt => update({ ...item, imageAlt })} value={item.imageAlt} />
              </>
            ) : null}
          </>
        )
      }}
    />
  )
}

interface PagesEditorProps {
  items: ProjectPage[]
  onChange: (items: ProjectPage[]) => void
  fieldError: FieldErrorFor
  projectSlug: string
  max: number
}

/** Sub-pages, each with its body in the same rich markdown editor as the rest of the admin. */
export function PagesEditor({ items, onChange, fieldError, projectSlug, max }: PagesEditorProps) {
  return (
    <ListEditor
      empty={() => ({ slug: '', title: '', navHint: '', summary: '', body: '' })}
      items={items}
      max={max}
      noun="Page"
      onChange={onChange}
      render={(item, update, index) => {
        const path = `pages[${index}]`
        const id = `page-${index}`
        return (
          <>
            <div className="blog-editor__three-col">
              <TextField error={fieldError(`${path}.title`)} id={`${id}-title`} label="Tab title"
                onChange={title => update({ ...item, title })} value={item.title} />
              <TextField error={fieldError(`${path}.slug`)} id={`${id}-slug`} label="Page address"
                hint={`/portfolio/${projectSlug || 'project'}/${item.slug || 'page'}`}
                onChange={slug => update({ ...item, slug })} value={item.slug} />
              <TextField error={fieldError(`${path}.navHint`)} id={`${id}-hint`} label="Tab hint"
                onChange={navHint => update({ ...item, navHint })} value={item.navHint} />
            </div>
            <TextField error={fieldError(`${path}.summary`)} id={`${id}-summary`} label="Teaser"
              hint="Shown on the overview's link to this page."
              onChange={summary => update({ ...item, summary })} value={item.summary} />
            <div className="blog-editor__section">
              <span className="blog-editor__section-label">Body</span>
              <RichMarkdownEditor
                label={`Page ${index + 1} body`} markdown={item.body ?? ''}
                onChange={body => update({ ...item, body })} />
              {fieldError(`${path}.body`)}
            </div>
          </>
        )
      }}
    />
  )
}
