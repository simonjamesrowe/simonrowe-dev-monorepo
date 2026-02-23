import type { ComponentProps } from 'react'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { coy } from 'react-syntax-highlighter/dist/esm/styles/prism'

type CodeProps = ComponentProps<'code'> & {
  inline?: boolean
}

export function CodeBlock({ children, className, inline }: CodeProps) {
  const match = /language-(\w+)/.exec(className ?? '')
  const language = match ? match[1] : null
  const codeString = String(children).replace(/\n$/, '')

  if (!inline && language) {
    return (
      <SyntaxHighlighter language={language} style={coy}>
        {codeString}
      </SyntaxHighlighter>
    )
  }

  return <code className={className}>{children}</code>
}
