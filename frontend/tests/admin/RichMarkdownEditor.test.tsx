import { render } from '@testing-library/react'
import { useEffect } from 'react'
import { describe, expect, it, vi } from 'vitest'

// The real editor needs a layout engine jsdom lacks. This stand-in does what it does on mount:
// re-serialise the markdown it was given, flagged as normalisation, then report a real edit.
vi.mock('@mdxeditor/editor', async () => {
  const stub = () => () => null
  return {
    MDXEditor: ({ markdown, onChange }: { markdown: string, onChange: (v: string, n: boolean) => void }) => {
      useEffect(() => {
        onChange(`${markdown}\n`, true)
        onChange(`${markdown} edited`, false)
      }, [markdown, onChange])
      return null
    },
    BlockTypeSelect: stub(), BoldItalicUnderlineToggles: stub(), CodeToggle: stub(),
    CreateLink: stub(), InsertCodeBlock: stub(), InsertImage: stub(), InsertTable: stub(),
    ListsToggle: stub(), codeBlockPlugin: vi.fn(), codeMirrorPlugin: vi.fn(),
    headingsPlugin: vi.fn(), imagePlugin: vi.fn(), linkDialogPlugin: vi.fn(), linkPlugin: vi.fn(),
    listsPlugin: vi.fn(), markdownShortcutPlugin: vi.fn(), quotePlugin: vi.fn(),
    tablePlugin: vi.fn(), thematicBreakPlugin: vi.fn(), toolbarPlugin: vi.fn(),
  }
})
vi.mock('@mdxeditor/editor/style.css', () => ({}))
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => ({ getAccessToken: vi.fn() }) }))
vi.mock('../../src/services/adminApi', () => ({ uploadAdminMedia: vi.fn() }))

import { RichMarkdownEditor } from '../../src/components/admin/RichMarkdownEditor'
import { ThemeProvider } from '../../src/contexts/ThemeContext'

describe('RichMarkdownEditor', () => {
  it('passes on edits but not the copy the editor makes of what it was given', () => {
    const onChange = vi.fn()
    render(<ThemeProvider><RichMarkdownEditor label="Body" markdown="- one" onChange={onChange} /></ThemeProvider>)

    // Passing the normalised copy on would mark an untouched page as changed.
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith('- one edited')
  })
})
