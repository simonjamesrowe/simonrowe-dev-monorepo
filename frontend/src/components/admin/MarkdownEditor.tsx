import { useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'

interface MarkdownEditorProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
}

type Tab = 'write' | 'preview'

function insertSyntax(
  textarea: HTMLTextAreaElement,
  before: string,
  after: string,
  defaultText: string,
): string {
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  const selected = textarea.value.slice(start, end)
  const insertion = selected.length > 0 ? selected : defaultText
  return (
    textarea.value.slice(0, start) +
    before +
    insertion +
    after +
    textarea.value.slice(end)
  )
}

function insertAtLineStart(
  textarea: HTMLTextAreaElement,
  prefix: string,
  defaultText: string,
): string {
  const start = textarea.selectionStart
  const lineStart = textarea.value.lastIndexOf('\n', start - 1) + 1
  const selected = textarea.value.slice(start, textarea.selectionEnd)
  const insertion = selected.length > 0 ? selected : defaultText
  return (
    textarea.value.slice(0, lineStart) +
    prefix +
    textarea.value.slice(lineStart, start) +
    insertion +
    textarea.value.slice(textarea.selectionEnd)
  )
}

export function MarkdownEditor({ value, onChange, placeholder }: MarkdownEditorProps) {
  const [tab, setTab] = useState<Tab>('write')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  function applyInline(before: string, after: string, defaultText: string) {
    const textarea = textareaRef.current
    if (!textarea) return
    const newValue = insertSyntax(textarea, before, after, defaultText)
    onChange(newValue)
    requestAnimationFrame(() => {
      textarea.focus()
    })
  }

  function applyLinePrefix(prefix: string, defaultText: string) {
    const textarea = textareaRef.current
    if (!textarea) return
    const newValue = insertAtLineStart(textarea, prefix, defaultText)
    onChange(newValue)
    requestAnimationFrame(() => {
      textarea.focus()
    })
  }

  function handleLink() {
    const textarea = textareaRef.current
    if (!textarea) return
    const selected = textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)
    const label = selected.length > 0 ? selected : 'link text'
    const start = textarea.selectionStart
    const newValue =
      textarea.value.slice(0, start) +
      `[${label}](url)` +
      textarea.value.slice(textarea.selectionEnd)
    onChange(newValue)
    requestAnimationFrame(() => {
      textarea.focus()
    })
  }

  function handleImage() {
    const textarea = textareaRef.current
    if (!textarea) return
    const start = textarea.selectionStart
    const newValue =
      textarea.value.slice(0, start) +
      `![alt text](image-url)` +
      textarea.value.slice(start)
    onChange(newValue)
    requestAnimationFrame(() => {
      textarea.focus()
    })
  }

  return (
    <div className="md-editor">
      <div className="md-editor__tabs">
        <button
          className={`md-editor__tab${tab === 'write' ? ' md-editor__tab--active' : ''}`}
          onClick={() => setTab('write')}
          type="button"
        >
          Write
        </button>
        <button
          className={`md-editor__tab${tab === 'preview' ? ' md-editor__tab--active' : ''}`}
          onClick={() => setTab('preview')}
          type="button"
        >
          Preview
        </button>
      </div>

      {tab === 'write' && (
        <div className="md-editor__toolbar">
          <button
            className="md-editor__toolbar-btn"
            onClick={() => applyInline('**', '**', 'bold text')}
            title="Bold"
            type="button"
          >
            <strong>B</strong>
          </button>
          <button
            className="md-editor__toolbar-btn"
            onClick={() => applyInline('_', '_', 'italic text')}
            title="Italic"
            type="button"
          >
            <em>I</em>
          </button>
          <button
            className="md-editor__toolbar-btn"
            onClick={() => applyLinePrefix('## ', 'Heading')}
            title="Heading"
            type="button"
          >
            H
          </button>
          <button
            className="md-editor__toolbar-btn"
            onClick={handleLink}
            title="Link"
            type="button"
          >
            Link
          </button>
          <button
            className="md-editor__toolbar-btn"
            onClick={() => applyInline('`', '`', 'code')}
            title="Inline code"
            type="button"
          >
            {'</>'}
          </button>
          <button
            className="md-editor__toolbar-btn"
            onClick={handleImage}
            title="Image"
            type="button"
          >
            Img
          </button>
        </div>
      )}

      {tab === 'write' ? (
        <textarea
          className="md-editor__textarea"
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          ref={textareaRef}
          value={value}
        />
      ) : (
        <div className="md-editor__preview">
          {value.trim() ? (
            <ReactMarkdown>{value}</ReactMarkdown>
          ) : (
            <p className="md-editor__preview-empty">Nothing to preview.</p>
          )}
        </div>
      )}
    </div>
  )
}
