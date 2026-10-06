import { useCallback, useEffect, useState } from 'react'
import { Check, ExternalLink, X } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  dismissNewsletterCandidate,
  fetchContentSources,
  fetchNewsletterCandidates,
  fetchNewsletterReviewSummary,
  promoteNewsletterCandidate,
  type AdminNewsletterCandidate,
  type NewsletterCandidateStatus,
  type NewsletterReviewSummary,
  type PageResponse,
} from '../../services/adminApi'

const PAGE_SIZE = 20

const TABS: { status: NewsletterCandidateStatus; label: string }[] = [
  { status: 'PENDING', label: 'Waiting for review' },
  { status: 'ACCEPTED', label: 'Saved automatically' },
  { status: 'PROMOTED', label: 'Promoted' },
  { status: 'DISMISSED', label: 'Dismissed' },
]

function formatRelevance(score: number): string {
  return score.toFixed(2)
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}

/**
 * The review queue for email newsletter stories (the TLDR editions).
 *
 * Every story is scored against the articles hearted on News & Events. Those at or above the
 * threshold are saved without review; the rest wait here, where promoting one saves it exactly
 * as an automatic save would, and dismissing one records the decision so it is never offered
 * again.
 */
export function NewsletterReviewAdmin() {
  const { getAccessToken } = useAuth()

  const [status, setStatus] = useState<NewsletterCandidateStatus>('PENDING')
  const [source, setSource] = useState('')
  const [sort, setSort] = useState<'relevance' | 'received'>('relevance')
  const [page, setPage] = useState(0)

  const [data, setData] = useState<PageResponse<AdminNewsletterCandidate> | null>(null)
  const [summary, setSummary] = useState<NewsletterReviewSummary | null>(null)
  const [editions, setEditions] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    fetchContentSources(getAccessToken)
      .then((sources) => {
        if (cancelled) return
        setEditions(
          sources
            .filter((s) => s.scrapeStrategy === 'EMAIL_NEWSLETTER')
            .map((s) => s.name)
            .sort((a, b) => a.localeCompare(b)),
        )
      })
      .catch(() => {
        // The edition filter is a convenience; the queue still works without it.
      })
    return () => {
      cancelled = true
    }
  }, [getAccessToken])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all([
      fetchNewsletterCandidates(getAccessToken, { status, source, sort, page, size: PAGE_SIZE }),
      fetchNewsletterReviewSummary(getAccessToken),
    ])
      .then(([candidates, counts]) => {
        if (cancelled) return
        setData(candidates)
        setSummary(counts)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof Error ? err.message : 'Failed to load the review queue')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [getAccessToken, status, source, sort, page, reloadKey])

  const decide = useCallback(
    async (candidate: AdminNewsletterCandidate, action: 'promote' | 'dismiss') => {
      setBusyId(candidate.id)
      setError(null)
      try {
        if (action === 'promote') {
          await promoteNewsletterCandidate(getAccessToken, candidate.id)
        } else {
          await dismissNewsletterCandidate(getAccessToken, candidate.id)
        }
        // The row has left this tab. Step back a page if it was the last one on it.
        if (data && data.content.length === 1 && page > 0) {
          setPage(page - 1)
        } else {
          setReloadKey((k) => k + 1)
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : `Failed to ${action} the story`)
      } finally {
        setBusyId(null)
      }
    },
    [data, getAccessToken, page],
  )

  const totalPages = data?.totalPages ?? 0

  return (
    <div className="admin-page newsletter-review">
      <h1 className="admin-page__title">Newsletter review</h1>
      <p className="newsletter-review__intro">
        Stories from email newsletters, scored by how close each is to an article you have
        hearted.
        {summary && (
          <>
            {' '}
            Stories scoring {formatRelevance(summary.relevanceThreshold)} or more are saved
            automatically, up to {summary.maxAcceptedPerRun} per edition per run. The rest wait
            here, and cost nothing more until you promote one.
          </>
        )}
      </p>

      {error && <div className="admin-error-banner">{error}</div>}

      <div className="admin-tabs" role="tablist">
        {TABS.map((tab) => (
          <button
            key={tab.status}
            type="button"
            role="tab"
            aria-selected={status === tab.status}
            className={status === tab.status ? 'active' : undefined}
            onClick={() => {
              setStatus(tab.status)
              setPage(0)
            }}
          >
            {tab.label}
            {summary ? ` (${summary.counts[tab.status] ?? 0})` : ''}
          </button>
        ))}
      </div>

      <div className="newsletter-review__filters">
        <select
          aria-label="Edition"
          value={source}
          onChange={(e) => {
            setSource(e.target.value)
            setPage(0)
          }}
        >
          <option value="">All editions</option>
          {editions.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
        <select
          aria-label="Sort"
          value={sort}
          onChange={(e) => {
            setSort(e.target.value as 'relevance' | 'received')
            setPage(0)
          }}
        >
          <option value="relevance">Most relevant first</option>
          <option value="received">Newest first</option>
        </select>
        {data && <span className="newsletter-review__count">{data.totalElements} stories</span>}
      </div>

      {loading && !data ? (
        <p>Loading stories...</p>
      ) : data && data.content.length === 0 ? (
        <p className="admin-empty">Nothing here.</p>
      ) : (
        <table className="admin-table newsletter-review__table">
          <thead>
            <tr>
              <th className="admin-table__th">Relevance</th>
              <th className="admin-table__th">Story</th>
              <th className="admin-table__th">Closest heart</th>
              <th className="admin-table__th">Actions</th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map((candidate) => (
              <tr key={candidate.id}>
                <td className="admin-table__td">
                  <span
                    className={
                      summary && candidate.relevance >= summary.relevanceThreshold
                        ? 'newsletter-review__score newsletter-review__score--relevant'
                        : 'newsletter-review__score'
                    }
                    title={candidate.reason}
                  >
                    {formatRelevance(candidate.relevance)}
                  </span>
                </td>
                <td className="admin-table__td">
                  <a
                    className="newsletter-review__title"
                    href={candidate.url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {candidate.title}
                    <ExternalLink size={12} aria-hidden="true" />
                  </a>
                  <p className="newsletter-review__summary">{candidate.summary}</p>
                  <p className="newsletter-review__meta">
                    {[candidate.sourceName, candidate.section, candidate.label]
                      .filter(Boolean)
                      .join(' · ')}{' '}
                    · {formatDate(candidate.receivedAt)}
                  </p>
                  <p className="newsletter-review__reason">{candidate.reason}</p>
                </td>
                <td className="admin-table__td newsletter-review__nearest">
                  {candidate.nearestFavourite ?? '—'}
                </td>
                <td className="admin-table__td admin-table__td--actions">
                  {(candidate.status === 'PENDING' || candidate.status === 'DISMISSED') && (
                    <button
                      type="button"
                      className="admin-btn admin-btn--sm admin-btn--primary"
                      disabled={busyId !== null}
                      onClick={() => decide(candidate, 'promote')}
                      aria-label={`Promote ${candidate.title}`}
                    >
                      <Check size={14} aria-hidden="true" />
                      {busyId === candidate.id ? 'Saving…' : 'Promote'}
                    </button>
                  )}
                  {candidate.status === 'PENDING' && (
                    <button
                      type="button"
                      className="admin-btn admin-btn--sm"
                      disabled={busyId !== null}
                      onClick={() => decide(candidate, 'dismiss')}
                      aria-label={`Dismiss ${candidate.title}`}
                    >
                      <X size={14} aria-hidden="true" />
                      Dismiss
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {totalPages > 1 && (
        <nav className="newsletter-review__pager" aria-label="Pages">
          <button
            type="button"
            className="admin-btn admin-btn--sm"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </button>
          <span>
            Page {page + 1} of {totalPages}
          </span>
          <button
            type="button"
            className="admin-btn admin-btn--sm"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </nav>
      )}
    </div>
  )
}
