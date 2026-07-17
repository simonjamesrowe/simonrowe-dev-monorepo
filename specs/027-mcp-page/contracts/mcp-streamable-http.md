# Contract: MCP Streamable-HTTP (client-consumed)

The `/mcp` page is an MCP client. It does not define new HTTP endpoints — it consumes the JSON-RPC 2.0 methods of the site's own MCP server over the Streamable-HTTP transport at `POST /mcp` (same-origin via proxy). This is the contract `mcpClient.ts` depends on.

**Prerequisite**: backend `spring.ai.mcp.server.protocol: STREAMABLE` (see research.md Decision 1). Until set, `/mcp` returns 404 and the SSE transport is active at `/sse` instead.

## Common

- **URL**: `POST ${API_BASE_URL}/mcp` (default same-origin `/mcp`).
- **Request headers** (all calls): `Content-Type: application/json`, `Accept: application/json, text/event-stream`.
- **Session**: after `initialize`, every request carries `Mcp-Session-Id: <captured value>`.
- **Response body**: either `application/json` (parse directly) or `text/event-stream` (extract `data:` line(s), then JSON-parse). Client reads full text.

## 1. initialize

Request:
```json
{ "jsonrpc": "2.0", "id": 1, "method": "initialize",
  "params": { "protocolVersion": "2024-11-05", "capabilities": {},
              "clientInfo": { "name": "simonrowe-dev-web", "version": "1.0.0" } } }
```
Response: `result` with server capabilities/info. **Side effect the client depends on**: the response carries an `Mcp-Session-Id` header to reuse on subsequent calls.

## 2. notifications/initialized

Request (notification — no `id`):
```json
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```
Sent with the `Mcp-Session-Id` header. No result body expected (202/empty tolerated).

## 3. tools/list

Request:
```json
{ "jsonrpc": "2.0", "id": 2, "method": "tools/list" }
```
Response:
```json
{ "jsonrpc": "2.0", "id": 2, "result": { "tools": [
  { "name": "searchBlogs", "description": "Search Simon's published blog posts…",
    "inputSchema": { "type": "object",
      "properties": { "query": { "type": "string", "description": "Search keywords…" } },
      "required": ["query"] } }
] } }
```
→ `McpTool[]` (one card each).

## 4. tools/call

Request:
```json
{ "jsonrpc": "2.0", "id": 3, "method": "tools/call",
  "params": { "name": "searchBlogs", "arguments": { "query": "kafka" } } }
```
Response:
```json
{ "jsonrpc": "2.0", "id": 3, "result": {
  "content": [ { "type": "text", "text": "[{\"title\":\"…\"}]" } ], "isError": false } }
```
→ `ToolResult` rendered in the card's result panel. A JSON-RPC `error` or `isError:true` → the card's error state.

## Error behaviour

| Failure | Client handling |
|---------|-----------------|
| `initialize` / `notifications/initialized` / `tools/list` fails (network, non-2xx, JSON-RPC error) | reject → page-level `ErrorMessage` |
| `tools/call` fails | reject → per-card error state; other cards unaffected |
| Rate limited (429 from the 60 req/min limiter) | surfaced as the relevant error state with the server message |

## Client API surface (`mcpClient.ts`)

```ts
connect(): Promise<void>                    // initialize + capture session + notifications/initialized
listTools(): Promise<McpTool[]>             // tools/list
callTool(name: string, args: Record<string, unknown>): Promise<ToolResult>   // tools/call
```
Pure TypeScript, no React; injectable/mockable so `McpPage` and `ToolCard` tests stub it.
