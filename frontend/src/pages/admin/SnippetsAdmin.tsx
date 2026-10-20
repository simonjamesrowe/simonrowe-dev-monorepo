import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  BookOpen,
  ChevronDown,
  Copy,
  Download,
  ExternalLink,
  Highlighter,
  NotebookPen,
  Search,
  Sparkles,
  Trash2,
} from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { SnippetTagInput } from '../../components/snippets/SnippetTagInput'
import {
  citation,
  copyText,
  exportMarkdown,
  formatCitationDate,
} from '../../components/snippets/snippetCitation'
import {
  deleteSnippet,
  fetchSnippetSources,
  fetchSnippetTags,
  searchSnippets,
  updateSnippet,
} from '../../services/snippetsApi'
import type { Snippet, SnippetSort, TagCount } from '../../types/snippets'

const PAGE_SIZE = 50
const SEARCH_DEBOUNCE_MS = 300
const MESSAGE_MS = 3000
/** The server's limits on a search, applied here too so highlighting matches what it found. */
const MAX_TERMS = 6
const MAX_QUERY_LENGTH = 100

function searchTerms(query: string): string[] {
  return query.trim().slice(0, MAX_QUERY_LENGTH).split(/\s+/).filter(Boolean).slice(0, MAX_TERMS)
}

/** Marks every search word in the text, matched literally and ignoring case. */
function Marked({ text, terms }: { text: string; terms: string[] }) {
  if (terms.length === 0) return <>{text}</>
  const lower = text.toLowerCase()
  const needles = terms.map(term => term.toLowerCase())
  const parts: Array<{ text: string; hit: boolean }> = []
  let index = 0
  while (index < text.length) {
    let next = -1
    let length = 0
    for (const needle of needles) {
      const at = lower.indexOf(needle, index)
      if (at >= 0 && (next < 0 || at < next || (at === next && needle.length > length))) {
        next = at
        length = needle.length
      }
    }
    if (next < 0) {
      parts.push({ text: text.slice(index), hit: false })
      break
    }
    if (next > index) parts.push({ text: text.slice(index, next), hit: false })
    parts.push({ text: text.slice(next, next + length), hit: true })
    index = next + length
  }
  return (
    <>
      {parts.map((part, i) => (part.hit
        ? <mark className="snippets-admin__match" key={i}>{part.text}</mark>
        : <Fragment key={i}>{part.text}</Fragment>))}
    </>
  )
}

