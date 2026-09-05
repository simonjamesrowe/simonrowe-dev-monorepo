import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertCircle,
  CheckCircle2,
  CloudCog,
  Loader2,
  Play,
  RefreshCw,
  ScanSearch,
  ScrollText,
  ShieldAlert,
  XCircle,
} from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { FRONTEND_COMMIT } from '../../config/version'
import { FactoryFlowGraph } from './FactoryFlowGraph'
import { FactoryNodeDrawer } from './FactoryNodeDrawer'
import { parsePullNumber } from './pullRequestInput'
import {
  fetchFactoryFlow,
  fetchFactoryFlowDetail,
  fetchRunProgress,
  fetchSoftwareFactoryStatus,
  startCodeReview,
  startDeploy,
  startFeedback,
  startLogWatchScan,
  startPlatformBackup,
  startVulnerabilityScan,
  type FactoryFlow,
  type FactoryFlowDetail,
  type FactoryModuleStatus,
  type FactoryRunAccepted,
  type FactoryRunProgress,
  type SoftwareFactoryStatus,
} from '../../services/softwareFactoryApi'

/** Three seconds tracks a deploy phase change closely without hammering an unrouted API. */
const POLL_INTERVAL_MS = 3000

/** Ten minutes of polling. A deploy that has not finished by then needs Temporal, not this page. */
const MAX_POLLS = 200

/** Off is the default: a console left open on a second monitor should not poll all day. */
const REFRESH_OPTIONS: { label: string; ms: number | null }[] = [
  { label: 'Off', ms: null },
  { label: '15s', ms: 15_000 },
  { label: '1m', ms: 60_000 },
  { label: '5m', ms: 300_000 },
]

function RefreshInterval(
  { value, onChange }: { value: number | null; onChange: (ms: number | null) => void },
) {
  return (
    <fieldset className="factory-console__refresh" role="radiogroup" aria-label="Refresh interval">
      {REFRESH_OPTIONS.map((option) => (
        <label key={option.label}>
          <input
            type="radio"
            name="factory-refresh"
            checked={value === option.ms}
            onChange={() => onChange(option.ms)}
          />
          {option.label}
        </label>
      ))}
    </fieldset>
  )
}

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

interface FlowDetailState {
  /**
   * `null` while a fetch is in flight — including immediately after switching nodes.
   * `undefined` means no node is selected, so a closed drawer is never drawn as "loading".
   */
  detail: FactoryFlowDetail | null | undefined
  /**
   * Set on a failed fetch, alongside {@link detail} staying `null`. Distinct from both other
   * states on purpose, following the same pattern as this page's own top-level `error`: a
   * detail panel that cannot be read must render as a failure, not as a permanent, silent
   * spinner indistinguishable from one still in flight, and not as the empty-list copy either —
   * that would misreport a broken fetch as a node with genuinely nothing to show.
   */
  error: string | null
}

