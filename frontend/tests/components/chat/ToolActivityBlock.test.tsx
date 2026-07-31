import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ToolActivityBlock } from '../../../src/components/chat/ToolActivityBlock'

describe('ToolActivityBlock', () => {
  it('renders the contextual label while running', () => {
    render(<ToolActivityBlock block={{ kind: 'tool', label: "Looking up Simon's skills", status: 'running' }} />)

    expect(screen.getByText("Looking up Simon's skills")).toBeInTheDocument()
  })

  it('renders the contextual label (not "Used 1 tool") when done, with no expander', () => {
    const { container } = render(
      <ToolActivityBlock block={{ kind: 'tool', label: "Looking up Simon's skills", status: 'done' }} />,
    )

    expect(screen.getByText("Looking up Simon's skills")).toBeInTheDocument()
    expect(screen.queryByText('Used 1 tool')).not.toBeInTheDocument()
    // No <details>/<summary> expander in the finished state.
    expect(container.querySelector('details')).toBeNull()
    expect(container.querySelector('summary')).toBeNull()
  })

  it('shows a tool icon in both states', () => {
    const running = render(
      <ToolActivityBlock block={{ kind: 'tool', label: 'Fetching code examples', status: 'running' }} />,
    )
    expect(running.container.querySelector('.chat-tool__icon')).not.toBeNull()
    // The spinner is the extra progress cue while running, on top of the tool icon.
    expect(running.container.querySelector('.chat-tool__spinner')).not.toBeNull()
    running.unmount()

    const done = render(
      <ToolActivityBlock block={{ kind: 'tool', label: 'Fetching code examples', status: 'done' }} />,
    )
    expect(done.container.querySelector('.chat-tool__icon')).not.toBeNull()
    expect(done.container.querySelector('.chat-tool__spinner')).toBeNull()
  })

  it('picks a distinct icon per tool, and a fallback for anything unrecognised', () => {
    const iconPathsFor = (label: string) => {
      const { container, unmount } = render(
        <ToolActivityBlock block={{ kind: 'tool', label, status: 'done' }} />,
      )
      const svg = container.querySelector('.chat-tool__icon')?.innerHTML ?? ''
      unmount()
      return svg
    }

    const icons = [
      "Looking up Simon's skills",
      'Pulling up employment history',
      'Fetching code examples',
      'Searching blog posts',
      'Searching tech news',
      'Finding upcoming events',
      'Some brand new tool',
    ].map(iconPathsFor)

    // Every label resolves to some icon...
    icons.forEach((svg) => expect(svg).not.toBe(''))
    // ...and the six known tools are all visually distinct from each other.
    expect(new Set(icons.slice(0, 6)).size).toBe(6)
  })
})