function downloadMarkdown(markdown: string) {
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `snippets-${new Date().toISOString().slice(0, 10)}.md`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/**
 * Every passage kept from a news summary or a blog post (spec 054), searchable and ready to
 * copy into a talk.
 *
 * Bulk actions only ever touch snippets that are on screen: the selection is intersected with
 * what the current filters show, so changing a filter never copies something hidden.
 */
export function SnippetsAdmin() {
  const { getAccessToken } = useAuth()
  // Searches read the token through a ref, so they re-run when a filter changes and never
  // because the token function's identity did.
  const tokenRef = useRef(getAccessToken)
  tokenRef.current = getAccessToken
  const token = useCallback(() => tokenRef.current(), [])
  const [queryInput, setQueryInput] = useState('')
  const [query, setQuery] = useState('')
  const [chosenTags, setChosenTags] = useState<string[]>([])
  const [chosenSources, setChosenSources] = useState<string[]>([])
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sort, setSort] = useState<SnippetSort>('newest')

  const [results, setResults] = useState<Snippet[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [last, setLast] = useState(true)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [tagCounts, setTagCounts] = useState<TagCount[]>([])
  const [sources, setSources] = useState<string[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [activeId, setActiveId] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const requestId = useRef(0)
  const messageTimer = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearTimeout(messageTimer.current), [])

  const say = useCallback((text: string) => {
    setMessage(text)
    window.clearTimeout(messageTimer.current)
    messageTimer.current = window.setTimeout(() => setMessage(null), MESSAGE_MS)
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(queryInput), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [queryInput])

  const loadFacets = useCallback(() => {
    fetchSnippetTags(token).then(setTagCounts).catch(() => undefined)
    fetchSnippetSources(token).then(setSources).catch(() => undefined)
  }, [token])

  useEffect(() => {
    loadFacets()
  }, [loadFacets])

  const load = useCallback(async (pageNumber: number) => {
    const id = ++requestId.current
    setLoading(true)
    try {
      const found = await searchSnippets(token, {
        q: query,
        tags: chosenTags,
        sources: chosenSources,
        from: from || undefined,
        to: to || undefined,
        sort,
        page: pageNumber,
        size: PAGE_SIZE,
      })
      // A slower answer to an older search must not replace a newer one.
      if (id !== requestId.current) return
      setResults(previous => (pageNumber === 0 ? found.content : [...previous, ...found.content]))
      setTotal(found.totalElements)
      setPage(pageNumber)
      setLast(found.last)
      setError(null)
    } catch (failure) {
      if (id === requestId.current) {
        setError(failure instanceof Error ? failure.message : 'Could not load snippets')
      }
    } finally {
      if (id === requestId.current) setLoading(false)
    }
  }, [token, query, chosenTags, chosenSources, from, to, sort])

  useEffect(() => {
    void load(0)
  }, [load])

  const terms = useMemo(() => searchTerms(query), [query])
  const chosen = results.filter(snippet => selected.has(snippet.id))
  const allShownSelected = results.length > 0 && chosen.length === results.length
  const active = results.find(snippet => snippet.id === activeId) ?? null
  const filtersApplied = query.trim() !== '' || chosenTags.length > 0
    || chosenSources.length > 0 || from !== '' || to !== ''

  const toggle = (id: string) => setSelected(previous => {
    const next = new Set(previous)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })

  const toggleAllShown = () => setSelected(previous => {
    const next = new Set(previous)
    for (const snippet of results) {
      if (allShownSelected) next.delete(snippet.id)
      else next.add(snippet.id)
    }
    return next
  })

  const toggleTag = (tag: string) => setChosenTags(previous =>
    (previous.includes(tag) ? previous.filter(existing => existing !== tag) : [...previous, tag]))

  const clearFilters = () => {
    setQueryInput('')
    setQuery('')
    setChosenTags([])
    setChosenSources([])
    setFrom('')
    setTo('')
  }

  const copyChosen = async () => {
    const copied = await copyText(exportMarkdown(chosen, chosenTags))
    say(copied ? `Copied ${chosen.length} snippet${chosen.length === 1 ? '' : 's'} as Markdown` : 'Could not copy')
  }

  const replace = (saved: Snippet) =>
    setResults(previous => previous.map(snippet => (snippet.id === saved.id ? saved : snippet)))

  const remove = async (snippet: Snippet) => {
    await deleteSnippet(token, snippet.id)
    setResults(previous => previous.filter(existing => existing.id !== snippet.id))
    setTotal(previous => Math.max(0, previous - 1))
    setSelected(previous => {
      const next = new Set(previous)
      next.delete(snippet.id)
      return next
    })
    setActiveId(null)
    loadFacets()
    say('Snippet deleted')
  }

  return (
    <div className="snippets-admin">
      <section className="snippets-admin__main">
        <div className="admin-header">
          <h1 className="snippets-admin__title">
            <Highlighter aria-hidden="true" size={22} /> Snippets
          </h1>
        </div>
        <p className="snippets-admin__intro">
          Passages kept from news summaries and blog posts. Only you can see these. Select text
          in any summary on News &amp; Events, or in any post on the blog, to add one.
        </p>

        <div className="snippets-admin__filters">
          <label className="snippets-admin__search">
            <Search aria-hidden="true" size={15} />
            <span className="snippets-admin__sr">Search snippets</span>
            <input
              onChange={event => setQueryInput(event.target.value)}
              placeholder="Search quotes, notes, tags, titles…"
              type="search"
              value={queryInput}
            />
          </label>
          <SourcePicker chosen={chosenSources} onChange={setChosenSources} sources={sources} />
          <label className="snippets-admin__date">
            <span>Kept from</span>
            <input max={to || undefined} onChange={event => setFrom(event.target.value)} type="date" value={from} />
          </label>
          <label className="snippets-admin__date">
            <span>to</span>
            <input min={from || undefined} onChange={event => setTo(event.target.value)} type="date" value={to} />
          </label>
          <label className="snippets-admin__sort">
            <span className="snippets-admin__sr">Sort</span>
            <select onChange={event => setSort(event.target.value as SnippetSort)} value={sort}>
              <option value="newest">Newest snippet</option>
              <option value="article">Newest article</option>
            </select>
          </label>
        </div>

        {tagCounts.length > 0 && (
          <div aria-label="Filter by tag" className="snippets-admin__chips" role="group">
            {tagCounts.map(({ tag, count }) => (
              <button
                aria-pressed={chosenTags.includes(tag)}
                className={`snippets-admin__chip${chosenTags.includes(tag) ? ' snippets-admin__chip--on' : ''}`}
                key={tag}
                onClick={() => toggleTag(tag)}
                type="button"
              >
                #{tag} <span className="snippets-admin__chip-count">{count}</span>
              </button>
            ))}
          </div>
        )}

        {chosen.length > 0 && (
          <div className="snippets-admin__bulk">
            <span className="snippets-admin__bulk-count">{chosen.length} selected</span>
            <button className="button button--secondary" onClick={() => void copyChosen()} type="button">
              <Copy aria-hidden="true" size={14} /> Copy as Markdown
            </button>
            <button
              className="button button--secondary"
              onClick={() => {
                downloadMarkdown(exportMarkdown(chosen, chosenTags))
                say(`Downloaded ${chosen.length} snippet${chosen.length === 1 ? '' : 's'}`)
              }}
              type="button"
            >
              <Download aria-hidden="true" size={14} /> Download .md
            </button>
            <button className="snippets-admin__link-button" onClick={() => setSelected(new Set())} type="button">
              Clear
            </button>
          </div>
        )}

        <p aria-live="polite" className="snippets-admin__message" role="status">{message}</p>

        {error && <div className="error" role="alert">{error}</div>}

        {results.length > 0 && (
          <div className="snippets-admin__list-head">
            <label>
              <input checked={allShownSelected} onChange={toggleAllShown} type="checkbox" />
              Select all shown
            </label>
            <span>{results.length} shown of {total}</span>
          </div>
        )}

        {!loading && !error && results.length === 0 && (
          <div className="snippets-admin__empty">
            {filtersApplied ? (
              <>
                <p>No snippets match. Try fewer words, or clear a tag.</p>
                <button className="button button--secondary" onClick={clearFilters} type="button">
                  Clear filters
                </button>
              </>
            ) : (
              <p>
                No snippets yet. Open a summary on <Link to="/news-events">News &amp; Events</Link>
                {' '}or a post on the <Link to="/blogs">blog</Link>, and select a passage to keep it.
              </p>
            )}
          </div>
        )}

        <ul className="snippets-admin__list">
          {results.map(snippet => (
            <li
              className={`snippets-admin__card${snippet.id === activeId ? ' snippets-admin__card--active' : ''}`}
              key={snippet.id}
            >
              <input
                aria-label={`Select snippet from ${snippet.articleTitle}`}
                checked={selected.has(snippet.id)}
                onChange={() => toggle(snippet.id)}
                type="checkbox"
              />
              <button
                className="snippets-admin__card-body"
                onClick={() => setActiveId(snippet.id)}
                type="button"
              >
                <span className="snippets-admin__quote"><Marked terms={terms} text={snippet.exact} /></span>
                {snippet.note && (
                  <span className="snippets-admin__note">
                    <NotebookPen aria-hidden="true" size={13} /> <Marked terms={terms} text={snippet.note} />
                  </span>
                )}
                <span className="snippets-admin__foot">
                  <span className="snippets-admin__source"><Marked terms={terms} text={snippet.sourceName} /></span>
                  <span><Marked terms={terms} text={snippet.articleTitle} /></span>
                  {snippet.articlePublishedAt && <span>· {formatCitationDate(snippet.articlePublishedAt)}</span>}
                  {!snippet.inCurrentSummary && (
                    <span
                      className="snippets-admin__stale"
                      title={snippet.sourceType === 'BLOG'
                        ? 'The post was edited and this text is no longer in it. Your copy is kept.'
                        : 'The summary was regenerated and this text is no longer in it. Your copy is kept.'}
                    >
                      {snippet.sourceType === 'BLOG' ? 'not in current post' : 'not in current summary'}
                    </span>
                  )}
                  {snippet.tags.length > 0 && (
                    <span className="snippets-admin__tags">
                      {snippet.tags.map(tag => <span className="snippet-tag" key={tag}>#<Marked terms={terms} text={tag} /></span>)}
                    </span>
                  )}
                </span>
              </button>
            </li>
          ))}
        </ul>

        {!last && (
          <div className="snippets-admin__more">
            <button className="button button--secondary" disabled={loading} onClick={() => void load(page + 1)} type="button">
              {loading ? 'Loading…' : 'Load more'}
            </button>
          </div>
        )}
      </section>

      <aside
        aria-label="Snippet details"
        className={`snippets-admin__detail${active ? '' : ' snippets-admin__detail--empty'}`}
      >
        {active ? (
          <SnippetDetail
            key={active.id}
            onCopy={async () => {
              const copied = await copyText(citation(active))
              say(copied ? 'Copied with citation' : 'Could not copy')
            }}
            onDelete={() => remove(active)}
            onSave={async change => {
              const saved = await updateSnippet(token, active.id, change)
              replace(saved)
              loadFacets()
              say('Saved')
            }}
            snippet={active}
            suggestions={tagCounts}
          />
        ) : (
          <>
            <h2 className="snippets-admin__detail-title">Snippet</h2>
            <p className="snippets-admin__detail-empty">
              Pick a snippet to edit its note and tags, copy it, or go back to the summary or post
              it came from. Tick several and use Copy as Markdown to drop a talk&apos;s reading list into
              your notes.
            </p>
          </>
        )}
      </aside>
    </div>
  )
}

function SourcePicker({
  sources, chosen, onChange,
}: { sources: string[]; chosen: string[]; onChange: (sources: string[]) => void }) {
  const label = chosen.length === 0 ? 'All sources'
    : chosen.length === 1 ? chosen[0] : `${chosen.length} sources`
  return (
    <details className="snippets-admin__sources">
      <summary>
        {label} <ChevronDown aria-hidden="true" size={14} />
      </summary>
      <div className="snippets-admin__sources-panel">
        {sources.length === 0 && <p className="snippets-admin__detail-empty">No sources yet.</p>}
        {sources.map(source => (
          <label className="snippets-admin__source-option" key={source}>
            <input
              checked={chosen.includes(source)}
              onChange={() => onChange(chosen.includes(source)
                ? chosen.filter(existing => existing !== source)
                : [...chosen, source])}
              type="checkbox"
            />
            {source}
          </label>
        ))}
      </div>
    </details>
  )
}

interface SnippetDetailProps {
  snippet: Snippet
  suggestions: TagCount[]
  onSave: (change: { note: string; tags: string[] }) => Promise<void>
  onDelete: () => Promise<void>
  onCopy: () => Promise<void>
}

function SnippetDetail({ snippet, suggestions, onSave, onDelete, onCopy }: SnippetDetailProps) {
  const [note, setNote] = useState(snippet.note)
  const [tags, setTags] = useState(snippet.tags)
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const dirty = note.trim() !== snippet.note || tags.join('\u0000') !== snippet.tags.join('\u0000')
  const details = [snippet.author, snippet.sourceName, formatCitationDate(snippet.articlePublishedAt)]
    .filter(Boolean)
    .join(' · ')
  const post = snippet.sourceType === 'BLOG'
  const openLabel = post ? 'Open post' : 'Open summary'
  const openPath = post
    ? `/blogs/${encodeURIComponent(snippet.contentId)}`
    : `/news-events?article=${encodeURIComponent(snippet.contentId)}`

  const run = async (action: () => Promise<void>) => {
    setSaving(true)
    try {
      await action()
      setFailure(null)
    } catch (error) {
      setFailure(error instanceof Error ? error.message : 'Something went wrong')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="snippets-admin__editor">
      <h2 className="snippets-admin__detail-title">Snippet</h2>
      <blockquote className="snippets-admin__detail-quote">{snippet.exact}</blockquote>
      <label className="snippet-tags__label" htmlFor="snippet-detail-note">Note</label>
      <textarea
        className="snippets-admin__detail-note"
        id="snippet-detail-note"
        maxLength={2000}
        onChange={event => setNote(event.target.value)}
        placeholder="Why you kept this…"
        value={note}
      />
      <SnippetTagInput onChange={setTags} suggestions={suggestions} tags={tags} />
      <div className="snippets-admin__citation">
        {post ? 'From' : 'AI summary of'} <strong>{snippet.articleTitle}</strong>
        {details && <><br />{details}</>}
        <br />
        <a href={snippet.originalUrl} rel="noopener noreferrer" target="_blank">
          {post ? 'The post' : 'Original article'} <ExternalLink aria-hidden="true" size={12} />
        </a>
      </div>
      {failure && <p className="error" role="alert">{failure}</p>}
      <div className="snippets-admin__detail-actions">
        <button
          className="button button--primary"
          disabled={!dirty || saving}
          onClick={() => void run(() => onSave({ note, tags }))}
          type="button"
        >
          Save
        </button>
        <button className="button button--secondary" onClick={() => void onCopy()} type="button">
          <Copy aria-hidden="true" size={14} /> Copy
        </button>
        {snippet.articleAvailable ? (
          <Link className="button button--secondary" to={openPath}>
            {post ? <BookOpen aria-hidden="true" size={14} /> : <Sparkles aria-hidden="true" size={14} />}
            {' '}{openLabel}
          </Link>
        ) : (
          <button
            className="button button--secondary"
            disabled
            title={post
              ? 'The post is not published. The snippet and its citation are kept.'
              : 'The article is no longer on the site. The snippet and its citation are kept.'}
            type="button"
          >
            {post ? <BookOpen aria-hidden="true" size={14} /> : <Sparkles aria-hidden="true" size={14} />}
            {' '}{openLabel}
          </button>
        )}
        <button
          className="button button--secondary snippets-admin__delete"
          disabled={saving}
          onClick={() => {
            if (confirmingDelete) void run(onDelete)
            else setConfirmingDelete(true)
          }}
          type="button"
        >
          <Trash2 aria-hidden="true" size={14} /> {confirmingDelete ? 'Confirm delete' : 'Delete'}
        </button>
      </div>
      <p className="snippets-admin__kept">
        Kept {formatCitationDate(snippet.createdAt)}. The quote itself can&apos;t be edited, because
        then it would no longer be a quote.
      </p>
    </div>
  )
}
