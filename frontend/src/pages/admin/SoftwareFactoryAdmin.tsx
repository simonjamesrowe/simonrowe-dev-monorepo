import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertCircle,
  CheckCircle2,
  CircleDashed,
  CloudCog,
  Loader2,
  Play,
  RefreshCw,
  ScanSearch,
  ShieldAlert,
  XCircle,
} from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { FRONTEND_COMMIT } from '../../config/version'
import { parsePullNumber } from './pullRequestInput'
import {
  fetchRunProgress,
  fetchSoftwareFactoryStatus,
  startCodeReview,
  startDeploy,
  startFeedback,
  startPlatformBackup,
  startVulnerabilityScan,
  type FactoryModuleStatus,
  type FactoryRunAccepted,
  type FactoryRunProgress,
  type FactoryScheduleStatus,
  type SoftwareFactoryStatus,
} from '../../services/softwareFactoryApi'

const formatTime = (value: string | null) =>
  value ? new Date(value).toLocaleString() : 'Not recorded'

/**
 * A paused schedule has no next action time, and neither does one Temporal has not computed yet,
 * so appending it unconditionally produced "Active · next Not recorded".
 */
function scheduleSummary(schedule: FactoryScheduleStatus): string {
  if (!schedule.exists) return 'Absent'
  const state = schedule.paused ? 'Paused' : 'Active'
  return schedule.nextActionAt ? `${state} · next ${formatTime(schedule.nextActionAt)}` : state
}

/** Three seconds tracks a deploy phase change closely without hammering an unrouted API. */
const POLL_INTERVAL_MS = 3000

/** Ten minutes of polling. A deploy that has not finished by then needs Temporal, not this page. */
const MAX_POLLS = 200

interface ActiveRun {
  key: string
  accepted: FactoryRunAccepted
  progress: FactoryRunProgress | null
}

/**
 * Follows an accepted run until Temporal says it has stopped.
 *
 * <p>The accepted response only proves the work is durably queued. Everything an operator wants
 * to know next — whether it started, which phase it reached, whether it failed — arrives after
 * that response has been sent, so the page has to ask.
 */
