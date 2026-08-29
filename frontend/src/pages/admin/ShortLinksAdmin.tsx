import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ExternalLink } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { fetchAdminShortLinks, type AdminShortLink } from '../../services/adminApi'

type SortKey = 'slug' | 'title' | 'contentType' | 'clickCount' | 'lastClickedAt'

const CONTENT_TYPE_LABELS: Record<AdminShortLink['contentType'], string> = {
  BLOG: 'Blog',
  ARTICLE: 'News',
  EVENT: 'Event',
}

/**
 * The shared-links table.
 *
 * Loads every row in one request and sorts in the browser: there is one row per piece of
 * content, a few hundred at most, and nothing here is editable, so paging would cost a
 * round trip per click for no benefit.
 */
export function ShortLinksAdmin() {
  const { getAccessToken } = useAuth()
  const [links, setLinks] = useState<AdminShortLink[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [sortKey, setSortKey] = useState<SortKey>('clickCount')
  const [descending, setDescending] = useState(true)

  const load = useCallback(async () => {
    try {
      setLoading(true)
      setLinks(await fetchAdminShortLinks(getAccessToken))
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load short links')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    void load()
  }, [load])

  const sorted = useMemo(() => {
    const direction = descending ? -1 : 1
    return [...links].sort((a, b) => compare(a, b, sortKey) * direction)
  }, [links, sortKey, descending])

  // Clicking the active column flips the direction; clicking another switches to it,
  // starting descending — for a statistics table the interesting end is the top.
  const sortBy = (key: SortKey) => {
    if (key === sortKey) {
      setDescending(previous => !previous)
    } else {
      setSortKey(key)
      setDescending(true)
    }
  }

  const header = (key: SortKey, label: string) => (
    <th>
      <button className="admin-table__sort" onClick={() => sortBy(key)} type="button">
        <span>{label}</span>
        {sortKey === key && (descending
          ? <ArrowDown aria-hidden="true" size={14} />
          : <ArrowUp aria-hidden="true" size={14} />)}
      </button>
    </th>
  )

  if (loading) return <div>Loading...</div>
  if (error) return <div className="error">{error}</div>

  return (
    <div>
      <div className="admin-header">
        <h1>Share Links</h1>
      </div>
      {links.length === 0 ? (
        <p>No share links yet.</p>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              {header('slug', 'Link')}
              {header('title', 'Content')}
              {header('contentType', 'Type')}
              {header('clickCount', 'Clicks')}
              {header('lastClickedAt', 'Last opened')}
            </tr>
          </thead>
          <tbody>
            {sorted.map(link => (
              <tr key={link.slug}>
                <td>
                  <a href={link.shortUrl} rel="noopener noreferrer" target="_blank">
                    /s/{link.slug} <ExternalLink aria-hidden="true" size={12} />
                  </a>
                </td>
                {/* A null title is an orphaned link — the content was deleted but the slug
                    is still in URLs people hold, so the row stays visible. */}
                <td>{link.title ?? <em>Deleted content</em>}</td>
                <td>{CONTENT_TYPE_LABELS[link.contentType]}</td>
                <td>{link.clickCount}</td>
                <td>
                  {link.lastClickedAt
                    ? new Date(link.lastClickedAt).toLocaleString()
                    : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function compare(a: AdminShortLink, b: AdminShortLink, key: SortKey): number {
  if (key === 'clickCount') return a.clickCount - b.clickCount
  if (key === 'lastClickedAt') {
    // Never-opened sorts below every opened link in both directions, which is what
    // "sort by last opened" means to someone reading the table.
    const left = a.lastClickedAt ? Date.parse(a.lastClickedAt) : -Infinity
    const right = b.lastClickedAt ? Date.parse(b.lastClickedAt) : -Infinity
    return left === right ? 0 : left - right
  }
  return String(a[key] ?? '').localeCompare(String(b[key] ?? ''))
}
