import { createElement, forwardRef, useImperativeHandle, useRef, type ChangeEvent, type Ref } from 'react'

/**
 * A stand-in for `@mdxeditor/editor`, which does not render in jsdom. The editor becomes a
 * textarea that keeps its markdown behind the same imperative handle the real one exposes, so
 * a form reading `editorRef.current.getMarkdown()` on save still sees what was typed.
 *
 * Use it from a hoisted mock factory:
 *   vi.mock('@mdxeditor/editor', async () => (await import('../testUtils/mdxEditorMock')).mdxEditorMock)
 */
const MockMDXEditor = forwardRef(function MockMDXEditor(
  { markdown, onChange }: { markdown: string; onChange?: (value: string) => void },
  ref: Ref<{ getMarkdown: () => string; insertMarkdown: (value: string) => void }>,
) {
  const valueRef = useRef(markdown)
  useImperativeHandle(ref, () => ({
    getMarkdown: () => valueRef.current,
    insertMarkdown: (value: string) => { valueRef.current += value },
    setMarkdown: (value: string) => { valueRef.current = value },
    focus: () => {},
  }))
  return createElement('textarea', {
    'data-testid': 'mdx-editor',
    defaultValue: markdown,
    onChange: (e: ChangeEvent<HTMLTextAreaElement>) => {
      valueRef.current = e.target.value
      onChange?.(e.target.value)
    },
  })
})

const plugin = () => ({})
const toolbarItem = () => null

export const mdxEditorMock = {
  MDXEditor: MockMDXEditor,
  headingsPlugin: plugin,
  listsPlugin: plugin,
  quotePlugin: plugin,
  thematicBreakPlugin: plugin,
  linkPlugin: plugin,
  linkDialogPlugin: plugin,
  imagePlugin: plugin,
  tablePlugin: plugin,
  codeBlockPlugin: plugin,
  codeMirrorPlugin: plugin,
  markdownShortcutPlugin: plugin,
  toolbarPlugin: plugin,
  BoldItalicUnderlineToggles: toolbarItem,
  BlockTypeSelect: toolbarItem,
  CreateLink: toolbarItem,
  InsertImage: toolbarItem,
  InsertCodeBlock: toolbarItem,
  InsertTable: toolbarItem,
  ListsToggle: toolbarItem,
  CodeToggle: toolbarItem,
}
