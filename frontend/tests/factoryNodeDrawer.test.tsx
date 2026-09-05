import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FactoryNodeDrawer } from '../src/pages/admin/FactoryNodeDrawer'
import type { FactoryFlowNode, FactoryModuleStatus } from '../src/services/softwareFactoryApi'

const node: FactoryFlowNode = {
  key: 'logwatch', kind: 'MODULE', band: 'OBSERVE', label: 'Log watch',
  counts: { inFlight: 1, ok24h: 2, failed24h: 0 }, health: 'DEGRADED',
  diagnostic: 'Enabled but not usable: GRAFANA_CLOUD_LOKI_ENDPOINT is unset',
}

const baseModule: FactoryModuleStatus = {
  key: 'logwatch',
  displayName: 'Log watch',
  configured: true,
  taskQueue: 'logwatch',
  workflowPollers: 1,
  activityPollers: 1,
  trigger: 'manual',
  schedule: null,
  missingPrerequisites: [],
  ready: true,
  diagnostic: null,
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

  it('shows an error rather than a permanent spinner when the fetch behind detail failed', () => {
    // A failed fetch must not be indistinguishable from one still loading: both a network
    // error and a genuinely empty list would otherwise render nothing more specific than
    // "Loading…" forever.
    render(<FactoryNodeDrawer
      node={node}
      module={null}
      onClose={vi.fn()}
      detail={null}
      detailError="Could not load recent runs"
    />)
    expect(screen.getByText('Could not load recent runs')).toBeInTheDocument()
    expect(screen.queryByText(/^Loading/i)).not.toBeInTheDocument()
  })

  it('shows the id alongside a human-readable title', () => {
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}
      detail={{ nodeKey: 'logwatch', items: [
        {
          id: 'logwatch-2', title: 'Log watch · 4 Sep, 20:14', status: 'COMPLETED',
          at: null, url: null,
        },
      ] }} />)
    expect(screen.getByText('Log watch · 4 Sep, 20:14')).toBeInTheDocument()
    expect(screen.getByText('(logwatch-2)')).toBeInTheDocument()
  })

  it('shows the configured state and schedule summary, not just pollers', () => {
    // "Configured" and "Schedule" previously lived in the module rail (On/Off/Unconfirmed,
    // Active/Paused/Absent) and vanished when the rail was deleted. An operator cannot tell
    // whether a schedule is paused, or when it next runs, from pollers and a task queue alone.
    render(
      <FactoryNodeDrawer
        node={node}
        module={{
          ...baseModule,
          configured: null,
          schedule: {
            scheduleId: 'logwatch-nightly',
            exists: true,
            paused: false,
            overlapPolicy: 'SKIP',
            previousActionAt: null,
            nextActionAt: '2026-09-05T02:00:00Z',
            runningActions: 0,
            diagnostic: null,
          },
        }}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('Unconfirmed')).toBeInTheDocument()
    expect(screen.getByText(/Active · next/)).toBeInTheDocument()
  })

  it('omits the "next" clause for a paused schedule with no next action', () => {
    // A paused schedule has no next action, and neither does one Temporal has not computed yet.
    // Appending it unconditionally produced "Active · next Not recorded".
    render(
      <FactoryNodeDrawer
        node={node}
        module={{
          ...baseModule,
          schedule: {
            scheduleId: 'logwatch-nightly',
            exists: true,
            paused: true,
            overlapPolicy: 'SKIP',
            previousActionAt: null,
            nextActionAt: null,
            runningActions: 0,
            diagnostic: null,
          },
        }}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('Paused')).toBeInTheDocument()
    expect(screen.queryByText(/next/)).not.toBeInTheDocument()
  })

  it('renders the module diagnostic distinctly from missing prerequisites', () => {
    // A module can be disabled by configuration with nothing missing to list — the old test for
    // this used the same string for both fields, so it passed even when the diagnostic itself
    // was never rendered.
    render(
      <FactoryNodeDrawer
        node={node}
        module={{
          ...baseModule,
          ready: false,
          missingPrerequisites: [],
          diagnostic: 'Required Temporal poller is missing',
        }}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('Required Temporal poller is missing')).toBeInTheDocument()
  })

  it('moves focus into the drawer when it opens', () => {
    render(
      <>
        <button type="button">Open</button>
        <FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} />
      </>,
    )
    expect(screen.getByRole('heading', { name: 'Log watch' })).toHaveFocus()
  })

  it('restores focus to the triggering node button when the drawer closes', async () => {
    // Otherwise a keyboard user who opened the drawer loses their place in the ring: focus falls
    // back to the document body rather than back to the node they just activated.
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Log watch</button>
          <FactoryNodeDrawer
            node={open ? node : null}
            module={null}
            onClose={() => setOpen(false)}
          />
        </>
      )
    }
    render(<Harness />)

    const trigger = screen.getByRole('button', { name: 'Log watch' })
    await userEvent.click(trigger)
    expect(screen.getByRole('heading', { name: 'Log watch' })).toHaveFocus()

    await userEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(trigger).toHaveFocus()
  })

  it('traps Tab within the drawer while it is open', async () => {
    // aria-modal="true" promises assistive technology that background content is inert; a
    // keyboard user who could Tab out to the other eleven graph nodes would make that false.
    render(
      <>
        <button type="button">Log watch</button>
        <button type="button">Some other node</button>
        <FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}>
          <button type="button">Scan logs now</button>
        </FactoryNodeDrawer>
      </>,
    )

    const closeButton = screen.getByRole('button', { name: /close/i })
    const lastButton = screen.getByRole('button', { name: 'Scan logs now' })

    lastButton.focus()
    await userEvent.tab()
    expect(closeButton).toHaveFocus()

    await userEvent.tab({ shift: true })
    expect(lastButton).toHaveFocus()

    expect(screen.queryByRole('button', { name: 'Some other node' })).not.toHaveFocus()
  })
})
