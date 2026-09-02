import type { ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import rehypeRaw from 'rehype-raw'
import remarkGfm from 'remark-gfm'

import { CodeBlock } from './CodeBlock'
import { SmartLink } from './SmartLink'

interface MarkdownRendererProps {
  content: string
}

/**
 * A wide comparison table has to be able to scroll sideways on a phone, and the
 * scroll container cannot be the table: `overflow-x` only takes effect on a
 * block-level box, and `display: block` on a <table> stops its cells sizing as
 * a table at all. So the table keeps its own display mode and this wrapper owns
 * the overflow.
 */
function TableWrap({ children }: { children?: ReactNode }) {
  return (
    <div className="blog-detail__table-wrap">
      <table>{children}</table>
    </div>
  )
}

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  return (
    <ReactMarkdown
      components={{
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        code: CodeBlock as any,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        a: SmartLink as any,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        table: TableWrap as any,
      }}
      rehypePlugins={[rehypeRaw]}
      remarkPlugins={[remarkGfm]}
    >
      {content}
    </ReactMarkdown>
  )
}
