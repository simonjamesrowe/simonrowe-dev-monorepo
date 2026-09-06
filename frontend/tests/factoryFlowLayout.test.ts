import { describe, expect, it } from 'vitest'
import {
  NODE_HALF_HEIGHT, NODE_HALF_WIDTH, NODE_POSITIONS, edgePath,
} from '../src/pages/admin/factoryFlowLayout'
import type { FactoryFlowEdge } from '../src/services/softwareFactoryApi'

/**
 * The node buttons render opaque, on top of this SVG canvas (see FactoryFlowGraph.tsx), so an
 * edge whose rendered endpoint lands inside a node's box is invisible under it regardless of how
 * its `marker-end` arrowhead is styled — this is the exact defect that shipped once already
 * ("no arrowhead is visible") and that no earlier test caught, because every earlier assertion
 * only inspected the `d` string's shape (endpoints textually present, control point on the right
 * side), never whether the drawn coordinates actually clear the destination node.
 *
 * Node half-extents are re-exported from factoryFlowLayout.ts rather than duplicated here as
 * magic numbers, so this test tracks the same measured values `edgePath` trims against.
 */
function outsideBox(point: { x: number; y: number }, center: { x: number; y: number }): boolean {
  return Math.abs(point.x - center.x) > NODE_HALF_WIDTH
    || Math.abs(point.y - center.y) > NODE_HALF_HEIGHT
}

function endpointsOf(d: string): { start: { x: number; y: number }; end: { x: number; y: number } } {
  const move = d.match(/^M ([-\d.]+) ([-\d.]+)/)
  const rest = d.match(/(?:L|Q [-\d.]+ [-\d.]+) ([-\d.]+) ([-\d.]+)$/)
  if (!move || !rest) throw new Error(`could not parse path endpoints from: ${d}`)
  return {
    start: { x: Number(move[1]), y: Number(move[2]) },
    end: { x: Number(rest[1]), y: Number(rest[2]) },
  }
}

describe('edgePath', () => {
  it('trims a straight edge so both endpoints clear the source and destination node boxes', () => {
    const edges: FactoryFlowEdge[] = [
      { from: 'linear', to: 'build', label: 'files', loop: 'MAIN' },
    ]

    const d = edgePath(edges[0], edges)
    const { start, end } = endpointsOf(d)

    expect(outsideBox(start, NODE_POSITIONS.linear)).toBe(true)
    expect(outsideBox(end, NODE_POSITIONS.build)).toBe(true)

    // And it must still point the right way: from somewhere near the source, on toward the
    // destination, not clipped back past the midpoint or beyond the far node entirely.
    expect(start.x).toBeGreaterThan(NODE_POSITIONS.linear.x)
    expect(start.x).toBeLessThan(NODE_POSITIONS.build.x)
    expect(end.x).toBeGreaterThan(NODE_POSITIONS.linear.x)
    expect(end.x).toBeLessThan(NODE_POSITIONS.build.x)
  })

  it('trims both reciprocal curves so their endpoints clear the pull-request and codereview boxes', () => {
    // The reciprocal pair is the one that survived a full review round rendering as a single
    // overlapping curve, and separately the one hardest to trim correctly since the curve --
    // not the straight line between centres -- is what has to clear each node.
    const edges: FactoryFlowEdge[] = [
      { from: 'pull-request', to: 'codereview', label: 'push webhook', loop: 'FAST' },
      { from: 'codereview', to: 'pull-request', label: 'findings and check run', loop: 'FAST' },
    ]

    for (const edge of edges) {
      const d = edgePath(edge, edges)
      const { start, end } = endpointsOf(d)
      const fromCenter = NODE_POSITIONS[edge.from]
      const toCenter = NODE_POSITIONS[edge.to]

      expect(outsideBox(start, fromCenter)).toBe(true)
      expect(outsideBox(end, toCenter)).toBe(true)
    }
  })

  it('still bows the two reciprocal edges to opposite sides of the line between them after trimming', () => {
    // Regression guard carried over from the pre-trim implementation: trimming the rendered
    // endpoints must not touch the control-point maths that keeps the two curves visually apart.
    const edges: FactoryFlowEdge[] = [
      { from: 'pull-request', to: 'codereview', label: 'push webhook', loop: 'FAST' },
      { from: 'codereview', to: 'pull-request', label: 'findings and check run', loop: 'FAST' },
    ]

    const controlPoints = edges.map((edge) => {
      const d = edgePath(edge, edges)
      const match = d.match(/Q ([-\d.]+) ([-\d.]+)/)
      if (!match) throw new Error(`expected a curved path with a control point, got: ${d}`)
      return { x: Number(match[1]), y: Number(match[2]) }
    })

    // pull-request and codereview share an x in NODE_POSITIONS, so the line between them is
    // vertical: opposite sides means the control points straddle that x. Derived rather than
    // hard-coded, so moving the layout cannot leave this comparing against a stale axis.
    const [a, b] = controlPoints
    const midline = NODE_POSITIONS['pull-request'].x
    expect(NODE_POSITIONS.codereview.x).toBe(midline)
    expect(Math.sign(a.x - midline)).not.toBe(0)
    expect(Math.sign(a.x - midline)).toBe(-Math.sign(b.x - midline))
  })
})
