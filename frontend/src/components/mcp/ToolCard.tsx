import { useState } from 'react'
import { Loader2, Play, ShieldOff } from 'lucide-react'

import { isDenylisted } from '../../config/mcp'
import type { McpClient } from '../../services/mcpClient'
import type { McpJsonSchemaProperty, McpTool, ToolResult } from '../../types/mcp'

interface ToolCardProps {
  tool: McpTool
  client: McpClient
}

// Build the arguments object from raw form values, applying the schema's types:
// coerce number/integer, keep booleans, and omit empty optional strings.
function buildArguments(
  tool: McpTool,
  values: Record<string, string | boolean>,
): Record<string, unknown> {
  const properties = tool.inputSchema.properties ?? {}
  const required = new Set(tool.inputSchema.required ?? [])
  const args: Record<string, unknown> = {}

  for (const [name, prop] of Object.entries(properties)) {
    const value = values[name]
    if (prop.type === 'boolean') {
      args[name] = Boolean(value)
      continue
    }
    const text = typeof value === 'string' ? value.trim() : ''
    if (text === '') {
      if (required.has(name)) args[name] = '' // let the server report the missing field
      continue
    }
    if (prop.type === 'number' || prop.type === 'integer') {
      const num = Number(text)
      args[name] = Number.isNaN(num) ? text : num
    } else {
      args[name] = text
    }
  }
  return args
}

// Render a text item: pretty-print JSON if it parses, otherwise show it raw.
function formatText(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

function renderResult(result: ToolResult): string {
  return result.content
    .map((item) =>
      item.type === 'text' && typeof item.text === 'string'
        ? formatText(item.text)
        : JSON.stringify(item, null, 2),
    )
    .join('\n\n')
}

function ParamField({
  name,
  prop,
  required,
  value,
  onChange,
}: {
  name: string
  prop: McpJsonSchemaProperty
  required: boolean
  value: string | boolean
  onChange: (value: string | boolean) => void
}) {
  const fieldId = `param-${name}`
  const label = (
    <label className="mcp-tool-card__label" htmlFor={fieldId}>
      {name}
      {required && <span className="mcp-tool-card__required" aria-hidden="true"> *</span>}
      {prop.description && (
        <span className="mcp-tool-card__param-desc">{prop.description}</span>
      )}
    </label>
  )

  if (prop.type === 'boolean') {
    return (
      <div className="mcp-tool-card__field mcp-tool-card__field--checkbox">
        <input
          checked={Boolean(value)}
          id={fieldId}
          onChange={(e) => onChange(e.target.checked)}
          type="checkbox"
        />
        {label}
      </div>
    )
  }

  if (prop.enum && prop.enum.length > 0) {
    return (
      <div className="mcp-tool-card__field">
        {label}
        <select
          className="mcp-tool-card__input"
          id={fieldId}
          onChange={(e) => onChange(e.target.value)}
          value={typeof value === 'string' ? value : ''}
        >
          <option value="">—</option>
          {prop.enum.map((option) => (
            <option key={String(option)} value={String(option)}>
              {String(option)}
            </option>
          ))}
        </select>
      </div>
    )
  }

  return (
    <div className="mcp-tool-card__field">
      {label}
      <input
        className="mcp-tool-card__input"
        id={fieldId}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        type="text"
        value={typeof value === 'string' ? value : ''}
      />
    </div>
  )
}

export function ToolCard({ tool, client }: ToolCardProps) {
  const properties = tool.inputSchema.properties ?? {}
  const requiredNames = new Set(tool.inputSchema.required ?? [])
  const paramEntries = Object.entries(properties)
  const denylisted = isDenylisted(tool.name)

  const [values, setValues] = useState<Record<string, string | boolean>>({})
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<ToolResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const setValue = (name: string, value: string | boolean) =>
    setValues((prev) => ({ ...prev, [name]: value }))

  const handleRun = (event: React.FormEvent) => {
    event.preventDefault()
    setRunning(true)
    setError(null)
    setResult(null)
    client
      .callTool(tool.name, buildArguments(tool, values))
      .then((res) => {
        if (res.isError) {
          setError(renderResult(res) || 'The tool reported an error.')
        } else {
          setResult(res)
        }
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setRunning(false))
  }

  return (
    <article className="mcp-tool-card">
      <header className="mcp-tool-card__header">
        <h3 className="mcp-tool-card__name">{tool.name}</h3>
        {denylisted && (
          <span className="mcp-tool-card__badge" title="Disabled on this public page">
            <ShieldOff size={14} aria-hidden="true" /> not runnable here
          </span>
        )}
      </header>
      {tool.description && <p className="mcp-tool-card__desc">{tool.description}</p>}

      {denylisted ? (
        paramEntries.length > 0 && (
          <ul className="mcp-tool-card__params">
            {paramEntries.map(([name, prop]) => (
              <li key={name} className="mcp-tool-card__param">
                <code>{name}</code>
                {requiredNames.has(name) && (
                  <span className="mcp-tool-card__required" aria-hidden="true"> *</span>
                )}
                {prop.type && <span className="mcp-tool-card__param-type"> ({prop.type})</span>}
                {prop.description && (
                  <span className="mcp-tool-card__param-desc">{prop.description}</span>
                )}
              </li>
            ))}
          </ul>
        )
      ) : (
        <form className="mcp-tool-card__form" onSubmit={handleRun}>
          {paramEntries.map(([name, prop]) => (
            <ParamField
              key={name}
              name={name}
              onChange={(value) => setValue(name, value)}
              prop={prop}
              required={requiredNames.has(name)}
              value={values[name] ?? (prop.type === 'boolean' ? false : '')}
            />
          ))}
          <button className="mcp-tool-card__run" disabled={running} type="submit">
            {running ? (
              <>
                <Loader2 className="mcp-tool-card__spin" size={16} aria-hidden="true" /> Running…
              </>
            ) : (
              <>
                <Play size={16} aria-hidden="true" /> Run
              </>
            )}
          </button>
        </form>
      )}

      {error && (
        <div className="mcp-tool-card__error" role="alert">
          {error}
        </div>
      )}
      {result && (
        <pre className="mcp-tool-card__result">{renderResult(result)}</pre>
      )}
    </article>
  )
}
