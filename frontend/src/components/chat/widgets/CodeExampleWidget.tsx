import type { CodeWidgetPayload } from '../chatTypes'

interface CodeExampleWidgetProps {
  payload: CodeWidgetPayload
}

export function CodeExampleWidget({ payload }: CodeExampleWidgetProps) {
  if (!payload.examples?.length) return null

  return (
    <div className="chat-widget chat-widget--code">
      {payload.examples.map(example => (
        <article className="chat-widget__item" key={example.id ?? example.title}>
          <div className="chat-widget__item-head">
            <h4 className="chat-widget__title">{example.title}</h4>
          </div>
          {example.description && <p className="chat-widget__summary">{example.description}</p>}
          {example.code && (
            <pre className="chat-widget__code">
              <code>{stripMarkdownFence(example.code)}</code>
            </pre>
          )}
          {!!example.skills?.length && (
            <div className="chat-widget__chips">
              {example.skills.map(skill => (
                <span className="chat-widget__chip" key={skill}>{skill}</span>
              ))}
            </div>
          )}
        </article>
      ))}
    </div>
  )
}

function stripMarkdownFence(code: string) {
  return code
    .replace(/^```[\w+-]*\s*\n?/, '')
    .replace(/\n?```\s*$/, '')
}
