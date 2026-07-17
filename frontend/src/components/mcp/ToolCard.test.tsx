import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { ToolCard } from './ToolCard'
import type { McpClient } from '../../services/mcpClient'
import type { McpTool } from '../../types/mcp'

function fakeClient(overrides: Partial<McpClient> = {}): McpClient {
  return {
    connect: vi.fn().mockResolvedValue(undefined),
    listTools: vi.fn().mockResolvedValue([]),
    callTool: vi.fn().mockResolvedValue({ content: [{ type: 'text', text: 'ok' }] }),
    ...overrides,
  }
}

const richTool: McpTool = {
  name: 'searchNews',
  description: 'Search the news.',
  inputSchema: {
    type: 'object',
    properties: {
      query: { type: 'string', description: 'Keywords' },
      source: { type: 'string', enum: ['hn', 'lobsters'], description: 'Source' },
      recent: { type: 'boolean', description: 'Only recent' },
    },
    required: ['query'],
  },
}

describe('ToolCard', () => {
  it('generates a form from the schema (text, enum → select, boolean → checkbox)', () => {
    render(<ToolCard client={fakeClient()} tool={richTool} />)

    // text input
    expect(screen.getByLabelText(/query/i)).toHaveAttribute('type', 'text')
    // enum → select with the enum options
    const select = screen.getByLabelText(/source/i)
    expect(select.tagName).toBe('SELECT')
    expect(screen.getByRole('option', { name: 'hn' })).toBeInTheDocument()
    // boolean → checkbox
    expect(screen.getByLabelText(/recent/i)).toHaveAttribute('type', 'checkbox')
    // required marker
    expect(document.querySelector('.mcp-tool-card__required')).toBeInTheDocument()
  })

  it('runs the tool with entered args and renders the result', async () => {
    const callTool = vi
      .fn()
      .mockResolvedValue({ content: [{ type: 'text', text: '{"hits":3}' }] })
    render(<ToolCard client={fakeClient({ callTool })} tool={richTool} />)

    fireEvent.change(screen.getByLabelText(/query/i), { target: { value: 'kafka' } })
    fireEvent.change(screen.getByLabelText(/source/i), { target: { value: 'hn' } })
    fireEvent.click(screen.getByLabelText(/recent/i))
    fireEvent.click(screen.getByRole('button', { name: /run/i }))

    await waitFor(() => {
      expect(callTool).toHaveBeenCalledWith('searchNews', {
        query: 'kafka',
        source: 'hn',
        recent: true,
      })
    })
    // Result is pretty-printed JSON.
    expect(screen.getByText(/"hits": 3/)).toBeInTheDocument()
  })

  it('shows a per-card error when the call fails', async () => {
    const callTool = vi.fn().mockRejectedValue(new Error('boom'))
    render(<ToolCard client={fakeClient({ callTool })} tool={richTool} />)

    fireEvent.change(screen.getByLabelText(/query/i), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: /run/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('boom')
    })
  })

  it('gates a denylisted tool: shows the badge, docs, and no run form', () => {
    const denyTool: McpTool = {
      name: 'submitContactForm',
      description: 'Send a message.',
      inputSchema: {
        type: 'object',
        properties: { email: { type: 'string', description: 'Email' } },
        required: ['email'],
      },
    }
    render(<ToolCard client={fakeClient()} tool={denyTool} />)

    expect(screen.getByText(/not runnable here/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /run/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument()
    // Docs (param name) still shown.
    expect(screen.getByText('email')).toBeInTheDocument()
  })
})
