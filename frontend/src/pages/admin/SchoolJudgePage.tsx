import { useCallback, useEffect, useMemo, useState } from 'react'
import { CheckCircle2, Gavel, Loader2, ShieldCheck, ShieldAlert, XCircle } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  decideSchoolApproval,
  fetchSchoolJudgeSummary,
  runSchoolJudge,
  type SchoolJudgeSummary,
  type SchoolJudgementItem,
} from '../../services/adminApi'

type Filter = 'all' | 'APPROVED' | 'DECLINED' | 'QUEUED' | 'overridden'

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'APPROVED', label: 'Published' },
  { key: 'DECLINED', label: 'Kept private' },
  { key: 'QUEUED', label: 'Left for you' },
  { key: 'overridden', label: 'You overrode' },
]

const OUTCOME_LABEL: Record<SchoolJudgementItem['outcome'], string> = {
  APPROVED: 'Published',
  DECLINED: 'Kept private',
  QUEUED: 'Left for you',
  SHADOW: 'Shadow only',
}

const STATE_LABEL: Record<SchoolJudgementItem['currentState'], string> = {
  PUBLIC: 'public',
  PRIVATE: 'private',
  WAITING: 'waiting in Approvals',
  MISSING: 'deleted',
}

const MODE_NOTE: Record<SchoolJudgeSummary['mode'], string> = {
  AUTO: 'Deciding automatically after every mail sync.',
  SHADOW: 'Shadow mode: recording verdicts, deciding nothing.',
  OFF: 'Switched off. Set SCHOOL_JUDGE_MODE to turn it on.',
}

function decidedBy(item: SchoolJudgementItem): string {
  if (item.rule === 'follows-email') {
    return 'followed its email'
  }
  if (item.rule) {
    return `rule: ${item.rule}`
  }
  return `${item.verdict.toLowerCase()} at ${Math.round(item.confidence * 100)}%`
}

/**
 * What the publication judge decided about school email, and why.
 *
 * The owner asked for a summary they can glance at rather than a digest email. Every row keeps
 * the same make public / make private controls as the Documents page, so overruling the judge
 * is one click, and an overruled row is flagged so a pattern of wrong calls is easy to see.
 */
