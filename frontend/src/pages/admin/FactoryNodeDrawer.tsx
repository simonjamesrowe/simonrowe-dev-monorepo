import { useEffect } from 'react'
import { X } from 'lucide-react'

import type {
  FactoryFlowDetail, FactoryFlowNode, FactoryModuleStatus, FactoryNodeHealth,
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

export function FactoryNodeDrawer(
  { node, module, onClose, detail, children }: {
    node: FactoryFlowNode | null
    module: FactoryModuleStatus | null
    onClose: () => void
    /**
     * The node's recent work. `null` means it has not loaded yet, distinct from an empty list
     * that genuinely found no runs — the two must never read the same, or a failed fetch looks
     * exactly like a quiet module.
     */
    detail?: FactoryFlowDetail | null
    children?: React.ReactNode
  },
) {
  useEffect(() => {
    if (!node) return undefined
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [node, onClose])

  if (!node) return null

  const note = NODE_NOTES[node.key]
  return (
    <aside
      className="factory-drawer"
      role="dialog"
      aria-modal="true"
      aria-labelledby="factory-drawer-title"
    >
      <header className="factory-drawer__header">
        <h2 id="factory-drawer-title">{node.label}</h2>
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
        <dl className="factory-drawer__module">
          <div><dt>Task queue</dt><dd>{module.taskQueue}</dd></div>
          <div><dt>Trigger</dt><dd>{module.trigger}</dd></div>
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
      )}

      {detail !== undefined && (
        <div className="factory-drawer__runs">
          <h3>Recent runs</h3>
          {detail === null ? (
            <p>Loading…</p>
          ) : detail.items.length === 0 ? (
            <p>No runs in the last 30 days.</p>
          ) : (
            <ol>
              {detail.items.map((item) => (
                <li key={item.id}>
                  {item.url ? (
                    <a href={item.url} target="_blank" rel="noreferrer">{item.title}</a>
                  ) : (
                    <span>{item.title}</span>
                  )}
                  {' — '}
                  <span className="factory-drawer__run-status">{item.status}</span>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}

      {children}
    </aside>
  )
}
