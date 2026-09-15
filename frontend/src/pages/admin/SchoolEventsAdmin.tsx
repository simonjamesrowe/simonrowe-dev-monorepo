import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import { fetchSchoolEvents, type SchoolEventRow } from '../../services/adminApi'

const PAGE_SIZE = 50

const TYPES = ['TERM_BOUNDARY', 'HALF_TERM', 'INSET', 'CLUB', 'TRIP', 'OTHER']

/** Extracted dated facts — the rows that answer "what is on this week". */
export function SchoolEventsAdmin() {
  const { getAccessToken } = useAuth()
  const [rows, setRows] = useState<SchoolEventRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  const [eventType, setEventType] = useState('')
  const [sourceType, setSourceType] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setLoading(true)
      const result = await fetchSchoolEvents(getAccessToken, {
        page,
        size: PAGE_SIZE,
        q: search,
        eventType,
        sourceType,
      })
      setRows(result.items)
      setTotal(result.total)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load events')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, page, search, eventType, sourceType])

  useEffect(() => {
    void load()
  }, [load])

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Term Time events</h1>
      <p className="school-admin__intro">
        Dated facts extracted from the calendar feed, newsletters and PDFs. These are what
        answer date questions — retrieval handles the prose.
      </p>

      {error && <div className="admin-error-banner">{error}</div>}

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
          placeholder="Search event titles…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search events"
        />
        <select
          value={eventType}
          onChange={(e) => {
            setPage(0)
            setEventType(e.target.value)
          }}
          aria-label="Event type"
        >
          <option value="">All types</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
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
          <option value="EMAIL">Email</option>
          <option value="PDF">PDF</option>
          <option value="WEBSITE_PAGE">Website</option>
          <option value="PASTED_NOTE">Pasted note</option>
          <option value="EXTERNAL_PAGE">Another school's site</option>
        </select>
        <button type="submit" className="admin-btn admin-btn--sm">
          Search
        </button>
      </form>

      {loading && <div className="admin-loading">Loading…</div>}
      {!loading && rows.length === 0 && <p className="admin-empty">No events match.</p>}

      {rows.length > 0 && (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Event</th>
              <th>Type</th>
              <th>Year groups</th>
              <th>Source</th>
              <th>Tier</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id}>
                <td>
                  {row.startDate}
                  {row.endDate !== row.startDate && ` → ${row.endDate}`}
                </td>
                <td>{row.title}</td>
                <td>
                  <span className={`school-admin__tag school-admin__tag--${row.eventType}`}>
                    {row.eventType}
                  </span>
                </td>
                <td>{row.yearGroups.length ? row.yearGroups.join(', ') : 'Whole school'}</td>
                <td>{row.sourceType}</td>
                <td>
                  <span className={`school-admin__tag school-admin__tag--${row.visibility}`}>
                    {row.visibility}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

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