export function SchoolJudgePage() {
  const { getAccessToken } = useAuth()
  const [summary, setSummary] = useState<SchoolJudgeSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [filter, setFilter] = useState<Filter>('all')
  const [running, setRunning] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setSummary(await fetchSchoolJudgeSummary(getAccessToken))
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the judge summary')
    }
  }, [getAccessToken])

  useEffect(() => {
    void load()
  }, [load])

  const shown = useMemo(() => {
    const items = summary?.items ?? []
    if (filter === 'all') {
      return items
    }
    if (filter === 'overridden') {
      return items.filter((item) => item.overridden)
    }
    return items.filter((item) => item.outcome === filter)
  }, [summary, filter])

  async function runNow() {
    setRunning(true)
    try {
      const result = await runSchoolJudge(getAccessToken)
      setNotice(result.detail)
      setError(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not run the judge')
    } finally {
      setRunning(false)
    }
  }

  async function decide(item: SchoolJudgementItem, action: 'approve' | 'decline' | 'revoke') {
    setBusyId(item.id)
    try {
      await decideSchoolApproval(getAccessToken, item.id, action)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not change that document')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Term Time judge</h1>
      <p className="school-admin__intro">
        {summary ? MODE_NOTE[summary.mode] : 'Loading…'} Anything it is not sure about stays in
        Approvals for you.
      </p>

      {error && <div className="admin-error-banner">{error}</div>}
      {notice && <div className="admin-success-banner">{notice}</div>}

      {summary && (
        <section className="admin-section">
          <div className="school-judge__head">
            <h2 className="admin-section__title">Last 30 days</h2>
            <button
              type="button"
              className="admin-btn admin-btn--sm"
              disabled={running || summary.mode === 'OFF'}
              onClick={() => void runNow()}
            >
              {running ? (
                <Loader2 size={14} className="school-admin__spin" aria-hidden="true" />
              ) : (
                <Gavel size={14} aria-hidden="true" />
              )}
              {running ? 'Judging…' : 'Run the judge now'}
            </button>
          </div>
          <dl className="school-judge__tiles">
            <div className="school-judge__tile">
              <dt>Published</dt>
              <dd>{summary.approved}</dd>
            </div>
            <div className="school-judge__tile">
              <dt>Kept private</dt>
              <dd>{summary.declined}</dd>
            </div>
            <div className="school-judge__tile">
              <dt>Left for you</dt>
              <dd>{summary.queued}</dd>
            </div>
            <div className="school-judge__tile">
              <dt>You overrode</dt>
              <dd>{summary.overridden}</dd>
            </div>
          </dl>
          <p className="school-admin__source-note">
            {summary.model}, prompt {summary.version}.
          </p>
        </section>
      )}

      <div className="admin-tabs" role="tablist" aria-label="Filter judgements">
        {FILTERS.map((option) => (
          <button
            key={option.key}
            type="button"
            role="tab"
            aria-selected={filter === option.key}
            className={filter === option.key ? 'active' : ''}
            onClick={() => setFilter(option.key)}
          >
            {option.label}
          </button>
        ))}
      </div>

      {summary && shown.length === 0 && (
        <p className="school-admin__source-note">Nothing here yet.</p>
      )}

      <ul className="admin-approval-list">
        {shown.map((item) => (
          <li
            key={item.id}
            className={`admin-approval${item.overridden ? ' school-judge__item--overridden' : ''}`}
          >
            <div className="admin-approval__head">
              <h2 className="admin-approval__title">{item.title || '(no subject)'}</h2>
              <span className="admin-approval__meta">
                <span className={`school-admin__tag school-judge__outcome--${item.outcome}`}>
                  {OUTCOME_LABEL[item.outcome]}
                </span>
                {item.sourceType && <span className="school-admin__tag">{item.sourceType}</span>}
                <span className="school-admin__tag">{decidedBy(item)}</span>
                {item.publishedAt && new Date(item.publishedAt).toLocaleDateString('en-GB')}
              </span>
            </div>

            <p className="admin-approval__reason">
              {item.reason}
              {!item.senderAuthenticated && item.verdict === 'PUBLIC' && (
                <>
                  {' '}
                  <ShieldAlert size={13} aria-hidden="true" /> Sender not verified, so it was
                  not published.
                </>
              )}
              {item.senderAuthenticated && item.rule === null && (
                <>
                  {' '}
                  <ShieldCheck size={13} aria-label="Sender verified" />
                </>
              )}
            </p>
            {item.overridden && (
              <p className="school-judge__override">
                You changed this since: it is now {STATE_LABEL[item.currentState]}.
              </p>
            )}
            {item.preview && <pre className="admin-approval__preview">{item.preview}</pre>}

            <div className="school-admin__row-actions">
              {item.currentState === 'PUBLIC' && (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm"
                  disabled={busyId === item.id}
                  onClick={() => void decide(item, 'revoke')}
                >
                  <XCircle size={14} aria-hidden="true" /> Make private
                </button>
              )}
              {(item.currentState === 'PRIVATE' || item.currentState === 'WAITING') && (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm admin-btn--primary"
                  disabled={busyId === item.id}
                  onClick={() => void decide(item, 'approve')}
                >
                  <CheckCircle2 size={14} aria-hidden="true" /> Make public
                </button>
              )}
              {item.currentState === 'WAITING' && (
                <button
                  type="button"
                  className="admin-btn admin-btn--sm"
                  disabled={busyId === item.id}
                  onClick={() => void decide(item, 'decline')}
                >
                  <XCircle size={14} aria-hidden="true" /> Keep private
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
