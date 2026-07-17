import type {
  JsonRpcRequest,
  JsonRpcResponse,
  McpTool,
  ToolResult,
} from '../types/mcp'

// A genuine MCP client speaking JSON-RPC 2.0 over the Streamable-HTTP transport
// against the site's own /mcp endpoint (same-origin via proxy). Pure TypeScript,
// no React — injectable/mockable so page and component tests can stub it.
//
// The backend (Spring AI 1.1.4) may answer a POST with either an application/json
// body or a single text/event-stream frame, so parsing handles both. Bodies are
// short single request/response payloads, so we read full text rather than stream.

// Always same-origin, routed to the backend by the dev (Vite) / prod (nginx)
// proxy. This is deliberate: a cross-origin call would hide the Mcp-Session-Id
// response header from JS (it is not CORS-safelisted and the server does not send
// Access-Control-Expose-Headers), breaking the session handshake.
const MCP_URL = '/mcp'
const PROTOCOL_VERSION = '2024-11-05'
const SESSION_HEADER = 'Mcp-Session-Id'

const REQUEST_HEADERS: Record<string, string> = {
  'Content-Type': 'application/json',
  Accept: 'application/json, text/event-stream',
}

export interface McpClient {
  connect(): Promise<void>
  listTools(): Promise<McpTool[]>
  callTool(name: string, args: Record<string, unknown>): Promise<ToolResult>
}

// Extract a JSON-RPC payload from a response that is either raw JSON or SSE-framed
// (one or more `data:` lines). Returns null for empty bodies (e.g. notification acks).
function parseBody<R>(contentType: string, body: string): JsonRpcResponse<R> | null {
  const trimmed = body.trim()
  if (trimmed === '') return null

  if (contentType.includes('text/event-stream')) {
    // Concatenate the payload from all `data:` lines of the (single) SSE event.
    const data = trimmed
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice('data:'.length).trim())
      .join('')
    if (data === '') return null
    return JSON.parse(data) as JsonRpcResponse<R>
  }

  return JSON.parse(trimmed) as JsonRpcResponse<R>
}

class StreamableHttpMcpClient implements McpClient {
  private sessionId: string | null = null
  private nextId = 1

  private headers(): Record<string, string> {
    const headers = { ...REQUEST_HEADERS }
    if (this.sessionId) headers[SESSION_HEADER] = this.sessionId
    return headers
  }

  // POST a JSON-RPC request/notification. Returns the parsed response, or null for
  // notifications and empty bodies. Throws on transport or JSON-RPC errors.
  private async post<R>(request: JsonRpcRequest): Promise<JsonRpcResponse<R> | null> {
    let response: Response
    try {
      response = await fetch(MCP_URL, {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify(request),
      })
    } catch (err) {
      throw new Error(
        `Unable to reach the MCP server. ${err instanceof Error ? err.message : ''}`.trim(),
      )
    }

    // Capture the session id from the initialize response (header lookup is
    // case-insensitive per the Fetch spec).
    const returnedSession = response.headers.get(SESSION_HEADER)
    if (returnedSession) this.sessionId = returnedSession

    const text = await response.text()

    if (!response.ok) {
      const detail = extractErrorMessage(text)
      throw new Error(detail ?? `MCP request failed (HTTP ${response.status}).`)
    }

    const contentType = response.headers.get('Content-Type') ?? ''
    const parsed = parseBody<R>(contentType, text)
    if (parsed?.error) {
      throw new Error(parsed.error.message || 'The MCP server returned an error.')
    }
    return parsed
  }

  async connect(): Promise<void> {
    await this.post({
      jsonrpc: '2.0',
      id: this.nextId++,
      method: 'initialize',
      params: {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: { name: 'simonrowe-dev-web', version: '1.0.0' },
      },
    })
    // Notification: no id, no response body expected.
    await this.post({ jsonrpc: '2.0', method: 'notifications/initialized' })
  }

  async listTools(): Promise<McpTool[]> {
    const response = await this.post<{ tools: McpTool[] }>({
      jsonrpc: '2.0',
      id: this.nextId++,
      method: 'tools/list',
    })
    return response?.result?.tools ?? []
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
    const response = await this.post<ToolResult>({
      jsonrpc: '2.0',
      id: this.nextId++,
      method: 'tools/call',
      params: { name, arguments: args },
    })
    const result = response?.result
    if (!result) {
      throw new Error('The MCP server returned an empty result.')
    }
    return result
  }
}

// Best-effort extraction of a human-readable message from a Spring error body.
function extractErrorMessage(body: string): string | null {
  const trimmed = body.trim()
  if (trimmed === '') return null
  try {
    const parsed = JSON.parse(trimmed) as { message?: string; error?: { message?: string } }
    return parsed.error?.message ?? parsed.message ?? null
  } catch {
    return null
  }
}

export function createMcpClient(): McpClient {
  return new StreamableHttpMcpClient()
}
