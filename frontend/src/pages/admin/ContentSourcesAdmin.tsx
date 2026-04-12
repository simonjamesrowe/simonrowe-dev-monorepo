import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchContentSources,
  updateContentSource,
  type AdminContentSource,
} from '../../services/adminApi'

export function ContentSourcesAdmin() {
  const { getAccessToken } = useAuth()

  const [sources, setSources] = useState<AdminContentSource[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadSources = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const data = await fetchContentSources(getAccessToken)
      setSources(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load content sources')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    loadSources()
  }, [loadSources])

  const handleToggleActive = async (source: AdminContentSource) => {
    try {
      setError(null)
      const updated = await updateContentSource(getAccessToken, source.id, {
        active: !source.active,
      })
      setSources((prev) => prev.map((s) => (s.id === source.id ? updated : s)))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update source')
    }
  }

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return 'Never'
    return new Date(dateStr).toLocaleString()
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Content Sources</h1>

      {error && <div className="admin-error-banner">{error}</div>}

      <section className="admin-section">
        <h2 className="admin-section__title">All Sources ({sources.length})</h2>

        {loading ? (
          <div className="admin-loading">Loading content sources...</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th className="admin-table__th">Name</th>
                <th className="admin-table__th">Base URL</th>
                <th className="admin-table__th">Type</th>
                <th className="admin-table__th">Strategy</th>
                <th className="admin-table__th">Active</th>
                <th className="admin-table__th">Last Fetched</th>
                <th className="admin-table__th">Last Error</th>
              </tr>
            </thead>
            <tbody>
              {sources.length === 0 && (
                <tr>
                  <td className="admin-table__td admin-table__td--empty" colSpan={7}>
                    No content sources found.
                  </td>
                </tr>
              )}
              {sources.map((source) => (
                <tr key={source.id} className="admin-table__row">
                  <td className="admin-table__td">{source.name}</td>
                  <td className="admin-table__td">
                    <a
                      className="admin-link"
                      href={source.baseUrl}
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      {source.baseUrl}
                    </a>
                  </td>
                  <td className="admin-table__td">
                    <span className="admin-badge">{source.sourceType}</span>
                  </td>
                  <td className="admin-table__td">
                    <span className="admin-badge admin-badge--secondary">{source.scrapeStrategy}</span>
                  </td>
                  <td className="admin-table__td">
                    <label className="admin-toggle" title={source.active ? 'Active - click to deactivate' : 'Inactive - click to activate'}>
                      <input
                        checked={source.active}
                        className="admin-toggle__input"
                        onChange={() => handleToggleActive(source)}
                        type="checkbox"
                      />
                      <span className="admin-toggle__track" />
                    </label>
                  </td>
                  <td className="admin-table__td admin-table__td--secondary">
                    {formatDate(source.lastFetchedAt)}
                  </td>
                  <td className="admin-table__td">
                    {source.lastError ? (
                      <span className="admin-error-text" title={source.lastError}>
                        {source.lastError.length > 60
                          ? `${source.lastError.slice(0, 60)}…`
                          : source.lastError}
                      </span>
                    ) : (
                      <span className="admin-table__td--secondary">-</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
