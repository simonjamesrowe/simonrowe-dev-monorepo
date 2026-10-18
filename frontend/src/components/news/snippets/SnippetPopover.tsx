import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, Copy, Trash2 } from 'lucide-react'

import type { Snippet, TagCount } from '../../../types/snippets'
import { SnippetTagInput } from './SnippetTagInput'

export interface SnippetAnchor {
  left: number
  top: number
  bottom: number
}

interface SnippetPopoverProps {
  snippet: Snippet
  anchor: SnippetAnchor
  suggestions: TagCount[]
  onSave: (change: { note: string; tags: string[] }) => Promise<void>
  onDelete: () => Promise<void>
  onCopy: () => void
  onClose: () => void
}

const GAP = 10
const MARGIN = 8
/** Below this width the popover is a bottom sheet, placed by CSS rather than by the anchor. */
const SHEET_QUERY = '(max-width: 600px)'

function isSheet(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(SHEET_QUERY).matches
}

/**
 * Edits one snippet's note and tags. Saves when it closes, whichever way it closes: Done, a
 * click outside or Escape, so nothing typed is lost to the way it was dismissed.
 */
export function SnippetPopover({
  snippet, anchor, suggestions, onSave, onDelete, onCopy, onClose,
}: SnippetPopoverProps) {
  const [note, setNote] = useState(snippet.note)
  const [tags, setTags] = useState(snippet.tags)
  const [status, setStatus] = useState<string | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  // Decided once, before the first paint: measuring the box while it is laid out as a
  // full-width sheet would place a desktop popover against the window's left edge.
  const [sheet] = useState(isSheet)
  const [position, setPosition] = useState<{ left: number; top: number } | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const noteRef = useRef<HTMLTextAreaElement>(null)

  // Read by the outside-click and Escape handlers, which are bound once.
  const draftRef = useRef({ note, tags })
  draftRef.current = { note, tags }

  const commitRef = useRef<() => Promise<void>>(async () => undefined)
  commitRef.current = async () => {
    const draft = draftRef.current
    const changed = draft.note.trim() !== snippet.note
      || draft.tags.join('\u0000') !== snippet.tags.join('\u0000')
    if (changed) {
      setStatus('Saving…')
      try {
        await onSave({ note: draft.note, tags: draft.tags })
      } catch (error) {
        setStatus(error instanceof Error ? error.message : 'Could not save the note')
        return
      }
    }
    onClose()
  }

  useLayoutEffect(() => {
    const box = boxRef.current
    if (!box || sheet) return
    const width = box.offsetWidth
    const height = box.offsetHeight
    const left = Math.min(Math.max(MARGIN, anchor.left), window.innerWidth - width - MARGIN)
    const below = anchor.bottom + GAP
    const top = below + height <= window.innerHeight - MARGIN
      ? below
      : Math.max(MARGIN, anchor.top - height - GAP)
    setPosition({ left, top })
  }, [anchor, sheet])

  // Focused once it is placed: a box still hidden for measuring cannot take focus.
  const placed = sheet || position !== null
  useEffect(() => {
    if (placed) noteRef.current?.focus()
  }, [placed])

  // Escape closes only this. A capture listener on window runs before the drawer's own
  // Escape listener on document, wherever focus is, and stops the event reaching it, so the
  // summary behind stays open.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.stopPropagation()
      void commitRef.current()
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [])

  useEffect(() => {
    const onPointerDown = (event: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) {
        void commitRef.current()
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [])

  return createPortal(
    <div
      aria-label="Snippet"
      className={`snippet-popover${sheet ? ' snippet-popover--sheet' : ''}`}
      ref={boxRef}
      role="dialog"
      style={sheet ? undefined
        : position ? { left: position.left, top: position.top }
          // Measured where it is, unseen, then moved beside the passage.
          : { left: 0, top: 0, visibility: 'hidden' }}
    >
      <blockquote className="snippet-popover__quote">{snippet.exact}</blockquote>
      <label className="snippet-popover__label" htmlFor={`snippet-note-${snippet.id}`}>Note</label>
      <textarea
        className="snippet-popover__note"
        id={`snippet-note-${snippet.id}`}
        maxLength={2000}
        onChange={event => setNote(event.target.value)}
        placeholder="Why you kept this, where it might go…"
        ref={noteRef}
        value={note}
      />
      <SnippetTagInput onChange={setTags} suggestions={suggestions} tags={tags} />
      <div className="snippet-popover__actions">
        <span aria-live="polite" className="snippet-popover__status" role="status">
          {status ?? (
            <><Check aria-hidden="true" size={13} /> Saved</>
          )}
        </span>
        <button
          aria-label="Copy with citation"
          className="snippet-popover__icon"
          onClick={onCopy}
          title="Copy with citation"
          type="button"
        >
          <Copy aria-hidden="true" size={15} />
        </button>
        {confirmingDelete ? (
          <button
            className="button button--secondary snippet-popover__delete"
            onClick={() => {
              void onDelete().catch(error => {
                setStatus(error instanceof Error ? error.message : 'Could not delete it')
              })
            }}
            type="button"
          >
            Delete snippet
          </button>
        ) : (
          <button
            aria-label="Delete snippet"
            className="snippet-popover__icon"
            onClick={() => setConfirmingDelete(true)}
            title="Delete snippet"
            type="button"
          >
            <Trash2 aria-hidden="true" size={15} />
          </button>
        )}
        <button className="button button--primary" onClick={() => void commitRef.current()} type="button">
          Done
        </button>
      </div>
    </div>,
    document.body,
  )
}
