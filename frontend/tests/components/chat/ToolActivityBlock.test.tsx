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
})