/** Fetches the selected node's recent work whenever the selection changes. */
function useFlowDetail(nodeKey: string | null): FlowDetailState {
  const { getAccessToken } = useAuth()
  const [detail, setDetail] = useState<FactoryFlowDetail | null | undefined>(undefined)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!nodeKey) {
      setDetail(undefined)
      setError(null)
      return undefined
    }
    setDetail(null)
    setError(null)
    let cancelled = false
    void (async () => {
      try {
        const result = await fetchFactoryFlowDetail(getAccessToken, nodeKey)
        if (!cancelled) setDetail(result)
      } catch (reason) {
        if (!cancelled) {
          setError(reason instanceof Error ? reason.message : 'Could not load recent runs')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [nodeKey, getAccessToken])

  return { detail, error }
}

export function SoftwareFactoryAdmin() {
  const { getAccessToken } = useAuth()
  const [status, setStatus] = useState<SoftwareFactoryStatus | null>(null)
  const [flow, setFlow] = useState<FactoryFlow | null>(null)
  const [selectedNode, setSelectedNode] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [run, setRun] = useState<ActiveRun | null>(null)
  const [reviewPullNumber, setReviewPullNumber] = useState('')
  const [pullNumber, setPullNumber] = useState('')
  const [deployConfirmation, setDeployConfirmation] = useState('')
  const [confirmBackup, setConfirmBackup] = useState(false)
  const [refreshMs, setRefreshMs] = useState<number | null>(null)

  useRunProgress(run, setRun)
  const { detail: selectedNodeDetail, error: selectedNodeDetailError } = useFlowDetail(selectedNode)

  // A ref, not state: state would re-run the interval effect below and reset its timer on every
  // load, which would make a slow response push its own next tick out rather than being skipped.
  const loadInFlight = useRef(false)

  const load = useCallback(async () => {
    try {
      loadInFlight.current = true
      setLoading(true)
      setError(null)
      const [nextStatus, nextFlow] = await Promise.all([
        fetchSoftwareFactoryStatus(getAccessToken),
        fetchFactoryFlow(getAccessToken),
      ])
      setStatus(nextStatus)
      setFlow(nextFlow)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not load factory status')
    } finally {
      setLoading(false)
      loadInFlight.current = false
    }
  }, [getAccessToken])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (refreshMs === null) return undefined
    const timer = setInterval(() => {
      // The interval is a floor on spacing, not a promise of one request per tick: a slow
      // response must not queue a second request behind the first.
      if (loadInFlight.current) return
      void load()
    }, refreshMs)
    return () => clearInterval(timer)
  }, [refreshMs, load])

  const modules = useMemo(
    () => new Map<string, FactoryModuleStatus>(status?.modules.map((module) => [module.key, module]) ?? []),
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

  /**
   * The manual controls offered for a node, inside its drawer.
   *
   * A total switch rather than a chain ending in a fallthrough: the previous `actionFor` returned
   * "Dry run / backup" for anything it did not recognise, silently mislabelling a newly added
   * module rather than failing visibly. Nodes with no manual action of their own render nothing.
   */
  const actionPanelFor = (key: string): React.ReactNode => {
    switch (key) {
      case 'codereview':
        return (
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
        )
      case 'feedback':
        return (
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
        )
      case 'cvefix':
        return (
          <ActionPanel title="Vulnerability report" description="Scan Dependency-Track and create or update one Linear ticket containing all current CVEs.">
            <button
              className="admin-btn admin-btn--primary"
              disabled={!modules.get('cvefix')?.ready || pending !== null}
              onClick={() => void start('cvefix', () => startVulnerabilityScan(getAccessToken))}
              type="button"
            ><ShieldAlert size={16} /> Scan now</button>
          </ActionPanel>
        )
      case 'logwatch':
        return (
          // Labels are deliberately more specific than the panel needs. "Dry run" alone collides
          // with the platform-backup control and "Scan now" with the vulnerability one, and the
          // accessible name is all a screen reader gets - the panel heading that disambiguates
          // them visually is not part of it.
          <ActionPanel title="Log watch" description="Scan production logs for recurring errors and file each distinct problem in Linear. A dry run reports what it would file and creates nothing.">
            <div className="factory-console__button-row">
              <button
                className="admin-btn"
                disabled={!modules.get('logwatch')?.ready || pending !== null}
                onClick={() => void start('logwatch-dry', () => startLogWatchScan(getAccessToken, true))}
                type="button"
              >Dry run scan</button>
              <button
                className="admin-btn admin-btn--primary"
                disabled={!modules.get('logwatch')?.ready || pending !== null}
                onClick={() => void start('logwatch', () => startLogWatchScan(getAccessToken, false))}
                type="button"
              ><ScrollText size={16} /> Scan logs now</button>
            </div>
          </ActionPanel>
        )
      case 'platformbackup':
        return (
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
        )
      case 'deploy':
        return (
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
        )
      case 'linear':
      case 'pull-request':
      case 'main':
      case 'production':
      case 'agent-setup':
      case 'build':
        return null
      default:
        return null
    }
  }

  if (loading && !status) return <div className="admin-loading">Loading Software Factory…</div>

  const selectedFlowNode = flow?.nodes.find((node) => node.key === selectedNode) ?? null

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
        <div className="factory-console__header-actions">
          <RefreshInterval value={refreshMs} onChange={setRefreshMs} />
          <button className="admin-btn" disabled={loading} onClick={() => void load()} type="button">
            <RefreshCw className={loading ? 'factory-console__spin' : ''} size={16} /> Refresh
          </button>
        </div>
      </div>

      <div className="factory-console__service-strip" aria-label="Factory service reachability">
        <ServiceState label="Factory" reachable={status?.factoryReachable ?? false} />
        <ServiceState label="Deployer" reachable={status?.deployerReachable ?? false} />
        <span className="factory-console__commit">production {shortCommit}</span>
      </div>

      {error && <div className="admin-error-banner"><AlertCircle size={16} /> {error}</div>}
      {run && <RunBanner run={run} />}

      {flow && (
        <FactoryFlowGraph flow={flow} selected={selectedNode} onSelect={setSelectedNode} />
      )}

      <FactoryNodeDrawer
        node={selectedFlowNode}
        module={selectedNode ? modules.get(selectedNode) ?? null : null}
        onClose={() => setSelectedNode(null)}
        detail={selectedNodeDetail}
        detailError={selectedNodeDetailError}
      >
        {selectedNode && actionPanelFor(selectedNode)}
      </FactoryNodeDrawer>
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
