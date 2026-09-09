import { useCallback, useEffect, useState } from 'react'
import { CalendarDays, Globe, Mail, RefreshCw } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchSchoolStatus,
  fetchSchoolUsage,
  triggerSchoolIngest,
  type SchoolStatus,
  type SchoolUsageSummary,
} from '../../services/adminApi'

const SOURCES = [
  {
    key: 'calendar' as const,
    label: 'Calendar feed',
    icon: <CalendarDays size={16} />,
    note: 'One request. Term dates and INSET days.',
  },
  {
    key: 'website' as const,
    label: 'Website + PDFs',
    icon: <Globe size={16} />,
    note: 'Around 30 minutes — the school asks for a 10s delay per page.',
  },
  {
    key: 'gmail' as const,
    label: 'School mailbox',
    icon: <Mail size={16} />,
    note: 'Body text, PDF attachments and extracted dates.',
  },
]

/** Sub-cent totals are the normal case here, so two decimal places would read as zero. */
function money(value: number): string {
  if (value === 0) {
    return '$0'
  }
  return value < 0.01 ? `$${value.toFixed(4)}` : `$${value.toFixed(2)}`
}

function when(value: string | null): string {
  return value ? new Date(value).toLocaleString('en-GB') : 'never'
}

/**
 * Term Time overview: what has been ingested, and manual ingest triggers.
 *
 * Polls while a run is in progress. A website crawl takes half an hour, so a static page would
 * leave the operator refreshing to find out whether anything was happening.
 */
export function SchoolOverviewAdmin() {
  const { getAccessToken } = useAuth()
  const [status, setStatus] = useState<SchoolStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [usage, setUsage] = useState<SchoolUsageSummary | null>(null)

  const load = useCallback(async () => {
    try {
      setStatus(await fetchSchoolStatus(getAccessToken))
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load Term Time status')
    }
  }, [getAccessToken])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    // Failing to load spend must not blank the page — the ingest controls above are the part
    // someone came here to use.
    fetchSchoolUsage(getAccessToken).then(setUsage).catch(() => setUsage(null))
  }, [getAccessToken])

  useEffect(() => {
    if (!status?.sources.some((s) => s.running)) {
      return
    }
    const timer = setInterval(() => void load(), 5000)
    return () => clearInterval(timer)
  }, [status, load])

  async function run(source: 'calendar' | 'website' | 'gmail') {
    try {
      const result = await triggerSchoolIngest(getAccessToken, source)
      setNotice(result.detail)
      setError(null)
      void load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start that ingest')
    }
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Term Time</h1>

      {error && <div className="admin-error-banner">{error}</div>}
      {notice && <div className="admin-success-banner">{notice}</div>}

      <section className="admin-section">
        <h2 className="admin-section__title">Sources</h2>
        <div className="school-admin__sources">
        {SOURCES.map((source) => {
          const state = status?.sources.find((s) => s.source === source.key)
          return (
            <div key={source.key} className="school-admin__source">
              <div className="school-admin__source-head">
                {source.icon}
                <strong>{source.label}</strong>
              </div>
              <p className="school-admin__source-note">{source.note}</p>
              <dl className="school-admin__source-meta">
                <dt>Last success</dt>
                <dd>{when(state?.lastSuccessAt ?? null)}</dd>
                {state?.lastFailureReason && (
                  <>
                    <dt>Last failure</dt>
                    <dd className="school-admin__failure">{state.lastFailureReason}</dd>
                  </>
                )}
              </dl>
              <button
                type="button"
                className="admin-btn admin-btn--sm"
                disabled={state?.running}
                onClick={() => void run(source.key)}
              >
                <RefreshCw size={14} className={state?.running ? 'school-admin__spin' : ''} />
                {state?.running ? 'Running…' : 'Run now'}
              </button>
            </div>
          )
        })}
        </div>
      </section>

      {usage && (
        <section className="admin-section">
          <h2 className="admin-section__title">
            Cost and usage · last {usage.windowDays} days
          </h2>
          <div className="school-admin__counts">
            <div className="school-admin__count-group">
              <h2>Spend</h2>
              <ul>
                <li>
                  <span>This period</span>
                  <strong>{money(usage.windowCostUsd)}</strong>
                </li>
                <li>
                  <span>All time</span>
                  <strong>{money(usage.totalCostUsd)}</strong>
                </li>
              </ul>
              {usage.includesEstimates && (
                <p className="school-admin__estimate-note">
                  Includes estimated figures. Chat costs come from the provider&rsquo;s own token
                  counts; indexing, classification and extraction are estimated from text length,
                  because those calls do not report usage back.
                </p>
              )}
            </div>
            <div className="school-admin__count-group">
              <h2>Spend by activity</h2>
              <ul>
                {Object.entries(usage.costByKind)
                  .filter(([, v]) => v > 0)
                  .map(([k, v]) => (
                    <li key={k}>
                      <span>{k}</span>
                      <strong>{money(v)}</strong>
                    </li>
                  ))}
              </ul>
            </div>
            <div className="school-admin__count-group">
              <h2>Usage</h2>
              <ul>
                <li>
                  <span>Questions answered</span>
                  <strong>{usage.chatTurns}</strong>
                </li>
                <li>
                  <span>Conversations</span>
                  <strong>{usage.distinctSessions}</strong>
                </li>
                <li>
                  <span>Distinct visitors</span>
                  <strong>{usage.distinctClients}</strong>
                </li>
              </ul>
              {/* Said plainly: there is no sign-in, so neither figure is a count of people. */}
              <p className="school-admin__estimate-note">
                Term Time has no sign-in, so these count conversations and distinct (hashed)
                addresses, not identified people.
              </p>
            </div>
          </div>
        </section>
      )}

      {status && (
        <section className="admin-section">
          <h2 className="admin-section__title">Corpus</h2>
          <div className="school-admin__counts">
          <div className="school-admin__count-group">
            <h2>Documents by source</h2>
            <ul>
              {Object.entries(status.documentsBySource).map(([k, v]) => (
                <li key={k}>
                  <span>{k}</span>
                  <strong>{v}</strong>
                </li>
              ))}
            </ul>
          </div>
          <div className="school-admin__count-group">
            <h2>Documents by tier</h2>
            <ul>
              {Object.entries(status.documentsByVisibility).map(([k, v]) => (
                <li key={k}>
                  <span>{k}</span>
                  <strong>{v}</strong>
                </li>
              ))}
              <li>
                <span>Awaiting approval</span>
                <strong>{status.awaitingApproval}</strong>
              </li>
            </ul>
          </div>
          <div className="school-admin__count-group">
            <h2>Events ({status.totalEvents})</h2>
            <ul>
              {Object.entries(status.eventsByType).map(([k, v]) => (
                <li key={k}>
                  <span>{k}</span>
                  <strong>{v}</strong>
                </li>
              ))}
            </ul>
          </div>
          </div>
        </section>
      )}
    </div>
  )
}
