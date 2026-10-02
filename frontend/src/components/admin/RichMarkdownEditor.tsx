import { useCallback, useRef, useState } from 'react'
import {
  MDXEditor,
  BlockTypeSelect,
  BoldItalicUnderlineToggles,
  CodeToggle,
  CreateLink,
  InsertCodeBlock,
  InsertImage,
  InsertTable,
  ListsToggle,
  codeBlockPlugin,
  codeMirrorPlugin,
  headingsPlugin,
  imagePlugin,
  linkDialogPlugin,
  linkPlugin,
  listsPlugin,
  markdownShortcutPlugin,
  quotePlugin,
  tablePlugin,
  thematicBreakPlugin,
  toolbarPlugin,
  type MDXEditorMethods,
} from '@mdxeditor/editor'
import '@mdxeditor/editor/style.css'
import { FolderOpen } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { useTheme } from '../../contexts/ThemeContext'
import { uploadAdminMedia } from '../../services/adminApi'
import { MediaLibrary } from './MediaLibrary'
import { IMAGE_TYPES } from './mediaTypes'

const CODE_LANGUAGES = {
  '': 'Plain Text',
  bash: 'Bash',
  css: 'CSS',
  html: 'HTML',
  java: 'Java',
  js: 'JavaScript',
  json: 'JSON',
  kotlin: 'Kotlin',
  python: 'Python',
  shell: 'Shell',
  sql: 'SQL',
  ts: 'TypeScript',
  tsx: 'TSX',
  xml: 'XML',
  yaml: 'YAML',
}

interface RichMarkdownEditorProps {
  /** The starting text. Read once on mount, so give the editor a `key` to load a different one. */
  markdown: string
  onChange: (markdown: string) => void
  /** Names the editor for assistive technology and tests. */
  label: string
  placeholder?: string
}

/**
 * The admin's markdown editor, as the blog editor uses it, plus tables: a project page's
 * sub-pages are tables as often as prose. Images go into the media library, uploaded or picked.
 */
export function RichMarkdownEditor({ markdown, onChange, label, placeholder }: RichMarkdownEditorProps) {
  const { getAccessToken } = useAuth()
  const { theme } = useTheme()
  const editorRef = useRef<MDXEditorMethods>(null)
  const [showLibrary, setShowLibrary] = useState(false)

  const imageUploadHandler = useCallback(async (file: File) => {
    const asset = await uploadAdminMedia(getAccessToken, file)
    return asset.originalPath
  }, [getAccessToken])

  return (
    <div aria-label={label} className="blog-editor__content rich-markdown-editor" role="group">
      <MDXEditor
        // The editor's own palette is light; its dark one keeps the toolbar legible on this site.
        className={theme === 'dark' ? 'dark-theme' : undefined}
        markdown={markdown}
        // The editor re-serialises what it was given as soon as it mounts. That is not an edit:
        // passing it on would mark an untouched page as changed and rewrite the stored copy.
        onChange={(value, initialMarkdownNormalize) => {
          if (!initialMarkdownNormalize) onChange(value)
        }}
        placeholder={placeholder}
        // Written the way the seeded copy is, so one edit does not rewrite every list on the page.
        toMarkdownOptions={{ bullet: '-' }}
        plugins={[
          headingsPlugin(),
          listsPlugin(),
          quotePlugin(),
          thematicBreakPlugin(),
          linkPlugin(),
          linkDialogPlugin(),
          tablePlugin(),
          imagePlugin({ imageUploadHandler }),
          codeBlockPlugin({ defaultCodeBlockLanguage: '' }),
          codeMirrorPlugin({ codeBlockLanguages: CODE_LANGUAGES }),
          markdownShortcutPlugin(),
          toolbarPlugin({
            toolbarContents: () => (
              <>
                <BoldItalicUnderlineToggles />
                <BlockTypeSelect />
                <ListsToggle />
                <CodeToggle />
                <CreateLink />
                <InsertImage />
                <InsertTable />
                <InsertCodeBlock />
                <button
                  className="mdx-library-btn"
                  onClick={() => setShowLibrary(true)}
                  title="Insert from Media Library"
                  type="button"
                >
                  <FolderOpen size={16} />
                  Library
                </button>
              </>
            ),
          }),
        ]}
        ref={editorRef}
      />
      {showLibrary && (
        <MediaLibrary
          accept={IMAGE_TYPES}
          onClose={() => setShowLibrary(false)}
          onSelect={(asset) => {
            editorRef.current?.insertMarkdown(`![${asset.fileName}](${asset.originalPath})`)
            setShowLibrary(false)
          }}
        />
      )}
    </div>
  )
}
