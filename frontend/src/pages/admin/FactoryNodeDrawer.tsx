import { useEffect, useRef } from 'react'
import { AlertCircle, X } from 'lucide-react'

import type {
  FactoryFlowDetail, FactoryFlowNode, FactoryModuleStatus, FactoryNodeHealth,
  FactoryScheduleStatus,
} from '../../services/softwareFactoryApi'

const HEALTH_LABELS: Record<FactoryNodeHealth, string> = {
  READY: 'Ready',
  DEGRADED: 'Degraded',
  DISABLED: 'Disabled',
  UNAVAILABLE: 'Unknown',
  OFFLINE: 'Offline',
  IDLE: 'Idle',
  // This node has no owning module and no artifact source this container can read (only
  // `production` today) — the platform status page is where its real state lives.
  NOT_TRACKED: 'Not tracked',
}

/**
 * Why a node is the way it is, where that is not obvious from the graph.
 *
 * A total record rather than an if-chain with a fallthrough: the old console labelled any
 * unrecognised module "Dry run / backup" precisely because of a fallthrough, and an empty string
 * here is an explicit decision that a node needs no explanation.
 */
const NODE_NOTES: Record<string, string> = {
  platformbackup:
    'Platform backup participates in no loop, so it is drawn off the ring rather than on it. '
    + 'Placing it on the ring would imply a feedback path that does not exist.',
  build:
    'The build agent is declared but not yet running. It is designed to run on a developer '
    + 'machine rather than the production host, so this node is derived entirely from Linear and '
    + 'GitHub. Offline means work is waiting and nothing has picked it up; Idle means there is '
    + 'nothing waiting.',
  linear:
    'Linear is an artifact, and the badge on it is the health of the linear sink module. That '
    + 'module is the factory’s only activity-only task queue — nothing flows through '
    + 'it — so it is not drawn as a box of its own.',
  production:
    'Production state is reported by the platform status page rather than counted here.',
}

/** The heading, empty-state and unavailable-state copy for a node's recent-work list. */
interface RunListCopy {
  heading: string
  empty: string
  /**
   * Shown when {@code detail.items} is null - the source could not be read - rather than the
   * generic "Run history is not available from this console.", so a node whose heading is
   * "Open tickets" does not pair it with a sentence about "runs", the same noun mismatch Task 12
   * fixed between the heading and empty-state copy.
   */
  unavailable: string
}

/**
 * Module nodes list Temporal workflow executions, bounded by the namespace's 30-day retention —
 * "Recent runs" and "in the last 30 days" are both literally true there. The four artifact nodes
 * below have their own reader with no such window and nothing that is a "run": a ticket, a pull
 * request, a commit. Reusing the module wording for them stated a window that was never applied,
 * about a kind of thing that does not exist on that node — text that reads as data and is simply
 * wrong. A lookup keyed by node, not a second if-chain in the JSX: the console already had one
 * silent-fallthrough bug from an if-chain ending in a default (the old `actionFor`), which is why
 * `actionPanelFor` is a total switch and this is a total lookup with a safe module default.
 */
const RUN_LIST_COPY: Record<string, RunListCopy> = {
  linear: {
    heading: 'Open tickets',
    empty: 'No open tickets.',
    unavailable: 'Open tickets are not available from this console.',
  },
  'pull-request': {
    heading: 'Open pull requests',
    empty: 'No open pull requests.',
    unavailable: 'Open pull requests are not available from this console.',
  },
  main: {
    heading: 'Recent merges',
    empty: 'No recent merges.',
    unavailable: 'Recent merges are not available from this console.',
  },
  'agent-setup': {
    heading: 'Open pull requests',
    empty: 'No open pull requests.',
    unavailable: 'Open pull requests are not available from this console.',
  },
  // production and build have no run list of their own — no Temporal workflow, no artifact
  // reader — so their FlowDetail is always FlowDetail.empty(), never a "runs" list bounded by a
  // 30-day retention window. Falling through to the module default stated a window and a noun
  // ("runs") that never applied to either, on every normal page load.
  production: {
    heading: 'Recent activity',
    empty: 'Production has no activity of its own here — see the platform status page.',
    unavailable: 'Activity is not available from this console.',
  },
  build: {
    heading: 'Recent activity',
    empty: 'The build agent has no run history here — its waiting work is the counts above.',
    unavailable: 'Activity is not available from this console.',
  },
}

const DEFAULT_RUN_LIST_COPY: RunListCopy = {
  heading: 'Recent runs',
  empty: 'No runs in the last 30 days.',
  unavailable: 'Run history is not available from this console.',
}

const runListCopy = (nodeKey: string): RunListCopy =>
  RUN_LIST_COPY[nodeKey] ?? DEFAULT_RUN_LIST_COPY

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

