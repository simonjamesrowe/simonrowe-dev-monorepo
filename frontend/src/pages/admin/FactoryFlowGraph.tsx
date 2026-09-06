import { AlertCircle } from 'lucide-react'

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

/**
 * The build node's `counts` is the same Linear backlog `NodeCounts` the `linear` node itself
 * shows — see `FactoryFlowService.countsFor` — because the build agent runs on a machine this
 * console cannot reach, so its only signal is the ticket queue waiting for it. Reusing the
 * generic "in flight" wording there says "7 things are running" on a node whose entire point is
 * that nothing is: build's `inFlight` is open tickets waiting, not runs in progress.
 */
const BUILD_NODE_KEY = 'build'

/** A missing count is not a zero count, and the two must not read the same. */
function countSummary(node: FactoryFlowNode): string {
  // `== null` rather than `=== null`: if the backend record ever gained
  // `@JsonInclude(NON_NULL)`, a null counts would arrive as `undefined` instead of JSON `null`,
  // and a strict `=== null` check would fall through into the destructuring two lines down and
  // throw — degrading "could not read this" into a white screen instead of "counts unknown".
  if (node.counts == null) return 'counts unknown'
  const { inFlight, ok24h, failed24h } = node.counts
  if (node.key === BUILD_NODE_KEY) {
    return `${inFlight} waiting`
  }
  return `${inFlight} in flight, ${ok24h} ok, ${failed24h} failed in 24h`
}

/**
 * Nodes with no fixed layout position — an unknown key {@link FACTORY_FLOW_ORDER} does not name,
 * or one present there but missing from {@link NODE_POSITIONS} — still need a distinct spot to
 * render at. Defaulting every one of them to the literal origin would stack an undrawn second
 * node exactly on top of the first, hiding one behind the other; spreading them along the top
 * edge by index keeps each one visible and individually clickable instead.
 */
function fallbackPosition(index: number): { x: number; y: number } {
  return { x: 40 + ((index * 80) % (VIEWBOX_WIDTH - 80)), y: 20 }
}

export function FactoryFlowGraph(
  { flow, selected, onSelect }:
  { flow: FactoryFlow; selected: string | null; onSelect: (key: string) => void },
) {
  // `flow.nodes` empty is a THIRD state, distinct from a node we drew but could not measure
  // ("counts unknown") and a drawer list we could read and found empty ("not available"): here
  // the factory returned 200 with nothing to draw at all, because neither backing container was
  // reachable. Rendering the (then-empty) svg/ul below would silently present as "there is
  // nothing here" — the exact failure mode this whole feature exists to avoid — so say plainly
  // that the graph could not be drawn and that node health is unknown, not healthy.
  if (flow.nodes.length === 0) {
    return (
      <div className="factory-flow factory-flow--empty admin-error-banner">
        <AlertCircle size={16} />
        <p>
          The flow diagram could not be drawn because Software Factory could not be reached.
          Node health above and in the drawers is unknown, not healthy.
        </p>
      </div>
    )
  }

  const byKey = new Map(flow.nodes.map((node) => [node.key, node]))
  const ordered = FACTORY_FLOW_ORDER
    .map((key) => byKey.get(key))
    .filter((node): node is FactoryFlowNode => node !== undefined)
  // A node the factory reports that FACTORY_FLOW_ORDER does not name (an eighth module added on
  // the Java side without a matching frontend entry) is appended rather than dropped: the Java
  // topology test already fails the build for this case, and silently filtering it out here would
  // move the exact same failure mode one layer up the stack, invisible with every test green.
  const undrawn = flow.nodes.filter((node) => !FACTORY_FLOW_ORDER.includes(node.key))
  const rendered = [...ordered, ...undrawn]

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
        {rendered.map((node, index) => {
          const position = NODE_POSITIONS[node.key] ?? fallbackPosition(index)
          return (
            <li
              key={node.key}
              className="factory-flow__node-slot"
              style={{
                '--factory-flow-x': `${(position.x / VIEWBOX_WIDTH) * 100}%`,
                '--factory-flow-y': `${(position.y / VIEWBOX_HEIGHT) * 100}%`,
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
