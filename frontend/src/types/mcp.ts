// Client-side shapes mirroring the MCP protocol payloads consumed by the /mcp page.
// The page is a genuine MCP client speaking JSON-RPC 2.0 over the Streamable-HTTP
// transport; nothing here is persisted — everything is fetched live and held in React state.

export interface JsonRpcRequest<P = unknown> {
  jsonrpc: '2.0'
  id?: number | string // omitted for notifications
  method: string
  params?: P
}

export interface JsonRpcError {
  code: number
  message: string
  data?: unknown
}

export interface JsonRpcResponse<R = unknown> {
  jsonrpc: '2.0'
  id: number | string
  result?: R
  error?: JsonRpcError
}

// A single property in a tool's JSON-schema input definition.
export interface McpJsonSchemaProperty {
  type?: string // "string" | "boolean" | "number" | "integer" | ...
  description?: string
  enum?: (string | number)[] // restricted value set -> <select>
}

// A tool's input schema (a JSON Schema object).
export interface McpJsonSchema {
  type?: string // usually "object" at the top level
  properties?: Record<string, McpJsonSchemaProperty>
  required?: string[] // names of required properties
}

// One tool as reported by tools/list.
export interface McpTool {
  name: string
  description?: string
  inputSchema: McpJsonSchema
}

// A single content item in a tool result.
export interface ToolResultContent {
  type: string // "text" | "resource" | "image" | ...
  text?: string // present for type === "text"
  [key: string]: unknown // tolerate other content shapes
}

// The result of a tools/call.
export interface ToolResult {
  content: ToolResultContent[]
  isError?: boolean
}
