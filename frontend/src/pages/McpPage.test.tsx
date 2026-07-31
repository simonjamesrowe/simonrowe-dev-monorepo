import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { McpPage } from './McpPage'
import type { McpClient } from '../services/mcpClient'
import type { McpTool } from '../types/mcp'

vi.mock('../services/analytics', () => ({
  trackPageView: vi.fn(),
}))

const TOOLS: McpTool[] = [
  {
    name: 'searchBlogs',
    description: 'Search blog posts.',
    inputSchema: {
      type: 'object',
      properties: { query: { type: 'string', description: 'Keywords' } },
      required: ['query'],
    },
  },
  {
    name: 'getProfile',
    description: 'Get the profile.',
    inputSchema: { type: 'object', properties: {} },
  },
]

function fakeClient(overrides: Partial<McpClient> = {}): McpClient {
  return {
    connect: vi.fn().mockResolvedValue(undefined),
    listTools: vi.fn().mockResolvedValue(TOOLS),
    callTool: vi.fn().mockResolvedValue({ content: [] }),
    ...overrides,
  }
}

describe('McpPage', () => {
  it('renders one card per tool from tools/list', async () => {
    render(<McpPage client={fakeClient()} />)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'searchBlogs' })).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: 'getProfile' })).toBeInTheDocument()
    expect(screen.getByText('Search blog posts.')).toBeInTheDocument()
  })

  it('shows a page-level error when connect fails', async () => {
    const client = fakeClient({
      connect: vi.fn().mockRejectedValue(new Error('Unable to reach the MCP server.')),
    })
    render(<McpPage client={client} />)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Unable to reach the MCP server.')
    })
    expect(screen.queryByRole('heading', { name: 'searchBlogs' })).not.toBeInTheDocument()
    // A page-appropriate heading, never the homepage's.
    expect(screen.getByRole('heading', { name: 'Unable to load MCP tools' })).toBeInTheDocument()
    expect(screen.queryByText(/Unable to load homepage/i)).not.toBeInTheDocument()
  })

  it('retries the connection and clears the error on success', async () => {
    const connect = vi
      .fn()
      .mockRejectedValueOnce(new Error('Unable to reach the MCP server.'))
      .mockResolvedValue(undefined)
    render(<McpPage client={fakeClient({ connect })} />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'searchBlogs' })).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(connect).toHaveBeenCalledTimes(2)
  })

  it('sets a page-identifying document title', async () => {
    render(<McpPage client={fakeClient()} />)

    await waitFor(() => {
      expect(document.title).toBe('MCP Tools · Simon Rowe')
    })
  })

  it('renders an empty-state message when the catalogue has no tools', async () => {
    const client = fakeClient({ listTools: vi.fn().mockResolvedValue([]) })
    render(<McpPage client={client} />)

    await waitFor(() => {
      expect(screen.getByText(/reported no tools/i)).toBeInTheDocument()
    })
    // The connect-your-client section still renders.
    expect(screen.getByRole('heading', { name: /connect your client/i })).toBeInTheDocument()
  })
})
