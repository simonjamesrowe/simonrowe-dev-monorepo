import { useCallback, useEffect, useMemo, useState } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ConnectInstructions } from '../components/mcp/ConnectInstructions'
import { ToolCard } from '../components/mcp/ToolCard'
import { usePageTitle } from '../hooks/usePageTitle'
import { createMcpClient, type McpClient } from '../services/mcpClient'
import { trackPageView } from '../services/analytics'
import type { McpTool } from '../types/mcp'

interface McpPageProps {
  // Injectable for tests; defaults to a real Streamable-HTTP client.
  client?: McpClient
}

export function McpPage({ client }: McpPageProps) {
  const mcpClient = useMemo(() => client ?? createMcpClient(), [client])
  const [tools, setTools] = useState<McpTool[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Bumping the attempt counter re-runs the connect/list effect, which is what Retry does.
  const [attempt, setAttempt] = useState(0)

  usePageTitle('MCP Tools')

  useEffect(() => {
    trackPageView('/mcp')
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    mcpClient
      .connect()
      .then(() => mcpClient.listTools())
      .then((list) => {
        if (!cancelled) setTools(list)
      })
      .catch((err: Error) => {
        if (!cancelled) setError(err.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [mcpClient, attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return (
    <div className="mcp-page">
      <header className="mcp-page__header">
        <h1 className="mcp-page__title">MCP Tools</h1>
        <p className="mcp-page__intro">
          This site runs a Model Context Protocol server. The tools below are read live
          from it — try them here, or connect your own MCP client.
        </p>
      </header>

      <ConnectInstructions />

      <section className="mcp-page__tools" aria-labelledby="mcp-tools-heading">
        <h2 className="mcp-page__section-title" id="mcp-tools-heading">
          Available tools
        </h2>
        {loading ? (
          <LoadingIndicator message="Connecting to the MCP server..." />
        ) : error ? (
          <ErrorMessage message={error} onRetry={retry} title="Unable to load MCP tools" />
        ) : tools.length === 0 ? (
          <p className="mcp-page__empty">The MCP server reported no tools.</p>
        ) : (
          <div className="mcp-page__grid">
            {tools.map((tool) => (
              <ToolCard key={tool.name} client={mcpClient} tool={tool} />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

export default McpPage
