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
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k, { label: k })))}
      selected={null} onSelect={vi.fn()} />)

    const labels = screen.getAllByRole('button').map((b) => b.getAttribute('data-node-key'))
    expect(labels).toEqual(FACTORY_FLOW_ORDER)
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

  it('draws the two reciprocal fast-loop edges with distinguishable geometry', () => {
    // pull-request -> codereview and codereview -> pull-request share the same two fixed
    // endpoints. Two straight lines between the same points are geometrically identical with
    // the ends swapped, so they would render as a single segment instead of two directed edges
    // for the fast loop.
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

    const paths = container.querySelectorAll('.factory-flow__edge')
    expect(paths).toHaveLength(2)
    const geometries = Array.from(paths).map((p) => p.getAttribute('d'))
    expect(geometries[0]).not.toEqual(geometries[1])
  })
})
