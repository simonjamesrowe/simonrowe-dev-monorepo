import type { FactoryFlow, FactoryFlowNode, FactoryNodeHealth } from '../../services/softwareFactoryApi'
import { FACTORY_FLOW_ORDER, NODE_POSITIONS, edgePath } from './factoryFlowLayout'

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

const VIEWBOX_WIDTH = 1000
const VIEWBOX_HEIGHT = 520

/** A missing count is not a zero count, and the two must not read the same. */
function countSummary(node: FactoryFlowNode): string {
  if (node.counts === null) return 'counts unknown'
  const { inFlight, ok24h, failed24h } = node.counts
  return `${inFlight} in flight, ${ok24h} ok, ${failed24h} failed in 24h`
}

export function FactoryFlowGraph(
  { flow, selected, onSelect }:
  { flow: FactoryFlow; selected: string | null; onSelect: (key: string) => void },
) {
  const byKey = new Map(flow.nodes.map((node) => [node.key, node]))
  const ordered = FACTORY_FLOW_ORDER
    .map((key) => byKey.get(key))
    .filter((node): node is FactoryFlowNode => node !== undefined)

  return (
    <div className="factory-flow">
      <svg
        className="factory-flow__canvas"
        viewBox={`0 0 ${VIEWBOX_WIDTH} ${VIEWBOX_HEIGHT}`}
        aria-hidden="true"
        focusable="false"
      >
        <defs>
          <marker
            id="factory-flow-arrow"
            viewBox="0 0 10 10"
            refX="8"
            refY="5"
            markerWidth="6"
            markerHeight="6"
            orient="auto-start-reverse"
          >
            <path d="M0,0 L10,5 L0,10 z" />
          </marker>
        </defs>
        {flow.edges.map((edge) => (
          <path
            key={`${edge.from}-${edge.to}`}
            className={`factory-flow__edge factory-flow__edge--${edge.loop.toLowerCase()}`}
            d={edgePath(edge, flow.edges)}
            markerEnd="url(#factory-flow-arrow)"
          />
        ))}
      </svg>
      <ul className="factory-flow__nodes">
        {ordered.map((node) => {
          const position = NODE_POSITIONS[node.key]
          return (
            <li
              key={node.key}
              className="factory-flow__node-slot"
              style={{
                '--factory-flow-x': `${((position?.x ?? 0) / VIEWBOX_WIDTH) * 100}%`,
                '--factory-flow-y': `${((position?.y ?? 0) / VIEWBOX_HEIGHT) * 100}%`,
              } as React.CSSProperties}
            >
              <button
                type="button"
                data-node-key={node.key}
                aria-pressed={selected === node.key}
                className={`factory-flow__node factory-flow__node--${node.health.toLowerCase()}`}
                onClick={() => onSelect(node.key)}
              >
                <span className="factory-flow__node-label">{node.label}</span>
                <span className="factory-flow__node-health">{HEALTH_LABELS[node.health]}</span>
                <span className="factory-flow__node-counts">{countSummary(node)}</span>
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
