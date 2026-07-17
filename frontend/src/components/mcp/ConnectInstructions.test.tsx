import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ConnectInstructions } from './ConnectInstructions'
import { PUBLIC_MCP_URL } from '../../config/mcp'

describe('ConnectInstructions', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders a snippet per client, each containing the public MCP URL', () => {
    render(<ConnectInstructions />)

    expect(screen.getByRole('heading', { name: 'Claude Code' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Gemini CLI' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Codex CLI' })).toBeInTheDocument()

    // Every snippet references the public endpoint (intro + 3 snippets).
    const withUrl = screen.getAllByText((_, node) =>
      Boolean(node?.textContent?.includes(PUBLIC_MCP_URL)),
    )
    expect(withUrl.length).toBeGreaterThanOrEqual(3)
  })

  it('copies the snippet to the clipboard on click', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })

    render(<ConnectInstructions />)
    fireEvent.click(screen.getAllByRole('button', { name: /copy to clipboard/i })[0])

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(
        expect.stringContaining(PUBLIC_MCP_URL),
      )
    })
  })
})
