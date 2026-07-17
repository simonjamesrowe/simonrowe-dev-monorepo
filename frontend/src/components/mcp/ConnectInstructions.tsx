import { useState } from 'react'
import { Check, Copy } from 'lucide-react'

import { PUBLIC_MCP_URL } from '../../config/mcp'

interface ClientSnippet {
  id: string
  name: string
  location: string
  snippet: string
}

// Verified against current client docs (July 2026). All three support remote
// Streamable-HTTP MCP natively, so no bridge is needed. The server is
// unauthenticated, so no auth header/token is required.
const SNIPPETS: ClientSnippet[] = [
  {
    id: 'claude-code',
    name: 'Claude Code',
    location: 'Run in your terminal',
    snippet: `claude mcp add --transport http simonrowe-dev ${PUBLIC_MCP_URL}`,
  },
  {
    id: 'gemini-cli',
    name: 'Gemini CLI',
    location: '~/.gemini/settings.json',
    snippet: `{
  "mcpServers": {
    "simonrowe-dev": { "httpUrl": "${PUBLIC_MCP_URL}" }
  }
}`,
  },
  {
    id: 'codex-cli',
    name: 'Codex CLI',
    location: '~/.codex/config.toml',
    snippet: `[mcp_servers.simonrowe-dev]
url = "${PUBLIC_MCP_URL}"
# On older Codex versions add: experimental_use_rmcp_client = true`,
  },
]

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = () => {
    // Guard against environments without the async clipboard API.
    if (!navigator.clipboard?.writeText) return
    navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopied(true)
        window.setTimeout(() => setCopied(false), 2000)
      })
      .catch(() => {
        // Copy failed (e.g. permission denied) — leave the UI unchanged.
      })
  }

  return (
    <button
      aria-label="Copy to clipboard"
      className="mcp-connect__copy"
      onClick={handleCopy}
      type="button"
    >
      {copied ? <Check size={16} aria-hidden="true" /> : <Copy size={16} aria-hidden="true" />}
      <span>{copied ? 'Copied' : 'Copy'}</span>
    </button>
  )
}

export function ConnectInstructions() {
  return (
    <section className="mcp-page__connect mcp-connect" aria-labelledby="mcp-connect-heading">
      <h2 className="mcp-page__section-title" id="mcp-connect-heading">
        Connect your client
      </h2>
      <p className="mcp-connect__intro">
        Point any MCP-capable client at <code>{PUBLIC_MCP_URL}</code>:
      </p>
      <div className="mcp-connect__grid">
        {SNIPPETS.map((client) => (
          <div key={client.id} className="mcp-connect__card">
            <div className="mcp-connect__card-head">
              <h3 className="mcp-connect__client-name">{client.name}</h3>
              <span className="mcp-connect__location">{client.location}</span>
            </div>
            <div className="mcp-connect__snippet-wrap">
              <pre className="mcp-connect__snippet">{client.snippet}</pre>
              <CopyButton text={client.snippet} />
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
