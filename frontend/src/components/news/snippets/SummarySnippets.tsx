import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { AlertCircle, Copy, Heart, Highlighter, NotebookPen } from 'lucide-react'

import { useAuth } from '../../../auth/useAuth'
import {
  createSnippet,
  deleteSnippet,
  fetchSnippetsFor,
  fetchSnippetTags,
  updateSnippet,
} from '../../../services/snippetsApi'
import type { ArticleResponse } from '../../../types/news'
import type { Snippet, TagCount } from '../../../types/snippets'
import { citation, copyText } from './snippetCitation'
import { SnippetPopover, type SnippetAnchor } from './SnippetPopover'
import {
  caretAt,
  locate,
  MAX_QUOTE_LENGTH,
  offsetOf,
  quoteFromRange,
  rangeFor,
  readProse,
  supportsHighlights,
  type Quote,
} from './snippetText'

interface SummarySnippetsProps {
  article: ArticleResponse
  /** The rendered summary, which this layer reads and highlights but never changes. */
  proseRef: RefObject<HTMLDivElement | null>
  /** The summary's markdown when it is READY, otherwise null: there is nothing to select. */
  summaryBody: string | null
  isFavourite: boolean
  onFavourite: () => void
  /** Tells the page how many snippets this article now has, for the card's badge. */
  onCountChange?: (contentId: string, count: number) => void
}

interface PendingQuote extends Quote {
  anchor: DOMRect | SnippetAnchor & { width: number }
  tooLong: boolean
}

const SELECTION_SETTLE_MS = 200
const FLASH_MS = 1600
const MESSAGE_MS = 3000
const HIGHLIGHTS = ['snippet', 'snippet-focus', 'snippet-new'] as const
const TOOLBAR_GAP = 10
const TOOLBAR_MARGIN = 8

/** jsdom's Range has no geometry; a real browser's always does. */
function anchorOf(range: Range): PendingQuote['anchor'] {
  if (typeof range.getClientRects !== 'function') {
    return { left: 0, top: 0, bottom: 0, width: 0 }
  }
  const rects = range.getClientRects()
  return rects.length > 0 ? rects[rects.length - 1] : range.getBoundingClientRect()
}

function rangeAnchor(range: Range | null, fallback: Element): SnippetAnchor {
  const rect = range && typeof range.getBoundingClientRect === 'function'
    ? range.getBoundingClientRect()
    : fallback.getBoundingClientRect()
  return { left: rect.left, top: rect.top, bottom: rect.bottom }
}

/**
 * Snippets in the summary drawer (spec 054): select a passage to keep it, click a highlight
 * to edit its note and tags, and see everything kept from this article listed under the
 * summary. Rendered only for the site admin; nobody else ever calls the snippets API.
 *
 * Highlights are drawn with the CSS Custom Highlight API, so React's DOM for the summary is
 * never touched. Where the API is missing the marks are skipped and the list still works.
 */
