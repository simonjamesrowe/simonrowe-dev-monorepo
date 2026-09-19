import { useCallback, useEffect, useState } from 'react'
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Download,
  FileDown,
  ExternalLink,
  Link2,
  XCircle,
} from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  bulkSchoolApproval,
  fetchSchoolDocuments,
  decideSchoolLink,
  openSchoolAttachment,
  type SchoolDocumentSummary,
  type SchoolLinkSummary,
} from '../../services/adminApi'

const PAGE_SIZE = 25

interface SchoolDocumentsAdminProps {
  /** Fixes the status filter, so the Approvals page is this table pre-filtered. */
  fixedStatus?: string
  heading: string
  subtitle: string
}

/**
 * The documents table, shared by the Approvals and Documents pages.
 *
 * One component with a fixed-status prop rather than two near-identical pages: approvals are
 * documents with a filter applied, and duplicating the table would mean fixing every future
 * bug twice.
 */
export function SchoolDocumentsAdmin({
  fixedStatus,
  heading,
  subtitle,
}: SchoolDocumentsAdminProps) {
  const { getAccessToken } = useAuth()
  const [rows, setRows] = useState<SchoolDocumentSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  const [sourceType, setSourceType] = useState('')
  const [visibility, setVisibility] = useState('')
  const [status, setStatus] = useState(fixedStatus ?? '')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [linkState, setLinkState] = useState<Record<string, SchoolLinkSummary>>({})

  const load = useCallback(async () => {
    try {
      setLoading(true)
      const result = await fetchSchoolDocuments(getAccessToken, {
        page,
        size: PAGE_SIZE,
        q: search,
        sourceType,
        visibility,
        status: fixedStatus ?? status,
      })
      setRows(result.items)
      setTotal(result.total)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load documents')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, page, search, sourceType, visibility, status, fixedStatus])

  useEffect(() => {
    void load()
  }, [load])

  // Clearing the selection when the page or filter changes matters: the checkboxes are keyed by
  // id, so a stale selection would silently apply a bulk action to rows no longer on screen.
  useEffect(() => {
    setSelected(new Set())
    setExpanded(new Set())
  }, [page, search, sourceType, visibility, status])

  function toggleExpanded(id: string) {
    setExpanded((prior) => {
      const next = new Set(prior)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  const allOnPageSelected = rows.length > 0 && rows.every((r) => selected.has(r.id))

  function toggleAll() {
    setSelected(allOnPageSelected ? new Set() : new Set(rows.map((r) => r.id)))
  }

  function toggle(id: string) {
    setSelected((prior) => {
      const next = new Set(prior)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  async function applyOne(
    id: string,
    action: 'approve' | 'decline' | 'revoke',
    force = false,
  ) {
    try {
      await bulkSchoolApproval(getAccessToken, [id], action, force)
      setNotice(action === 'revoke' ? 'Made private.' : 'Made public.')
      setError(null)
      void load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That did not go through')
    }
  }

  async function decideLink(link: SchoolLinkSummary, action: 'fetch' | 'ignore') {
    // Optimistically mark it in flight — a fetch can take many seconds and the row would
    // otherwise look inert.
    setLinkState((prior) => ({ ...prior, [link.id]: { ...link, status: 'PENDING' } }))
    try {
      const updated = await decideSchoolLink(getAccessToken, link.id, action)
      setLinkState((prior) => ({ ...prior, [link.id]: updated }))
      if (updated.status === 'FAILED') {
        setError(updated.failureReason ?? 'That fetch did not work')
      } else {
        setNotice(action === 'fetch' ? 'Fetched and ingested.' : 'Link ignored.')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That did not go through')
    }
  }

  async function applyBulk(action: 'approve' | 'decline' | 'revoke', force = false) {
    if (selected.size === 0) {
      return
    }
    try {
      const result = await bulkSchoolApproval(getAccessToken, [...selected], action, force)
      // Reports what actually happened rather than what was asked for, so the operator never
      // believes they published more than they did.
      const parts = [
        result.approved && `${result.approved} made public`,
        result.declined && `${result.declined} kept private`,
        result.revoked && `${result.revoked} revoked`,
        result.missing && `${result.missing} not found`,
      ].filter(Boolean)
      setNotice(parts.join(', ') || 'Nothing changed')
      setSelected(new Set())
      void load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That bulk action did not go through')
    }
  }

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">{heading}</h1>
      <p className="school-admin__intro">{subtitle}</p>

      {error && <div className="admin-error-banner">{error}</div>}
      {notice && <div className="admin-success-banner">{notice}</div>}

      <form
        className="school-admin__filters"
        onSubmit={(e) => {
          e.preventDefault()
          setPage(0)
          setSearch(query)
        }}
      >
        <input
          className="admin-form__input school-admin__search"
          placeholder="Search title and body…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search documents"
        />
        <select
          value={sourceType}
          onChange={(e) => {
            setPage(0)
            setSourceType(e.target.value)
          }}
          aria-label="Source"
        >
          <option value="">All sources</option>
          <option value="CALENDAR_FEED">Calendar</option>
          <option value="WEBSITE_PAGE">Website</option>
          <option value="PDF">PDF</option>
          <option value="EMAIL">Email</option>
          <option value="PASTED_NOTE">Pasted note</option>
          <option value="EXTERNAL_PAGE">Another school's site</option>
        </select>
        <select
          value={visibility}
          onChange={(e) => {
            setPage(0)
            setVisibility(e.target.value)
          }}
          aria-label="Tier"
        >
          <option value="">All tiers</option>
          <option value="PUBLIC">Public</option>
          <option value="RESTRICTED">Restricted</option>
        </select>
        {!fixedStatus && (
          <select
            value={status}
            onChange={(e) => {
              setPage(0)
              setStatus(e.target.value)
            }}
            aria-label="Status"
          >
            <option value="">Any status</option>
            <option value="awaiting">Awaiting approval</option>
            <option value="approved">Approved</option>
            <option value="declined">Declined</option>
          </select>
        )}
        <button type="submit" className="admin-btn admin-btn--sm">
          Search
        </button>
      </form>

      <div className="school-admin__bulk">
        <label className="school-admin__check-all">
          <input type="checkbox" checked={allOnPageSelected} onChange={toggleAll} />
          Select all on this page
        </label>
        <span className="school-admin__selected-count">{selected.size} selected</span>
        <button
          type="button"
          className="admin-btn admin-btn--sm admin-btn--primary"
          disabled={selected.size === 0}
          aria-label="Make selected documents public"
          onClick={() => void applyBulk('approve')}
        >
          Make public
        </button>
        <button
          type="button"
          className="admin-btn admin-btn--sm"
          disabled={selected.size === 0}
          aria-label="Keep selected documents private"
          onClick={() => void applyBulk('decline')}
        >
          Keep private
        </button>
        <button
          type="button"
          className="admin-btn admin-btn--sm"
          disabled={selected.size === 0}
          aria-label="Make selected documents private"
          onClick={() => void applyBulk('revoke')}
        >
          Make private
        </button>
      </div>

      {loading && <div className="admin-loading">Loading…</div>}
      {!loading && rows.length === 0 && <p className="admin-empty">Nothing matches.</p>}

      <ul className="admin-approval-list">
        {rows.map((row) => (
          <li key={row.id} className="admin-approval">
            <div className="admin-approval__head">
              <label className="school-admin__row-check">
                <input
                  type="checkbox"
                  checked={selected.has(row.id)}
                  onChange={() => toggle(row.id)}
                  aria-label={`Select ${row.title}`}
                />
                <h2 className="admin-approval__title">{row.title}</h2>
              </label>
              <span className="admin-approval__meta">
                <span className={`school-admin__tag school-admin__tag--${row.sourceType}`}>
                  {row.sourceType}
                </span>
                <span
                  className={`school-admin__tag school-admin__tag--${row.visibility}`}
                >
                  {row.visibility}
                </span>
                {new Date(row.publishedAt).toLocaleDateString('en-GB')}
              </span>
            </div>

            {row.proposalReason && (
              <p className="admin-approval__reason">{row.proposalReason}</p>
            )}
            <pre className="admin-approval__preview">
              {expanded.has(row.id) ? row.body : row.preview}
            </pre>

            {row.discoveredLinks.length > 0 && (
              <div className="school-admin__links">
                <p className="school-admin__links-title">
                  <Link2 size={14} aria-hidden="true" /> Links found in this message —{' '}
                  <strong>none have been followed</strong>
                </p>
                <ul>
                  {row.discoveredLinks.map((raw) => {
                    const link = linkState[raw.id] ?? raw
                    return (
                      <li key={link.id} className="school-admin__link">
                        <div className="school-admin__link-detail">
                          <span className="school-admin__link-text">{link.anchorText}</span>
                          <span className="school-admin__tag">{link.likelyKind}</span>
                          {/* The URL is shown in full and NOT clickable: clicking is exactly
                              the decision being made, and an accidental click on a tracking
                              link is the thing this page exists to prevent. */}
                          <code className="school-admin__link-url">{link.url}</code>
                          {link.failureReason && (
                            <span className="school-admin__link-error">
                              {link.failureReason}
                            </span>
                          )}
                        </div>
                        {link.status === 'PENDING' ? (
                          <div className="school-admin__link-actions">
                            <button
                              type="button"
                              className="admin-btn admin-btn--sm admin-btn--primary"
                              onClick={() => void decideLink(link, 'fetch')}
                            >
                              <Download size={13} aria-hidden="true" /> Fetch
                            </button>
                            <button
                              type="button"
                              className="admin-btn admin-btn--sm"
                              onClick={() => void decideLink(link, 'ignore')}
                            >
                              Ignore
                            </button>
                          </div>
                        ) : (
                          <span className="school-admin__tag">{link.status}</span>
                        )}
                      </li>
                    )
                  })}
                </ul>
              </div>
            )}

            <div className="school-admin__row-actions">
              {/*
                Per-row publish controls. The bulk bar above is for working through a queue of
                similar items; for a single already-public website PDF, ticking a checkbox and
                then reaching for a toolbar button is the wrong shape of interaction entirely.
              */}
              {row.visibility === 'PUBLIC' ? (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm"
                  onClick={() => void applyOne(row.id, 'revoke')}
                >
                  <XCircle size={14} aria-hidden="true" /> Make private
                </button>
              ) : (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm admin-btn--primary"
                  onClick={() => void applyOne(row.id, 'approve')}
                >
                  <CheckCircle2 size={14} aria-hidden="true" /> Make public
                </button>
              )}
              {/* The preview is 300 characters — nowhere near enough to judge a newsletter by,
                  which is the entire task on this page. The full body already came down with
                  the row, so this is a local expand rather than another request. */}
              {row.body.length > row.preview.length && (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm"
                  onClick={() => toggleExpanded(row.id)}
                  aria-expanded={expanded.has(row.id)}
                >
                  {expanded.has(row.id) ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  {expanded.has(row.id) ? 'Show less' : 'Show more'}
                </button>
              )}
              {row.originalUrl && (
                <a
                  className="admin-btn admin-btn--sm"
                  href={row.originalUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <ExternalLink size={14} aria-hidden="true" />
                  {row.sourceType === 'PDF' ? 'View PDF' : 'View original'}
                </a>
              )}
              {row.hasAttachment && (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm"
                  onClick={() =>
                    void openSchoolAttachment(getAccessToken, row.id).catch((err) =>
                      setError(err instanceof Error ? err.message : 'Could not open that PDF'),
                    )
                  }
                >
                  <FileDown size={14} aria-hidden="true" /> Open PDF
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>

      <nav className="school-admin__pager" aria-label="Pagination">
        <button
          type="button"
          className="admin-btn admin-btn--sm"
          disabled={page === 0}
          onClick={() => setPage((p) => Math.max(0, p - 1))}
        >
          Previous
        </button>
        <span>
          Page {page + 1} of {pages} · {total} total
        </span>
        <button
          type="button"
          className="admin-btn admin-btn--sm"
          disabled={page + 1 >= pages}
          onClick={() => setPage((p) => p + 1)}
        >
          Next
        </button>
      </nav>
    </div>
  )
}
