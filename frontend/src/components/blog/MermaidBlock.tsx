import { useEffect, useRef, useState } from 'react'
import mermaid from 'mermaid'

mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
})

let mermaidCounter = 0

interface MermaidBlockProps {
  chart: string
}

export function MermaidBlock({ chart }: MermaidBlockProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [svg, setSvg] = useState<string>('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const id = `mermaid-${++mermaidCounter}`

    mermaid
      .render(id, chart.trim())
      .then(({ svg: renderedSvg }) => {
        setSvg(renderedSvg)
        setError(null)
      })
      .catch((err) => {
        setError(String(err))
      })
  }, [chart])

  if (error) {
    return (
      <pre className="mermaid-error">
        <code>{chart}</code>
      </pre>
    )
  }

  return (
    <div
      className="mermaid-diagram"
      dangerouslySetInnerHTML={{ __html: svg }}
      ref={containerRef}
    />
  )
}
