import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FactoryFlowGraph } from '../src/pages/admin/FactoryFlowGraph'
import { FACTORY_FLOW_ORDER } from '../src/pages/admin/factoryFlowLayout'
import type { FactoryFlow, FactoryFlowEdge, FactoryFlowNode } from '../src/services/softwareFactoryApi'

const node = (key: string, over: Partial<FactoryFlowNode> = {}): FactoryFlowNode => ({
  key, kind: 'MODULE', band: 'OBSERVE', label: key,
  counts: { inFlight: 0, ok24h: 0, failed24h: 0 }, health: 'READY', diagnostic: null, ...over,
})

const flow = (nodes: FactoryFlowNode[], edges: FactoryFlowEdge[] = []): FactoryFlow => ({
  fetchedAt: '2026-09-04T10:00:00Z', nodes, edges,
})

describe('FactoryFlowGraph', () => {
  it('renders every node as a button so the graph is keyboard navigable', () => {
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k)))}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getAllByRole('button')).toHaveLength(FACTORY_FLOW_ORDER.length)
  })

  it('orders the buttons along the main loop, not by band', () => {
    // Tab order is the only traversal a keyboard user gets. Following the ring is what makes the
    // diagram legible without sight of the SVG.
    //
    // The fixture is deliberately built in REVERSE of FACTORY_FLOW_ORDER, not in the same order
    // as the assertion: a component that simply echoed `flow.nodes` verbatim (ignoring the order
    // constant entirely) would previously have passed this test by coincidence, since the fixture
    // and the expectation were built from the identical `.map()` call.
    const shuffled = [...FACTORY_FLOW_ORDER].reverse()
    render(<FactoryFlowGraph flow={flow(shuffled.map((k) => node(k, { label: k })))}
      selected={null} onSelect={vi.fn()} />)

    const labels = screen.getAllByRole('button').map((b) => b.getAttribute('data-node-key'))
    expect(labels).toEqual(FACTORY_FLOW_ORDER)
  })

  it('appends a node FACTORY_FLOW_ORDER does not name rather than dropping it', () => {
    // An eighth module added on the Java side without a matching frontend entry must still be
    // reachable: the Java topology test already fails the build for the reverse omission, and
    // silently filtering an unknown key out here would move the identical failure mode one layer
    // up the stack with every existing test still green (both build their fixture FROM
    // FACTORY_FLOW_ORDER, so neither could ever see a node missing from it).
    render(<FactoryFlowGraph
      flow={flow([...FACTORY_FLOW_ORDER.map((k) => node(k)), node('new-module', { label: 'New module' })])}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: /New module/ })).toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(FACTORY_FLOW_ORDER.length + 1)
  })

  it('hides the decorative svg from assistive technology', () => {
    const { container } = render(
      <FactoryFlowGraph flow={flow([node('logwatch')])} selected={null} onSelect={vi.fn()} />)

    expect(container.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('gives every node a unique accessible name', () => {
    // "Dry run" collided with platform backup once already. The accessible name is all a screen
    // reader gets, and two identical ones make the graph unusable without sight of it.
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k, { label: k })))}
      selected={null} onSelect={vi.fn()} />)

    const names = screen.getAllByRole('button').map((b) => b.textContent?.trim())
    expect(new Set(names).size).toBe(names.length)
  })

  it('describes the build node\'s count as waiting tickets, not "in flight"', () => {
    // build's counts are the Linear backlog waiting for it, read verbatim from the same
    // NodeCounts the linear node itself shows. The generic "in flight" wording would say things
    // are RUNNING on a node whose entire point is that nothing is - visible on every normal page
    // load where any ticket is open.
    render(<FactoryFlowGraph
      flow={flow([node('build', { label: 'Build agent', health: 'OFFLINE',
        counts: { inFlight: 7, ok24h: 0, failed24h: 0 } })])}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: /7 waiting/ })).toBeInTheDocument()
    expect(screen.queryByText(/in flight/)).not.toBeInTheDocument()
  })

  it('reports a null count as unknown rather than as zero', () => {
    render(<FactoryFlowGraph
      flow={flow([node('logwatch', { counts: null, health: 'UNAVAILABLE' })])}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: /unknown/i })).toBeInTheDocument()
  })

  it('calls back with the node key when one is activated', async () => {
    const onSelect = vi.fn()
    render(<FactoryFlowGraph flow={flow([node('logwatch', { label: 'Log watch' })])}
      selected={null} onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('button', { name: /Log watch/ }))

    expect(onSelect).toHaveBeenCalledWith('logwatch')
  })

  it('marks the selected node as pressed', () => {
    render(<FactoryFlowGraph flow={flow([node('logwatch', { label: 'Log watch' })])}
      selected="logwatch" onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: /Log watch/ }))
      .toHaveAttribute('aria-pressed', 'true')
  })

  it('renders an explanatory message instead of a silently empty region when there are no nodes', () => {
    // FactoryAdminService.flow() returns 200 with nodes: [] when neither backing container is
    // reachable. Rendering the (then-empty) svg/ul would look identical to "the factory has no
    // nodes" — this is the failure mode the whole feature exists to avoid, so it must say plainly
    // that the graph could not be drawn.
    const { container } = render(
      <FactoryFlowGraph flow={flow([])} selected={null} onSelect={vi.fn()} />)

    expect(screen.getByText(
      /flow diagram could not be drawn because Software Factory could not be reached/i,
    )).toBeInTheDocument()
    expect(container.querySelectorAll('.factory-flow__node').length).toBe(0)
    expect(container.querySelector('svg.factory-flow__canvas')).not.toBeInTheDocument()
  })

  it('still renders the diagram, with no empty-state message, when nodes are present', () => {
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k)))}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getAllByRole('button')).toHaveLength(FACTORY_FLOW_ORDER.length)
    expect(screen.queryByText(/could not be drawn/i)).not.toBeInTheDocument()
  })

  it('bows the two reciprocal fast-loop edges to opposite sides of the line between them', () => {
    // pull-request -> codereview and codereview -> pull-request share the same two fixed
    // endpoints. Two straight lines between the same points are geometrically identical with
    // the ends swapped, so they would render as a single segment instead of two directed edges
    // for the fast loop. A curve is not enough either: naively negating the direction vector
    // when deriving the perpendicular offset, alongside a sign that also flips with direction,
    // cancels out and produces the SAME control point for both edges — a curve traced backwards
    // is pixel-identical to the curve traced forwards. So this test does not just check the `d`
    // strings differ (that is true trivially, since the endpoints are textually swapped even
    // when the curve itself coincides) — it extracts each curve's actual control point and
    // requires them to land on opposite sides of the straight line joining the endpoints.
    const edges: FactoryFlowEdge[] = [
      { from: 'pull-request', to: 'codereview', label: 'push webhook', loop: 'FAST' },
      { from: 'codereview', to: 'pull-request', label: 'findings and check run', loop: 'FAST' },
    ]

    const { container } = render(
      <FactoryFlowGraph
        flow={flow([node('pull-request'), node('codereview')], edges)}
        selected={null} onSelect={vi.fn()}
      />,
    )

    const paths = Array.from(container.querySelectorAll('.factory-flow__edge'))
    expect(paths).toHaveLength(2)

    const controlPoints = paths.map((p) => {
      const d = p.getAttribute('d') ?? ''
      const match = d.match(/Q ([-\d.]+) ([-\d.]+)/)
      if (!match) throw new Error(`expected a curved path with a control point, got: ${d}`)
      return { x: Number(match[1]), y: Number(match[2]) }
    })

    expect(controlPoints[0]).not.toEqual(controlPoints[1])

    // pull-request and codereview are both at x=480 (NODE_POSITIONS), so the line between them
    // is vertical: opposite sides of it means the control points' x coordinates straddle 480,
    // one above and one below, never both on the same side and never sitting on the line itself.
    const [a, b] = controlPoints
    expect(Math.sign(a.x - 480)).not.toBe(0)
    expect(Math.sign(a.x - 480)).toBe(-Math.sign(b.x - 480))
  })
})