export function SummarySnippets({
  article, proseRef, summaryBody, isFavourite, onFavourite, onCountChange,
}: SummarySnippetsProps) {
  const { getAccessToken } = useAuth()
  // The load below reads the token through a ref, so it runs once per article rather than
  // once per render of a caller whose token function is not stable.
  const tokenRef = useRef(getAccessToken)
  tokenRef.current = getAccessToken
  const [snippets, setSnippets] = useState<Snippet[]>([])
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [suggestions, setSuggestions] = useState<TagCount[]>([])
  const [pending, setPending] = useState<PendingQuote | null>(null)
  const [open, setOpen] = useState<{ id: string; anchor: SnippetAnchor } | null>(null)
  const [flashId, setFlashId] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [nudge, setNudge] = useState(false)
  const [toolbarPosition, setToolbarPosition] = useState<{ left: number; top: number; arrow: number } | null>(null)
  const toolbarRef = useRef<HTMLDivElement>(null)
  const snippetsRef = useRef(snippets)
  snippetsRef.current = snippets
  const flashTimer = useRef<number | undefined>(undefined)
  const messageTimer = useRef<number | undefined>(undefined)
  const scrollTimer = useRef<number | undefined>(undefined)

  // Every timer this layer starts is cleared when the drawer closes, not just ignored later.
  useEffect(() => () => {
    window.clearTimeout(flashTimer.current)
    window.clearTimeout(messageTimer.current)
    window.clearTimeout(scrollTimer.current)
  }, [])

  const say = useCallback((text: string) => {
    setMessage(text)
    window.clearTimeout(messageTimer.current)
    messageTimer.current = window.setTimeout(() => setMessage(null), MESSAGE_MS)
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoaded(false)
    setSnippets([])
    setOpen(null)
    setNudge(false)
    const token = () => tokenRef.current()
    fetchSnippetsFor(token, article.id)
      .then(found => {
        if (cancelled) return
        setSnippets(found)
        setLoadError(null)
        setLoaded(true)
      })
      .catch(error => {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : 'Could not load your snippets')
      })
    fetchSnippetTags(token)
      .then(tags => { if (!cancelled) setSuggestions(tags) })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [article.id])

  useEffect(() => {
    if (loaded) onCountChange?.(article.id, snippets.length)
  }, [loaded, snippets.length, article.id, onCountChange])

  // A selection settles before the toolbar appears: `selectionchange` fires for every
  // character a drag passes, and on a phone for every nudge of a handle.
  useEffect(() => {
    if (!summaryBody) return
    let timer: number | undefined
    const check = () => {
      const prose = proseRef.current
      const selection = document.getSelection()
      if (!prose || !selection || selection.rangeCount === 0 || selection.isCollapsed) {
        setPending(null)
        return
      }
      const range = selection.getRangeAt(0)
      if (!range.intersectsNode(prose)) {
        setPending(null)
        return
      }
      const quote = quoteFromRange(readProse(prose), range)
      setPending(quote
        ? { ...quote, anchor: anchorOf(range), tooLong: quote.exact.length > MAX_QUOTE_LENGTH }
        : null)
    }
    const onSelectionChange = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(check, SELECTION_SETTLE_MS)
    }
    document.addEventListener('selectionchange', onSelectionChange)
    return () => {
      window.clearTimeout(timer)
      document.removeEventListener('selectionchange', onSelectionChange)
    }
  }, [summaryBody, proseRef])

  // The toolbar is fixed to the viewport, so a scroll of the drawer would leave it behind.
  useEffect(() => {
    const scroller = proseRef.current?.closest('.drawer__body')
    if (!scroller) return
    const hide = () => setPending(null)
    scroller.addEventListener('scroll', hide)
    return () => scroller.removeEventListener('scroll', hide)
  }, [proseRef, summaryBody])

  // Below the selection rather than above, so it clears the phone's own copy/paste callout.
  useLayoutEffect(() => {
    const toolbar = toolbarRef.current
    if (!pending || !toolbar) {
      setToolbarPosition(null)
      return
    }
    const width = toolbar.offsetWidth
    const centre = pending.anchor.left + pending.anchor.width / 2
    const left = Math.min(
      Math.max(TOOLBAR_MARGIN, centre - width / 2),
      window.innerWidth - width - TOOLBAR_MARGIN,
    )
    setToolbarPosition({ left, top: pending.anchor.bottom + TOOLBAR_GAP, arrow: centre - left })
  }, [pending])

  useEffect(() => {
    const prose = proseRef.current
    if (!prose || !summaryBody || !supportsHighlights()) return
    const text = readProse(prose)
    const groups: Record<(typeof HIGHLIGHTS)[number], Range[]> = {
      snippet: [], 'snippet-focus': [], 'snippet-new': [],
    }
    for (const snippet of snippets) {
      const start = locate(text.text, snippet)
      const range = start >= 0 ? rangeFor(text, start, start + snippet.exact.length) : null
      if (!range) continue
      const group = snippet.id === open?.id ? 'snippet-focus'
        : snippet.id === flashId ? 'snippet-new' : 'snippet'
      groups[group].push(range)
    }
    for (const name of HIGHLIGHTS) CSS.highlights.set(name, new Highlight(...groups[name]))
    return () => {
      for (const name of HIGHLIGHTS) CSS.highlights.delete(name)
    }
  }, [snippets, summaryBody, open?.id, flashId, proseRef])

  const openSnippet = useCallback((id: string, anchor: SnippetAnchor) => {
    setPending(null)
    setOpen({ id, anchor })
  }, [])

  // Clicking a highlight opens it. The marks are not elements, so the click is matched to
  // a snippet by where in the text it landed.
  useEffect(() => {
    const prose = proseRef.current
    if (!prose || !summaryBody) return
    const onClick = (event: MouseEvent) => {
      const selection = document.getSelection()
      if (selection && !selection.isCollapsed) return
      const caret = caretAt(event.clientX, event.clientY)
      if (!caret) return
      const text = readProse(prose)
      const offset = offsetOf(text, caret.node, caret.offset)
      if (offset < 0) return
      const hit = snippetsRef.current.find(snippet => {
        const start = locate(text.text, snippet)
        return start >= 0 && offset >= start && offset <= start + snippet.exact.length
      })
      if (hit) {
        openSnippet(hit.id, { left: event.clientX - 20, top: event.clientY, bottom: event.clientY + 6 })
      }
    }
    prose.addEventListener('click', onClick)
    return () => prose.removeEventListener('click', onClick)
  }, [proseRef, summaryBody, openSnippet])

  const clearSelection = () => {
    document.getSelection()?.removeAllRanges()
    setPending(null)
  }

  const keep = async (withNote: boolean) => {
    const prose = proseRef.current
    if (!pending || pending.tooLong || busy || !prose) return
    const anchor = { left: pending.anchor.left, top: pending.anchor.top, bottom: pending.anchor.bottom }
    const text = readProse(prose).text
    const existing = snippets.find(snippet =>
      snippet.exact === pending.exact && locate(text, snippet) === pending.start)
    if (existing) {
      clearSelection()
      openSnippet(existing.id, anchor)
      say('Already highlighted. Edit it here.')
      return
    }
    setBusy(true)
    try {
      const saved = await createSnippet(getAccessToken, {
        contentId: article.id,
        exact: pending.exact,
        prefix: pending.prefix,
        suffix: pending.suffix,
      })
      const first = snippets.length === 0
      setSnippets(previous => [...previous, saved])
      clearSelection()
      setFlashId(saved.id)
      window.clearTimeout(flashTimer.current)
      flashTimer.current = window.setTimeout(() => setFlashId(null), FLASH_MS)
      if (first && !isFavourite) setNudge(true)
      if (withNote) openSnippet(saved.id, anchor)
      else say('Snippet saved')
    } catch (error) {
      say(error instanceof Error ? error.message : 'Could not keep that passage')
    } finally {
      setBusy(false)
    }
  }

  const copyUnsaved = async () => {
    if (!pending) return
    const copied = await copyText(citation({
      exact: pending.exact,
      articleTitle: article.title,
      sourceName: article.sourceName,
      author: article.author,
      originalUrl: article.originalUrl,
      articlePublishedAt: article.publishedDate,
    }))
    clearSelection()
    say(copied ? 'Copied with citation (not saved)' : 'Could not copy. Select the text and copy it yourself.')
  }

  const copySaved = async (snippet: Snippet) => {
    const copied = await copyText(citation(snippet))
    say(copied ? 'Copied with citation' : 'Could not copy')
  }

  const showInText = (snippet: Snippet, item: Element) => {
    const prose = proseRef.current
    const text = prose ? readProse(prose) : null
    const start = text ? locate(text.text, snippet) : -1
    const range = text && start >= 0 ? rangeFor(text, start, start + snippet.exact.length) : null
    if (!range) {
      openSnippet(snippet.id, rangeAnchor(null, item))
      return
    }
    const block = range.startContainer.parentElement
    if (block && typeof block.scrollIntoView === 'function') {
      block.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
    // Anchored once the scroll has moved the passage into view.
    window.clearTimeout(scrollTimer.current)
    scrollTimer.current = window.setTimeout(
      () => openSnippet(snippet.id, rangeAnchor(range, item)), 350)
  }

  // Ordered as they appear in the text; any no longer in it go last, oldest first.
  const ordered = (() => {
    const prose = proseRef.current
    const text = prose && summaryBody ? readProse(prose).text : ''
    return snippets
      .map((snippet, index) => ({ snippet, index, at: text ? locate(text, snippet) : -1 }))
      .sort((a, b) => {
        if (a.at < 0 || b.at < 0) return a.at < 0 && b.at < 0 ? a.index - b.index : a.at < 0 ? 1 : -1
        return a.at - b.at
      })
      .map(entry => entry.snippet)
  })()

  const openSnippetRecord = open ? snippets.find(snippet => snippet.id === open.id) : undefined

  if (!summaryBody && snippets.length === 0 && !loadError) return null

  return (
    <section aria-label="Your snippets" className="summary-snippets">
      <div className="summary-snippets__head">
        <h3 className="summary-snippets__title">
          <Highlighter aria-hidden="true" size={15} /> Your snippets ({snippets.length})
        </h3>
        <Link className="summary-snippets__all" to="/admin/snippets">All snippets</Link>
      </div>

      {nudge && (
        <div className="summary-snippets__nudge">
          <span>Heart this article too, so it is in your Favourites?</span>
          <span className="summary-snippets__nudge-actions">
            <button
              className="button button--secondary"
              onClick={() => { onFavourite(); setNudge(false) }}
              type="button"
            >
              <Heart aria-hidden="true" size={14} /> Heart it
            </button>
            <button className="summary-snippets__dismiss" onClick={() => setNudge(false)} type="button">
              Not now
            </button>
          </span>
        </div>
      )}

      {loadError && <p className="summary-snippets__error" role="alert">{loadError}</p>}

      {!loadError && snippets.length === 0 && loaded && (
        <p className="summary-snippets__empty">
          Select any passage in the summary to keep it as a snippet.
        </p>
      )}

      {ordered.length > 0 && (
        <ul className="summary-snippets__list">
          {ordered.map(snippet => (
            <li key={snippet.id}>
              <button
                className="summary-snippets__item"
                onClick={event => showInText(snippet, event.currentTarget)}
                type="button"
              >
                <span className="summary-snippets__quote">{snippet.exact}</span>
                {snippet.note && <span className="summary-snippets__note">{snippet.note}</span>}
                {snippet.tags.length > 0 && (
                  <span className="summary-snippets__tags">
                    {snippet.tags.map(tag => <span className="snippet-tag" key={tag}>#{tag}</span>)}
                  </span>
                )}
                {!snippet.inCurrentSummary && (
                  <span className="summary-snippets__stale">
                    <AlertCircle aria-hidden="true" size={12} />
                    No longer in the current summary. Your copy is kept.
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}

      <p aria-live="polite" className="summary-snippets__message" role="status">{message}</p>

      {pending && !open && createPortal(
        <div
          aria-label="Keep this passage"
          className="snippet-toolbar"
          // Keeps the selection alive while a button is pressed.
          onMouseDown={event => event.preventDefault()}
          ref={toolbarRef}
          role="toolbar"
          style={toolbarPosition
            ? { left: toolbarPosition.left, top: toolbarPosition.top, ['--snippet-arrow' as string]: `${toolbarPosition.arrow}px` }
            : { visibility: 'hidden' }}
        >
          {pending.tooLong ? (
            <span className="snippet-toolbar__warning">
              That is over {MAX_QUOTE_LENGTH.toLocaleString('en-GB')} characters. Select a shorter passage.
            </span>
          ) : (
            <>
              <button
                className="snippet-toolbar__button snippet-toolbar__button--main"
                disabled={busy}
                onClick={() => void keep(false)}
                type="button"
              >
                <Highlighter aria-hidden="true" size={14} /> Highlight
              </button>
              <button className="snippet-toolbar__button" disabled={busy} onClick={() => void keep(true)} type="button">
                <NotebookPen aria-hidden="true" size={14} /> Add note
              </button>
              <button className="snippet-toolbar__button" onClick={() => void copyUnsaved()} type="button">
                <Copy aria-hidden="true" size={14} /> Copy with citation
              </button>
            </>
          )}
        </div>,
        document.body,
      )}

      {open && openSnippetRecord && (
        <SnippetPopover
          anchor={open.anchor}
          key={openSnippetRecord.id}
          onClose={() => setOpen(current => (current?.id === openSnippetRecord.id ? null : current))}
          onCopy={() => void copySaved(openSnippetRecord)}
          onDelete={async () => {
            await deleteSnippet(getAccessToken, openSnippetRecord.id)
            setSnippets(previous => previous.filter(snippet => snippet.id !== openSnippetRecord.id))
            setOpen(null)
            say('Snippet deleted')
          }}
          onSave={async change => {
            const saved = await updateSnippet(getAccessToken, openSnippetRecord.id, change)
            setSnippets(previous => previous.map(snippet => (snippet.id === saved.id ? saved : snippet)))
            setSuggestions(previous => mergeTags(previous, saved.tags))
          }}
          snippet={openSnippetRecord}
          suggestions={suggestions}
        />
      )}
    </section>
  )
}

/** Adds newly used tags to the suggestions so the next snippet is offered them too. */
function mergeTags(previous: TagCount[], tags: string[]): TagCount[] {
  const known = new Set(previous.map(entry => entry.tag))
  return [...previous, ...tags.filter(tag => !known.has(tag)).map(tag => ({ tag, count: 1 }))]
}