/**
 * Elements a keyboard user can land on. Deliberately excludes `[tabindex="-1"]`, which is exactly
 * how the heading is made an initial-focus target without joining the Tab cycle.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

export function FactoryNodeDrawer(
  { node, module, onClose, detail, detailError, children }: {
    node: FactoryFlowNode | null
    module: FactoryModuleStatus | null
    onClose: () => void
    /**
     * The node's recent work. `null` means it has not loaded yet, distinct from an empty list
     * that genuinely found no runs — the two must never read the same, or a failed fetch looks
     * exactly like a quiet module.
     */
    detail?: FactoryFlowDetail | null
    /**
     * Set when the fetch behind `detail` failed. Takes precedence over `detail === null`: a
     * detail panel that could not be read must show an error, not a permanent, silent spinner.
     */
    detailError?: string | null
    children?: React.ReactNode
  },
) {
  const asideRef = useRef<HTMLElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)

  // Escape closes, and Tab is trapped inside the drawer while it is open: `aria-modal="true"` is
  // a promise to assistive technology that background content is inert, and a keyboard user who
  // could still Tab out to the other eleven graph nodes would make that promise false.
  useEffect(() => {
    if (!node) return undefined
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      const container = asideRef.current
      if (!container) return
      const focusable = Array.from(
        container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      )
      if (focusable.length === 0) {
        event.preventDefault()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [node, onClose])

  // Moves focus into the drawer whenever a (possibly different) node opens, having first
  // remembered whatever had focus — the node button that was just activated, via the browser's
  // own focus-follows-click — so it can be restored below.
  useEffect(() => {
    if (!node) return
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    headingRef.current?.focus()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-run only when the node identity changes, not on every prop tweak
  }, [node?.key])

  // Restores focus to the triggering node button exactly once, when the drawer actually closes —
  // not on every node switch, which would otherwise fight the browser's own focus-follows-click.
  const isOpen = node !== null
  useEffect(() => {
    if (!isOpen) return undefined
    return () => {
      previousFocusRef.current?.focus()
    }
  }, [isOpen])

  if (!node) return null

  const note = NODE_NOTES[node.key]
  return (
    <aside
      ref={asideRef}
      className="factory-drawer"
      role="dialog"
      aria-modal="true"
      aria-labelledby="factory-drawer-title"
    >
      <header className="factory-drawer__header">
        <h2 id="factory-drawer-title" ref={headingRef} tabIndex={-1}>{node.label}</h2>
        <button className="admin-btn" type="button" onClick={onClose} aria-label="Close details">
          <X size={16} />
        </button>
      </header>

      <p className={`factory-drawer__health factory-drawer__health--${node.health.toLowerCase()}`}>
        {HEALTH_LABELS[node.health]}
      </p>

      {node.counts === null ? (
        <p className="factory-drawer__counts">Counts unknown — the source could not be read.</p>
      ) : (
        <dl className="factory-drawer__counts">
          <div><dt>In flight</dt><dd>{node.counts.inFlight}</dd></div>
          <div><dt>Succeeded (24h)</dt><dd>{node.counts.ok24h}</dd></div>
          <div><dt>Failed (24h)</dt><dd>{node.counts.failed24h}</dd></div>
        </dl>
      )}

      {node.diagnostic && <p className="factory-drawer__diagnostic">{node.diagnostic}</p>}
      {note && <p className="factory-drawer__note">{note}</p>}

      {module && (
        <>
          <dl className="factory-drawer__module">
            <div>
              <dt>Configured</dt>
              <dd>{module.configured === null ? 'Unconfirmed' : module.configured ? 'On' : 'Off'}</dd>
            </div>
            <div><dt>Task queue</dt><dd>{module.taskQueue}</dd></div>
            <div><dt>Trigger</dt><dd>{module.trigger}</dd></div>
            {module.schedule && (
              <div><dt>Schedule</dt><dd>{scheduleSummary(module.schedule)}</dd></div>
            )}
            <div>
              <dt>Pollers</dt>
              <dd>
                {module.workflowPollers ?? '?'} workflow / {module.activityPollers ?? '?'} activity
              </dd>
            </div>
            {module.missingPrerequisites.length > 0 && (
              <div>
                <dt>Missing</dt>
                <dd>{module.missingPrerequisites.join('; ')}</dd>
              </div>
            )}
          </dl>
          {module.diagnostic && <p className="factory-drawer__diagnostic">{module.diagnostic}</p>}
        </>
      )}

      {detail !== undefined && (
        <div className="factory-drawer__runs">
          <h3>{runListCopy(node.key).heading}</h3>
          {detailError ? (
            <p className="admin-error-banner">
              <AlertCircle size={14} /> {detailError}
            </p>
          ) : detail === null ? (
            <p>Loading…</p>
          ) : detail.items == null ? (
            // Distinct from an empty list: this node's own source could not be read — an
            // artifact reader (Linear, GitHub) failing, or, for `deploy`/`platformbackup`, the
            // deployer itself being unreachable — not "nothing open". `== null` rather than
            // `=== null`: if the backend ever gained `@JsonInclude(NON_NULL)`, this would arrive
            // as `undefined` instead, and a strict check would fall through to the `.length`
            // read below and throw.
            <p className="admin-error-banner">
              <AlertCircle size={14} /> {runListCopy(node.key).unavailable}
            </p>
          ) : detail.items.length === 0 ? (
            <p>{runListCopy(node.key).empty}</p>
          ) : (
            <ol>
              {detail.items.map((item) => {
                // The id is rendered inside the link, not just alongside it, so the accessible
                // name of two items sharing a title (two pull requests both called "Fix typo")
                // still differs — an id span outside the anchor is invisible to a screen reader.
                const label = (
                  <>
                    {item.title} <span className="factory-drawer__run-id">({item.id})</span>
                  </>
                )
                return (
                  <li key={item.id}>
                    {item.url ? (
                      <a href={item.url} target="_blank" rel="noopener noreferrer">{label}</a>
                    ) : (
                      <span>{label}</span>
                    )}
                    {' — '}
                    <span className="factory-drawer__run-status">{item.status}</span>
                    {item.at && (
                      <>
                        {' · '}
                        <span className="factory-drawer__run-time">{formatTime(item.at)}</span>
                      </>
                    )}
                  </li>
                )
              })}
            </ol>
          )}
        </div>
      )}

      {children}
    </aside>
  )
}
