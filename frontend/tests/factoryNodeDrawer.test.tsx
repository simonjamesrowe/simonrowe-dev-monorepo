import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FactoryNodeDrawer } from '../src/pages/admin/FactoryNodeDrawer'
import type { FactoryFlowNode } from '../src/services/softwareFactoryApi'

const node: FactoryFlowNode = {
  key: 'logwatch', kind: 'MODULE', band: 'OBSERVE', label: 'Log watch',
  counts: { inFlight: 1, ok24h: 2, failed24h: 0 }, health: 'DEGRADED',
  diagnostic: 'Enabled but not usable: GRAFANA_CLOUD_LOKI_ENDPOINT is unset',
}

describe('FactoryNodeDrawer', () => {
  it('renders nothing when no node is selected', () => {
    const { container } = render(
      <FactoryNodeDrawer node={null} module={null} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('names the node and shows its diagnostic', () => {
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} />)
    expect(screen.getByRole('heading', { name: 'Log watch' })).toBeInTheDocument()
    expect(screen.getByText(/GRAFANA_CLOUD_LOKI_ENDPOINT is unset/)).toBeInTheDocument()
  })

  it('renders counts unknown rather than zero when the source could not be read', () => {
    // Null means the source could not be read; zero means nothing happened. They send an
    // operator to different places, so they must never read the same.
    render(<FactoryNodeDrawer
      node={{ ...node, counts: null, health: 'UNAVAILABLE' }}
      module={null}
      onClose={vi.fn()}
    />)
    expect(screen.getByText(/counts unknown/i)).toBeInTheDocument()
    expect(screen.queryByText(/^0 /)).not.toBeInTheDocument()
  })

  it('explains why platform backup has no edges', () => {
    // Otherwise it reads as an oversight rather than as a statement.
    render(<FactoryNodeDrawer node={{ ...node, key: 'platformbackup', label: 'Platform backup', band: 'UTILITY' }}
      module={null} onClose={vi.fn()} />)
    expect(screen.getByText(/participates in no loop/i)).toBeInTheDocument()
  })

  it('explains that the build agent is not yet staffed', () => {
    render(<FactoryNodeDrawer node={{ ...node, key: 'build', label: 'Build agent', health: 'IDLE' }}
      module={null} onClose={vi.fn()} />)
    expect(screen.getByText(/not yet running/i)).toBeInTheDocument()
  })

  it('closes on the close control', async () => {
    const onClose = vi.fn()
    render(<FactoryNodeDrawer node={node} module={null} onClose={onClose} />)
    await userEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(onClose).toHaveBeenCalled()
  })

  it('closes on Escape', async () => {
    const onClose = vi.fn()
    render(<FactoryNodeDrawer node={node} module={null} onClose={onClose} />)
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('renders the actions passed as children', () => {
    render(
      <FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}>
        <button type="button">Scan logs now</button>
      </FactoryNodeDrawer>,
    )
    expect(screen.getByRole('button', { name: 'Scan logs now' })).toBeInTheDocument()
  })

  it('renders module details when a module is given', () => {
    render(
      <FactoryNodeDrawer
        node={node}
        module={{
          key: 'logwatch',
          displayName: 'Log watch',
          configured: true,
          taskQueue: 'logwatch',
          workflowPollers: 1,
          activityPollers: 1,
          trigger: 'manual',
          schedule: null,
          missingPrerequisites: ['GRAFANA_CLOUD_LOKI_ENDPOINT is not set'],
          ready: false,
          diagnostic: null,
        }}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('logwatch', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getByText(/GRAFANA_CLOUD_LOKI_ENDPOINT is not set/)).toBeInTheDocument()
  })

  it('lists the recent runs it was given', () => {
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}
      detail={{ nodeKey: 'logwatch', items: [
        { id: 'logwatch-2', title: 'logwatch-2', status: 'COMPLETED', at: null, url: null },
      ] }} />)
    expect(screen.getByText('logwatch-2')).toBeInTheDocument()
  })

  it('says so plainly when a node has no recent work', () => {
    // An empty list and a list that failed to load must not look the same.
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}
      detail={{ nodeKey: 'logwatch', items: [] }} />)
    expect(screen.getByText(/No runs in the last 30 days/i)).toBeInTheDocument()
  })

  it('says so plainly when the detail has not loaded', () => {
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} detail={null} />)
    expect(screen.getByText(/Loading/i)).toBeInTheDocument()
  })

  it('renders no recent-work section at all when no detail was requested', () => {
    // Distinct from both other states: a caller that never asked for detail must not imply a
    // load is in progress.
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} />)
    expect(screen.queryByText(/Recent runs/i)).not.toBeInTheDocument()
  })
})
