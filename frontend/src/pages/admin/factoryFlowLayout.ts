import type { FactoryFlowEdge } from '../../services/softwareFactoryApi'

/**
 * Tab order, and therefore the order a screen reader reads the graph in.
 *
 * Deliberately the main loop rather than the bands: the ring is the thing being communicated, and
 * a keyboard user who cannot see the SVG gets it only from this sequence. Nodes off the ring
 * follow at the end.
 */
export const FACTORY_FLOW_ORDER = [
  'linear', 'build', 'pull-request', 'codereview', 'main', 'deploy', 'production',
  'logwatch', 'cvefix', 'feedback', 'agent-setup', 'platformbackup',
]

/** Fixed grid positions, in an arbitrary 1000x520 viewBox the SVG scales from. */
export const NODE_POSITIONS: Record<string, { x: number; y: number }> = {
  linear: { x: 120, y: 260 },
  build: { x: 300, y: 160 },
  'pull-request': { x: 480, y: 160 },
  codereview: { x: 480, y: 60 },
  main: { x: 660, y: 160 },
  deploy: { x: 820, y: 260 },
  production: { x: 660, y: 380 },
  logwatch: { x: 420, y: 400 },
  cvefix: { x: 660, y: 470 },
  feedback: { x: 300, y: 60 },
  'agent-setup': { x: 140, y: 60 },
  platformbackup: { x: 900, y: 440 },
}

/** How far a reciprocal edge's curve bows away from the straight line between its endpoints. */
const RECIPROCAL_CURVE_OFFSET = 18

function hasReciprocal(edge: FactoryFlowEdge, edges: FactoryFlowEdge[]): boolean {
  return edges.some((other) => other.from === edge.to && other.to === edge.from)
}

/**
 * SVG path `d` for one edge.
 *
 * A reciprocal pair — two edges connecting the same two nodes in opposite directions, such as the
 * fast loop's "push webhook" / "findings and check run" pair between `pull-request` and
 * `codereview` — share identical endpoints. Drawn as straight lines they are geometrically
 * identical with the ends swapped, so they render as one indistinguishable segment instead of two
 * directed edges. Detecting the reciprocal generically (rather than special-casing those two node
 * keys) means any future reciprocal pair gets the same treatment for free.
 *
 * The offset direction must come from something that does NOT flip when the edge's own direction
 * flips, or the two sides of a reciprocal pair cancel out: negating (dx, dy) by swapping `from`
 * and `to`, and then also flipping `sign` from `edge.from < edge.to`, cancel each other exactly —
 * both edges land on the identical control point, i.e. the identical curve traced backwards,
 * which renders as the original one-segment defect merely curved instead of straight. Fixed by
 * computing the perpendicular from a canonical, unordered direction for the pair (the
 * lexicographically smaller key to the larger one) so it is the same for both edges, and then
 * choosing which side *this* edge's control point lands on based on whether `edge.from` is that
 * canonical smaller key. That is independent of which of the two edges is being drawn, so the two
 * sides are guaranteed to differ rather than merely likely to.
 */
export function edgePath(edge: FactoryFlowEdge, edges: FactoryFlowEdge[]): string {
  const from = NODE_POSITIONS[edge.from]
  const to = NODE_POSITIONS[edge.to]
  if (!from || !to) return ''

  if (!hasReciprocal(edge, edges)) {
    return `M ${from.x} ${from.y} L ${to.x} ${to.y}`
  }

  const canonicalFromKey = edge.from < edge.to ? edge.from : edge.to
  const canonicalToKey = edge.from < edge.to ? edge.to : edge.from
  const canonicalFrom = NODE_POSITIONS[canonicalFromKey]
  const canonicalTo = NODE_POSITIONS[canonicalToKey]

  const dx = canonicalTo.x - canonicalFrom.x
  const dy = canonicalTo.y - canonicalFrom.y
  const length = Math.hypot(dx, dy) || 1
  const perpX = -dy / length
  const perpY = dx / length
  const sign = edge.from === canonicalFromKey ? 1 : -1

  const offsetX = perpX * RECIPROCAL_CURVE_OFFSET * sign
  const offsetY = perpY * RECIPROCAL_CURVE_OFFSET * sign
  const controlX = (from.x + to.x) / 2 + offsetX
  const controlY = (from.y + to.y) / 2 + offsetY

  return `M ${from.x} ${from.y} Q ${controlX} ${controlY} ${to.x} ${to.y}`
}
