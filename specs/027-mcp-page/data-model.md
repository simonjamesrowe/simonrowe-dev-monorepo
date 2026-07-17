# Data Model: Public MCP Tools Page

**Phase 1 output.** No persistence — these are client-side TypeScript shapes (`src/types/mcp.ts`) mirroring the MCP protocol payloads. All entities are transient (fetched live, held in React state).

## JSON-RPC envelopes

```ts
interface JsonRpcRequest<P = unknown> {
  jsonrpc: '2.0'
  id?: number | string        // omitted for notifications
  method: string
  params?: P
}

interface JsonRpcResponse<R = unknown> {
  jsonrpc: '2.0'
  id: number | string
  result?: R
  error?: JsonRpcError
}

interface JsonRpcError {
  code: number
  message: string
  data?: unknown
}
```

**Validation**: A response with a truthy `error` is surfaced as a thrown `Error(error.message)`; page-level for connect/list, per-card for `tools/call`.

## MCP tool catalogue

```ts
interface McpTool {
  name: string                // unique id, e.g. "searchBlogs"
  description?: string
  inputSchema: McpJsonSchema  // JSON Schema (object) describing arguments
}

interface McpJsonSchema {
  type?: string               // usually "object" at the top level
  properties?: Record<string, McpJsonSchemaProperty>
  required?: string[]         // names of required properties
}

interface McpJsonSchemaProperty {
  type?: string               // "string" | "boolean" | "number" | "integer" | ...
  description?: string
  enum?: (string | number)[]  // restricted value set → <select>
}
```

**Derived from**: the `result.tools` array of a `tools/list` response.

**Form-generation rules** (ToolCard), applied per property in `inputSchema.properties`:
| Condition | Control |
|-----------|---------|
| `enum` present | `<select>` with an option per enum value |
| `type === 'boolean'` | checkbox |
| otherwise (incl. `string`, `number`) | text `<input>` |

- A property whose name is in `inputSchema.required` is marked required (visual `*`) and its label reflects that.
- A tool with no `properties` (or empty) renders a Run button and no fields.
- `number`/`integer` text inputs are coerced to Number before being placed in `arguments`; blanks for optional fields are omitted.

## Tool result

```ts
interface ToolResult {
  content: ToolResultContent[]
  isError?: boolean
}

interface ToolResultContent {
  type: string                // "text" | "resource" | "image" | ...
  text?: string               // present for type === "text"
  [k: string]: unknown        // tolerate other content shapes
}
```

**Derived from**: the `result` of a `tools/call` response.

**Rendering rules** (ToolCard result panel):
- `type === 'text'` items: if `text` parses as JSON, pretty-print it (`JSON.stringify(parsed, null, 2)`) in a `<pre>`; else show raw text.
- Non-text items: pretty-print the whole content item as JSON.
- `isError === true` (or a JSON-RPC error) → the card's error state, not the result panel.

## Client-side constants / config

```ts
const DENYLISTED_TOOLS = ['submitContactForm'] as const   // not runnable on the public page
const PUBLIC_MCP_URL = 'https://api.simonrowe.dev/mcp'     // for ConnectInstructions snippets
```

- **Session state** (inside `mcpClient`): the `Mcp-Session-Id` string captured at `initialize`, sent on every subsequent request. Held in module/closure state for the client instance; not React state.

## Entity relationships

```
tools/list ──▶ McpTool[] ──(one per)──▶ ToolCard
                  │
                  └─ inputSchema.properties ──▶ generated form fields
ToolCard "Run" ──▶ tools/call(name, arguments) ──▶ ToolResult ──▶ result panel
DENYLISTED_TOOLS ∩ McpTool.name ──▶ badge instead of form
```