function useRunProgress(
  run: ActiveRun | null,
  setRun: (update: (previous: ActiveRun | null) => ActiveRun | null) => void,
) {
  const { getAccessToken } = useAuth()
  const workflowId = run?.accepted?.workflowId ?? null
  const terminal = run?.progress?.terminal ?? false
  const polls = useRef(0)

  useEffect(() => {
    polls.current = 0
  }, [workflowId])

  useEffect(() => {
    if (!workflowId || terminal) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const poll = async () => {
      try {
        const progress = await fetchRunProgress(getAccessToken, workflowId)
        if (cancelled) return
        setRun((previous) =>
          previous && previous.accepted?.workflowId === workflowId
            ? { ...previous, progress }
            : previous,
        )
        if (progress.terminal) return
      } catch {
        // A run that cannot be read yet is normal immediately after acceptance: keep the last
        // known state on screen and try again rather than replacing it with an error.
      }
      polls.current += 1
      if (!cancelled && polls.current < MAX_POLLS) {
        timer = setTimeout(() => void poll(), POLL_INTERVAL_MS)
      }
    }

    timer = setTimeout(() => void poll(), POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [workflowId, terminal, getAccessToken, setRun])
}

export function SoftwareFactoryAdmin() {
  const { getAccessToken } = useAuth()
  const [status, setStatus] = useState<SoftwareFactoryStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [run, setRun] = useState<ActiveRun | null>(null)
  const [reviewPullNumber, setReviewPullNumber] = useState('')
  const [pullNumber, setPullNumber] = useState('')
  const [deployConfirmation, setDeployConfirmation] = useState('')
  const [confirmBackup, setConfirmBackup] = useState(false)

  useRunProgress(run, setRun)

  const load = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      setStatus(await fetchSoftwareFactoryStatus(getAccessToken))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not load factory status')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => { void load() }, [load])

  const modules = useMemo(
    () => new Map(status?.modules.map((module) => [module.key, module]) ?? []),
    [status],
  )
  const shortCommit = status?.backendCommit?.slice(0, 7) ?? 'unknown'
  const deployPhrase = `REDEPLOY ${shortCommit}`
  const repository = status?.repository ?? 'the configured repository'
  const reviewNumber = parsePullNumber(reviewPullNumber)
  const feedbackNumber = parsePullNumber(pullNumber)

  const start = async (key: string, action: () => Promise<FactoryRunAccepted>) => {
    try {
      setPending(key)
      setError(null)
      setRun(null)
      const accepted = await action()
      setRun({ key, accepted, progress: null })
      await load()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Factory action failed')
    } finally {
      setPending(null)
    }
  }

  if (loading && !status) return <div className="admin-loading">Loading Software Factory…</div>

  return (
    <div className="admin-page factory-console">
      <div className="admin-page__header factory-console__header">
        <div>
          <span className="factory-console__eyebrow">Temporal operations</span>
          <h1 className="admin-page__title">Software Factory</h1>
          <p className="factory-console__intro">
            Observe each automation boundary and start the workflows that are safe to run by hand.
          </p>
        </div>
        <button className="admin-btn" disabled={loading} onClick={() => void load()} type="button">
          <RefreshCw className={loading ? 'factory-console__spin' : ''} size={16} /> Refresh
        </button>
      </div>

      <div className="factory-console__service-strip" aria-label="Factory service reachability">
        <ServiceState label="Factory" reachable={status?.factoryReachable ?? false} />
        <ServiceState label="Deployer" reachable={status?.deployerReachable ?? false} />
        <span className="factory-console__commit">production {shortCommit}</span>
      </div>

      {error && <div className="admin-error-banner"><AlertCircle size={16} /> {error}</div>}
      {run && <RunBanner run={run} />}

      <div className="factory-rail" role="list" aria-label="Software Factory modules">
        {status?.modules.map((module, index) => (
          <ModuleRow
            key={module.key}
            module={module}
            number={index + 1}
            action={actionFor(module)}
          />
        ))}
      </div>

      <section className="factory-console__controls" aria-labelledby="factory-actions-title">
        <div className="factory-console__controls-heading">
          <span>Manual controls</span>
          <h2 id="factory-actions-title">Durable workflow starts</h2>
        </div>

        <ActionPanel title="Code review" description="Re-review a pull request the webhook could not. A dry run reviews without posting anything; publishing comments on the pull request as the reviewer bot.">
          <label className="factory-console__field">
            Pull request to review
            <input
              placeholder="130 or a pull request URL"
              value={reviewPullNumber}
              onChange={(event) => setReviewPullNumber(event.target.value)}
            />
            <PullRequestHint repository={repository} value={reviewPullNumber} parsed={reviewNumber} />
          </label>
          <div className="factory-console__button-row">
            <button
              className="admin-btn"
              disabled={
                !modules.get('codereview')?.ready || pending !== null || reviewNumber === null
              }
              onClick={() => void start('review-dry',
                () => startCodeReview(getAccessToken, reviewNumber as number, false))}
              type="button"
            >
              {pending === 'review-dry'
                ? <Loader2 className="factory-console__spin" size={16} />
                : <ScanSearch size={16} />}
              Dry-run review
            </button>
            <button
              className="admin-btn admin-btn--primary"
              disabled={
                !modules.get('codereview')?.ready || pending !== null || reviewNumber === null
              }
              onClick={() => void start('review',
                () => startCodeReview(getAccessToken, reviewNumber as number, true))}
              type="button"
            >
              {pending === 'review'
                ? <Loader2 className="factory-console__spin" size={16} />
                : <Play size={16} />}
              Review and comment
            </button>
          </div>
        </ActionPanel>

        <ActionPanel title="Review feedback" description="Harvest a closed pull request, file one Linear issue, then propose guidance changes.">
          <label className="factory-console__field">
            Pull request to harvest
            <input
              placeholder="130 or a pull request URL"
              value={pullNumber}
              onChange={(event) => setPullNumber(event.target.value)}
            />
            <PullRequestHint repository={repository} value={pullNumber} parsed={feedbackNumber} />
          </label>
          <button
            className="admin-btn admin-btn--primary"
            disabled={
              !modules.get('feedback')?.ready || pending !== null || feedbackNumber === null
            }
            onClick={() => void start('feedback',
              () => startFeedback(getAccessToken, feedbackNumber as number))}
            type="button"
          >
            {pending === 'feedback' ? <Loader2 className="factory-console__spin" size={16} /> : <Play size={16} />}
            Process feedback
          </button>
        </ActionPanel>

        <ActionPanel title="Vulnerability report" description="Scan Dependency-Track and create or update one Linear ticket containing all current CVEs.">
          <button
            className="admin-btn admin-btn--primary"
            disabled={!modules.get('cvefix')?.ready || pending !== null}
            onClick={() => void start('cvefix', () => startVulnerabilityScan(getAccessToken))}
            type="button"
          ><ShieldAlert size={16} /> Scan now</button>
        </ActionPanel>

        <ActionPanel title="Platform backup" description="Dry runs exercise the capture plan. A real run uploads a new archive; restore remains a host operation.">
          <div className="factory-console__button-row">
            <button
              className="admin-btn"
              disabled={!modules.get('platformbackup')?.ready || pending !== null}
              onClick={() => void start('backup-dry', () => startPlatformBackup(getAccessToken, true))}
              type="button"
            >Dry run</button>
            {!confirmBackup ? (
              <button
                className="admin-btn admin-btn--danger"
                disabled={!modules.get('platformbackup')?.ready || pending !== null}
                onClick={() => setConfirmBackup(true)}
                type="button"
              >Back up now</button>
            ) : (
              <button
                className="admin-btn admin-btn--danger"
                disabled={pending !== null}
                onClick={() => {
                  setConfirmBackup(false)
                  void start('backup', () => startPlatformBackup(getAccessToken, false))
                }}
                type="button"
              >Confirm real backup</button>
            )}
          </div>
        </ActionPanel>

        <ActionPanel title="Redeploy production" description={`Only the currently running commit can be redeployed. Type ${deployPhrase} to continue.`} danger>
          <label className="factory-console__field">
            Confirmation phrase
            <input value={deployConfirmation} onChange={(event) => setDeployConfirmation(event.target.value)} />
          </label>
          <button
            className="admin-btn admin-btn--danger"
            disabled={
              !modules.get('deploy')?.ready || pending !== null || FRONTEND_COMMIT === 'unknown'
              || deployConfirmation !== deployPhrase
            }
            onClick={() => void start('deploy', () => startDeploy(getAccessToken, FRONTEND_COMMIT, deployConfirmation))}
            type="button"
          ><CloudCog size={16} /> Redeploy {shortCommit}</button>
        </ActionPanel>
      </section>
    </div>
  )
}

/**
 * Says what the field wants, and confirms what it understood.
 *
 * Naming the repository matters as much as the format: the actions always target the
 * server-configured repository, so an operator pasting a URL from a different one would
 * otherwise get a review of an unrelated pull request that happens to share the number.
 */
function PullRequestHint(
  { repository, value, parsed }: { repository: string; value: string; parsed: number | null },
) {
  if (value.trim() !== '' && parsed === null) {
    return (
      <span className="factory-console__hint factory-console__hint--bad">
        Not a pull request number or URL
      </span>
    )
  }
  return (
    <span className="factory-console__hint">
      {parsed === null
        ? `Number or pull request URL · ${repository}`
        : `${repository}#${parsed}`}
    </span>
  )
}

function ServiceState({ label, reachable }: { label: string; reachable: boolean }) {
  return (
    <span className={`factory-console__service factory-console__service--${reachable ? 'up' : 'down'}`}>
      {reachable ? <CheckCircle2 size={14} /> : <AlertCircle size={14} />}
      {label} {reachable ? 'reachable' : 'unreachable'}
    </span>
  )
}

/** Statuses Temporal uses for a run that stopped without succeeding. */
const FAILED_STATUSES = ['FAILED', 'TERMINATED', 'TIMED_OUT', 'CANCELED']

function runState(run: ActiveRun): { tone: 'running' | 'done' | 'failed'; label: string } {
  const status = run.progress?.executionStatus ?? ''
  const failed = FAILED_STATUSES.some((candidate) => status.includes(candidate))
  if (failed) return { tone: 'failed', label: 'Failed' }
  if (run.progress?.terminal) return { tone: 'done', label: 'Completed' }
  return { tone: 'running', label: run.progress ? 'Running' : 'Accepted' }
}

function RunBanner({ run }: { run: ActiveRun }) {
  const { tone, label } = runState(run)
  const icon = tone === 'failed'
    ? <XCircle size={16} />
    : tone === 'done'
      ? <CheckCircle2 size={16} />
      : <Loader2 className="factory-console__spin" size={16} />
  return (
    <div
      className={`factory-console__run factory-console__run--${tone}`}
      role="status"
      aria-live="polite"
    >
      {icon}
      <div>
        <strong>{label}</strong>
        <span> · <code>{run.accepted?.workflowId}</code></span>
        {run.progress?.phase && <span> · {run.progress.phase}</span>}
        <p>{run.progress?.detail ?? run.accepted?.detail ?? 'Queued in Temporal.'}</p>
      </div>
    </div>
  )
}

function ModuleRow({ module, number, action }: { module: FactoryModuleStatus; number: number; action: string }) {
  const schedule = module.schedule
  return (
    <article className={`factory-rail__row${module.ready ? '' : ' factory-rail__row--fault'}`} role="listitem">
      <div className="factory-rail__identity">
        <span className="factory-rail__number">{String(number).padStart(2, '0')}</span>
        <div><h2>{module.displayName}</h2><span>{module.taskQueue}</span></div>
      </div>
      <Checkpoint
        label="Configured"
        good={module.configured === true}
        value={module.configured === null ? 'Unconfirmed' : module.configured ? 'On' : 'Off'}
      />
      <Checkpoint
        label="Worker"
        good={module.ready}
        value={`${module.workflowPollers ?? '—'} workflow / ${module.activityPollers ?? '—'} activity`}
      />
      <Checkpoint
        label={schedule ? 'Schedule' : 'Trigger'}
        good={schedule ? schedule.exists && schedule.paused === false : module.ready}
        value={schedule ? scheduleSummary(schedule) : module.trigger}
      />
      <div className="factory-rail__action"><span>Manual</span><strong>{action}</strong></div>
      {module.missingPrerequisites?.length > 0 && (
        <ul className="factory-rail__prerequisites" aria-label={`${module.displayName} prerequisites`}>
          {module.missingPrerequisites.map((missing) => (
            <li key={missing}><AlertCircle size={14} /> {missing}</li>
          ))}
        </ul>
      )}
      {module.diagnostic && module.missingPrerequisites?.length === 0
        && <p className="factory-rail__diagnostic">{module.diagnostic}</p>}
    </article>
  )
}

function Checkpoint({ label, good, value }: { label: string; good: boolean; value: string }) {
  return (
    <div className="factory-rail__checkpoint">
      {good ? <CheckCircle2 size={16} /> : <CircleDashed size={16} />}
      <span>{label}</span><strong>{value}</strong>
    </div>
  )
}

function ActionPanel({
  title,
  description,
  danger = false,
  children,
}: {
  title: string
  description: string
  danger?: boolean
  children: React.ReactNode
}) {
  return (
    <div className={`factory-action${danger ? ' factory-action--danger' : ''}`}>
      <h3>{title}</h3><p>{description}</p><div className="factory-action__controls">{children}</div>
    </div>
  )
}

function actionFor(module: FactoryModuleStatus): string {
  if (module.key === 'linear') return 'Status only'
  if (module.key === 'codereview') return 'Review a PR'
  if (module.key === 'feedback') return 'Process PR'
  if (module.key === 'cvefix') return 'Scan now'
  if (module.key === 'deploy') return 'Guarded redeploy'
  return 'Dry run / backup'
}
