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

  it('labels the build node\'s count as waiting, never "In flight", and drops the 24h rows', () => {
    // build's counts are the Linear backlog, not runs in progress: "In flight" implies work is
    // running, and the two 24h rows carry no meaning for a ticket queue.
    render(<FactoryNodeDrawer node={{ ...node, key: 'build', label: 'Build agent', health: 'IDLE' }}
      module={null} onClose={vi.fn()} />)
    expect(screen.getByText('Waiting')).toBeInTheDocument()
    expect(screen.queryByText('In flight')).not.toBeInTheDocument()
    expect(screen.queryByText(/Succeeded \(24h\)/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Failed \(24h\)/)).not.toBeInTheDocument()
  })

  it('keeps "In flight" and the 24h rows for a module node', () => {
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} />)
    expect(screen.getByText('In flight')).toBeInTheDocument()
    expect(screen.getByText('Succeeded (24h)')).toBeInTheDocument()
    expect(screen.getByText('Failed (24h)')).toBeInTheDocument()
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

  it('says open tickets are not available when the linear artifact reader failed', () => {
    // Null items is a different fact from an empty list: the source itself could not be read,
    // not "nothing open" — losing that distinction would misreport a broken Linear or GitHub
    // read as a quiet one. The copy is node-specific ("Open tickets"), not the generic "Run
    // history", so it does not pair with a heading of "Open tickets" above it — the exact noun
    // mismatch Task 12 fixed between the heading and the empty-state copy.
    render(<FactoryNodeDrawer
      node={{ ...node, key: 'linear', label: 'Linear' }}
      module={null}
      onClose={vi.fn()}
      detail={{ nodeKey: 'linear', items: null }}
    />)
    expect(screen.getByText('Open tickets are not available from this console.')).toBeInTheDocument()
    expect(screen.queryByText(/No runs in the last 30 days/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Run history is not available/i)).not.toBeInTheDocument()
  })

  it('says run history is not available when the deployer could not be reached', () => {
    // Same null-items shape, now for a deployer-owned node: `deploy`/`platformbackup` have no
    // artifact reader of their own — this is the deployer container being unreachable rather
    // than an unread Linear/GitHub source, so the copy must not claim a "source" was read at
    // all, just that this console could not get the history. `deploy` is a module node with no
    // entry of its own in the copy lookup, so it keeps the generic "Run history" wording.
    render(<FactoryNodeDrawer
      node={{ ...node, key: 'deploy', label: 'Deploy' }}
      module={null}
      onClose={vi.fn()}
      detail={{ nodeKey: 'deploy', items: null }}
    />)
    expect(screen.getByText('Run history is not available from this console.')).toBeInTheDocument()
    expect(screen.queryByText(/No runs in the last 30 days/i)).not.toBeInTheDocument()
  })

  it('says "No open tickets" for the Linear node rather than the module wording', () => {
    // linearItems() applies no 30-day window and returns tickets, not runs — the module copy
    // ("No runs in the last 30 days") would state a window that was never applied, about a
    // kind of thing that does not exist on this node.
    render(<FactoryNodeDrawer node={{ ...node, key: 'linear', label: 'Linear' }} module={null}
      onClose={vi.fn()} detail={{ nodeKey: 'linear', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Open tickets' })).toBeInTheDocument()
    expect(screen.getByText('No open tickets.')).toBeInTheDocument()
    expect(screen.queryByText(/No runs in the last 30 days/i)).not.toBeInTheDocument()
  })

  it('says "No open pull requests" for the pull-request node', () => {
    render(<FactoryNodeDrawer node={{ ...node, key: 'pull-request', label: 'Pull request' }}
      module={null} onClose={vi.fn()} detail={{ nodeKey: 'pull-request', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Open pull requests' })).toBeInTheDocument()
    expect(screen.getByText('No open pull requests.')).toBeInTheDocument()
  })

  it('says "No open pull requests" for the agent-setup node too', () => {
    // Same wording as pull-request — both list open GitHub pull requests, just on different
    // repositories — but wired through its own node key rather than inherited by accident.
    render(<FactoryNodeDrawer node={{ ...node, key: 'agent-setup', label: 'agent-setup' }}
      module={null} onClose={vi.fn()} detail={{ nodeKey: 'agent-setup', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Open pull requests' })).toBeInTheDocument()
    expect(screen.getByText('No open pull requests.')).toBeInTheDocument()
  })

  it('says "No recent merges" for the main node', () => {
    render(<FactoryNodeDrawer node={{ ...node, key: 'main', label: 'main' }} module={null}
      onClose={vi.fn()} detail={{ nodeKey: 'main', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Recent merges' })).toBeInTheDocument()
    expect(screen.getByText('No recent merges.')).toBeInTheDocument()
  })

  it('keeps the module wording for a module node', () => {
    // logwatch has no entry in the copy lookup, so it must fall through to the module default
    // rather than losing the heading entirely.
    render(<FactoryNodeDrawer node={node} module={null}
      onClose={vi.fn()} detail={{ nodeKey: 'logwatch', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Recent runs' })).toBeInTheDocument()
    expect(screen.getByText('No runs in the last 30 days.')).toBeInTheDocument()
  })

  it('does not claim a 30-day run window for the production node', () => {
    // production has no Temporal workflow and no artifact reader: its FlowDetail is always
    // FlowDetail.empty(), never a "runs" list bounded by a 30-day retention window. The module
    // default would state a window and a noun ("runs") that never applied here.
    render(<FactoryNodeDrawer node={{ ...node, key: 'production', label: 'Production' }}
      module={null} onClose={vi.fn()} detail={{ nodeKey: 'production', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Recent activity' })).toBeInTheDocument()
    expect(screen.getByText('Production has no activity of its own here — see the platform status page.')).toBeInTheDocument()
    expect(screen.queryByText(/No runs in the last 30 days/i)).not.toBeInTheDocument()
  })

  it('does not claim a 30-day run window for the build node', () => {
    // build runs on a machine this console cannot reach and has no Temporal workflow of its
    // own; its counts (rendered above) are the real signal, not a "runs" list.
    render(<FactoryNodeDrawer node={{ ...node, key: 'build', label: 'Build agent' }}
      module={null} onClose={vi.fn()} detail={{ nodeKey: 'build', items: [] }} />)
    expect(screen.getByRole('heading', { name: 'Recent activity' })).toBeInTheDocument()
    expect(screen.getByText(/counts above/i)).toBeInTheDocument()
    expect(screen.queryByText(/No runs in the last 30 days/i)).not.toBeInTheDocument()
  })

  it('renders when an item started, not just its status', () => {
    // FlowDetail.Item.at is populated by every reader but was never rendered — commits, pull
    // requests and Linear tickets showed no date at all, and spec.md's drawer requirement names
    // "started" explicitly.
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}
      detail={{ nodeKey: 'logwatch', items: [
        {
          id: 'logwatch-2', title: 'logwatch-2', status: 'COMPLETED',
          at: '2026-09-04T20:14:00Z', url: null,
        },
      ] }} />)
    expect(screen.getByText(new Date('2026-09-04T20:14:00Z').toLocaleString())).toBeInTheDocument()
  })

  it('renders each item as a link whose accessible name stays unique across a title collision', () => {
    // Two open tickets can share a title; only the id inside the link tells them apart, and it
    // must be inside the anchor to be part of the accessible name at all.
    render(<FactoryNodeDrawer
      node={{ ...node, key: 'linear', label: 'Linear' }}
      module={null}
      onClose={vi.fn()}
      detail={{ nodeKey: 'linear', items: [
        {
          id: 'SIM-1', title: 'openssl', status: 'TRIAGE', at: null,
          url: 'https://linear.app/sim-1',
        },
        {
          id: 'SIM-2', title: 'openssl', status: 'STARTED', at: null,
          url: 'https://linear.app/sim-2',
        },
      ] }}
    />)
    const links = screen.getAllByRole('link', { name: /openssl/i })
    expect(links).toHaveLength(2)
    expect(links[0]).toHaveAccessibleName('openssl (SIM-1)')
    expect(links[1]).toHaveAccessibleName('openssl (SIM-2)')
    expect(links[0]).toHaveAttribute('href', 'https://linear.app/sim-1')
    expect(links[0]).toHaveAttribute('rel', 'noopener noreferrer')
    expect(links[0]).toHaveAttribute('target', '_blank')
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
