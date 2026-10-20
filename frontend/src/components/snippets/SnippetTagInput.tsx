import { useId, useState } from 'react'
import { X } from 'lucide-react'

import type { TagCount } from '../../types/snippets'
import { normaliseTag } from './snippetCitation'

interface SnippetTagInputProps {
  tags: string[]
  onChange: (tags: string[]) => void
  /** Tags already in use, most used first, offered as one-tap suggestions. */
  suggestions: TagCount[]
  label?: string
}

const MAX_SUGGESTIONS = 6

/**
 * Free-text tags: Enter or a comma adds one, Backspace in an empty field removes the last.
 * The admin `TagInput` only picks from a fixed list, and a snippet tag is usually new.
 */
export function SnippetTagInput({ tags, onChange, suggestions, label = 'Tags' }: SnippetTagInputProps) {
  const [draft, setDraft] = useState('')
  const inputId = useId()

  const add = (raw: string) => {
    const tag = normaliseTag(raw)
    if (tag && !tags.includes(tag)) onChange([...tags, tag])
    setDraft('')
  }

  const offered = suggestions
    .filter(suggestion => !tags.includes(suggestion.tag))
    .filter(suggestion => !draft || suggestion.tag.includes(normaliseTag(draft)))
    .slice(0, MAX_SUGGESTIONS)

  return (
    <div className="snippet-tags">
      <label className="snippet-tags__label" htmlFor={inputId}>{label}</label>
      <div className="snippet-tags__box">
        {tags.map(tag => (
          <span className="snippet-tag snippet-tag--chosen" key={tag}>
            #{tag}
            <button
              aria-label={`Remove tag ${tag}`}
              className="snippet-tag__remove"
              onClick={() => onChange(tags.filter(existing => existing !== tag))}
              type="button"
            >
              <X aria-hidden="true" size={12} />
            </button>
          </span>
        ))}
        <input
          className="snippet-tags__input"
          id={inputId}
          onBlur={() => { if (draft.trim()) add(draft) }}
          onChange={event => {
            const value = event.target.value
            if (value.endsWith(',')) add(value.slice(0, -1))
            else setDraft(value)
          }}
          onKeyDown={event => {
            if (event.key === 'Enter' && draft.trim()) {
              event.preventDefault()
              add(draft)
            } else if (event.key === 'Backspace' && !draft && tags.length > 0) {
              onChange(tags.slice(0, -1))
            }
          }}
          placeholder={tags.length === 0 ? 'Add a tag, press Enter' : ''}
          value={draft}
        />
      </div>
      {offered.length > 0 && (
        <div className="snippet-tags__suggestions">
          {offered.map(suggestion => (
            <button
              className="snippet-tags__suggestion"
              key={suggestion.tag}
              // Keeps focus in the field, so its blur does not add a half-typed tag first.
              onMouseDown={event => event.preventDefault()}
              onClick={() => add(suggestion.tag)}
              type="button"
            >
              + {suggestion.tag} <span className="snippet-tags__count">{suggestion.count}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
