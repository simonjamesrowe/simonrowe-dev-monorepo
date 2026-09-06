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

/**
 * Half-extents of a `.factory-flow__node` button, in viewBox units, measured (not guessed) on the
 * running page: `svg.factory-flow__canvas.getBoundingClientRect()` against its
 * `viewBox="0 0 1000 520"` gives a uniform ~1.08 px per viewBox unit, and the widest connected
 * node's rendered box (`min-width: 7rem` plus a two-line label such as "Code review") measured
 * ~146 x 58 units — half-width 73, half-height 29. `platformbackup` renders taller still (a
 * wrapped three-line label) but is drawn on no edge at all
 * (see `FactoryFlowTopologyTest.leavesPlatformBackupOffTheRing`), so it is deliberately excluded
 * from this measurement rather than inflating every other node's trim for a box no line ever
 * touches.
 */
export const NODE_HALF_WIDTH = 73
export const NODE_HALF_HEIGHT = 29

/**
 * Extra clearance past the node's own border, in viewBox units. `factory-flow__marker--*` is
 * anchored near its own tip (`refX="8"` of a `markerWidth="10"` box, `markerUnits` defaulting to
 * `strokeWidth`), so roughly a fifth of its rendered length overshoots the path's endpoint in the
 * direction of travel — for the heaviest edge (`main`, stroke-width 3, a 30-unit marker) that is
 * ~6 units. Trimming the endpoint back by only that much would still land the visible tip right
 * on the border; this is comfortably larger so the whole arrowhead clears it with a visible gap.
 */
const EDGE_GAP = 3

type Point = { x: number; y: number }

/**
 * Where a straight ray from `center` toward `towards` first crosses the axis-aligned box centred
 * on `center` (the node's own half-extents, inflated by {@link EDGE_GAP}) — the point on (just
 * outside) the node's own perimeter, closest to `center`, along the line to the other endpoint.
 * Standard box/ray parametrisation: the ray leaves the box on whichever axis it reaches its
 * half-extent first. `Math.min(..., 1)` is a defensive clamp so two implausibly close nodes can
 * never push the trimmed point past the other node's centre and invert the line.
 */
function trimToBox(center: Point, towards: Point): Point {
  const dx = towards.x - center.x
  const dy = towards.y - center.y
  if (dx === 0 && dy === 0) return center
  const halfWidth = NODE_HALF_WIDTH + EDGE_GAP
  const halfHeight = NODE_HALF_HEIGHT + EDGE_GAP
  const tx = dx !== 0 ? halfWidth / Math.abs(dx) : Infinity
  const ty = dy !== 0 ? halfHeight / Math.abs(dy) : Infinity
  const t = Math.min(tx, ty, 1)
  return { x: center.x + dx * t, y: center.y + dy * t }
}

function quadraticPoint(p0: Point, control: Point, p1: Point, t: number): Point {
  const mt = 1 - t
  return {
    x: (mt * mt * p0.x) + (2 * mt * t * control.x) + (t * t * p1.x),
    y: (mt * mt * p0.y) + (2 * mt * t * control.y) + (t * t * p1.y),
  }
}

function isOutsideBox(point: Point, center: Point): boolean {
  const halfWidth = NODE_HALF_WIDTH + EDGE_GAP
  const halfHeight = NODE_HALF_HEIGHT + EDGE_GAP
  return Math.abs(point.x - center.x) > halfWidth || Math.abs(point.y - center.y) > halfHeight
}

const CURVE_TRIM_SAMPLES = 200

/**
 * Walks THIS edge's own quadratic — not the straight line between centres, which the curve
 * deliberately bows away from — from one end inward, and returns the first sampled point that
 * clears the given node's box. Called once per endpoint (`fromStart` true walks t: 0 -> 1 against
 * the source node's box, false walks t: 1 -> 0 against the destination's), so each end is trimmed
 * against the node it actually touches, using the curve it is actually drawn on. The control point
 * itself is never moved — only where the path is cut off along it — so the curve's bow direction
 * (which side of the straight line it lands on) is untouched by trimming.
 */
function trimCurveEnd(p0: Point, control: Point, p1: Point, center: Point, fromStart: boolean): Point {
  for (let i = 0; i <= CURVE_TRIM_SAMPLES; i += 1) {
    const t = fromStart ? i / CURVE_TRIM_SAMPLES : 1 - (i / CURVE_TRIM_SAMPLES)
    const point = quadraticPoint(p0, control, p1, t)
    if (isOutsideBox(point, center)) return point
  }
  // The two endpoints are always node centres, which start inside their own box (t=0 / t=1), so
  // this only falls through if two nodes sit implausibly close together; fall back to the centre
  // rather than throw.
  return fromStart ? p0 : p1
}

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
 *
 * Both the straight and curved forms render from the node's PERIMETER, not its centre: the node
 * buttons sit opaque on top of this SVG canvas, so a path ending at the destination centre buries
 * its `marker-end` arrowhead entirely under the button — invisible regardless of how the marker
 * itself is styled. Control points are still computed from the centres above (unchanged); only
 * the rendered start/end coordinates are pulled back to just outside each node's box.
 */
export function edgePath(edge: FactoryFlowEdge, edges: FactoryFlowEdge[]): string {
  const from = NODE_POSITIONS[edge.from]
  const to = NODE_POSITIONS[edge.to]
  if (!from || !to) return ''

  if (!hasReciprocal(edge, edges)) {
    const start = trimToBox(from, to)
    const end = trimToBox(to, from)
    return `M ${start.x} ${start.y} L ${end.x} ${end.y}`
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
  const control = { x: controlX, y: controlY }

  const start = trimCurveEnd(from, control, to, from, true)
  const end = trimCurveEnd(from, control, to, to, false)

  return `M ${start.x} ${start.y} Q ${controlX} ${controlY} ${end.x} ${end.y}`
}
