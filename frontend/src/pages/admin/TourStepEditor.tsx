import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  MDXEditor,
  headingsPlugin,
  listsPlugin,
  quotePlugin,
  thematicBreakPlugin,
  linkPlugin,
  linkDialogPlugin,
  imagePlugin,
  codeBlockPlugin,
  codeMirrorPlugin,
  markdownShortcutPlugin,
  toolbarPlugin,
  BoldItalicUnderlineToggles,
  BlockTypeSelect,
  CreateLink,
  InsertImage,
  InsertCodeBlock,
  ListsToggle,
  CodeToggle,
  type MDXEditorMethods,
} from '@mdxeditor/editor'
import '@mdxeditor/editor/style.css'
import { FolderOpen } from 'lucide-react'
import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminTourStepById,
  createAdminTourStep,
  updateAdminTourStep,
  uploadAdminMedia,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { MediaLibrary } from '../../components/admin/MediaLibrary'

type Position = 'top' | 'bottom' | 'left' | 'right' | 'center'

interface TourStepFormState {
  title: string
  selector: string
  description: string
  titleImage: string
  position: Position | ''
  order: number
}

const emptyForm = (): TourStepFormState => ({
  title: '',
  selector: '',
  description: '',
  titleImage: '',
  position: '',
  order: 0,
})

const POSITIONS: Position[] = ['top', 'bottom', 'left', 'right', 'center']

export function TourStepEditor() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const editorRef = useRef<MDXEditorMethods>(null)

  const isNew = !id || id === 'new'

  const [form, setForm] = useState<TourStepFormState>(emptyForm())
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)
  const [showMediaLibrary, setShowMediaLibrary] = useState(false)
  const [editorKey, setEditorKey] = useState(0)

  useUnsavedChanges(dirty)

  const loadStep = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      setError(null)
      const data = await fetchAdminTourStepById(getAccessToken, id)
      setForm({
        title: data.title,
        selector: data.selector,
        description: data.description ?? '',
        titleImage: data.titleImage ?? '',
        position: (data.position as Position) ?? '',
        order: data.order,
      })
      setEditorKey((k) => k + 1)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load tour step')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadStep()
  }, [loadStep])

  const imageUploadHandler = useCallback(async (file: File) => {
    const asset = await uploadAdminMedia(getAccessToken, file)
    return asset.originalPath
  }, [getAccessToken])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const description = editorRef.current?.getMarkdown() ?? form.description
      const payload: Record<string, unknown> = {
        ...form,
        description: description || null,
        position: form.position || null,
        titleImage: form.titleImage || null,
      }
      if (isNew) {
        await createAdminTourStep(getAccessToken, payload)
      } else {
        await updateAdminTourStep(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/tour-steps')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save tour step')
    } finally {
      setSaving(false)
    }
  }

  const handleInsertFromLibrary = () => {
    setShowMediaLibrary(true)
  }

  if (loading) {
    return <div className="admin-loading">Loading tour step...</div>
  }

  return (
    <div className="blog-editor">
      <div className="blog-editor__header">
        <h1>{isNew ? 'New Tour Step' : 'Edit Tour Step'}</h1>
        <button
          className="admin-btn"
          onClick={() => navigate('/admin/tour-steps')}
          type="button"
        >
          Back to List
        </button>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="blog-editor__top-row">
          <div className="blog-editor__top-left">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="title">Title</label>
              <input
                className="admin-form__input"
                id="title"
                name="title"
                onChange={(e) => { setForm((f) => ({ ...f, title: e.target.value })); setDirty(true) }}
                required
                type="text"
                value={form.title}
              />
            </div>

            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="selector">CSS Selector</label>
              <input
                className="admin-form__input admin-form__input--mono"
                id="selector"
                name="selector"
                onChange={(e) => { setForm((f) => ({ ...f, selector: e.target.value })); setDirty(true) }}
                placeholder=".my-element or #element-id"
                required
                type="text"
                value={form.selector}
              />
            </div>
          </div>

          <div className="blog-editor__top-right">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Title Image</label>
              <ImagePicker
                value={form.titleImage || null}
                onChange={(url) => { setForm((f) => ({ ...f, titleImage: url })); setDirty(true) }}
              />
            </div>
          </div>
        </div>

        <div className="blog-editor__section blog-editor__two-col">
          <div>
            <label className="blog-editor__section-label" htmlFor="position">Position</label>
            <select
              className="admin-form__select"
              id="position"
              name="position"
              onChange={(e) => { setForm((f) => ({ ...f, position: e.target.value as Position | '' })); setDirty(true) }}
              value={form.position}
            >
              <option value="">-- none --</option>
              {POSITIONS.map((pos) => (
                <option key={pos} value={pos}>
                  {pos.charAt(0).toUpperCase() + pos.slice(1)}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="blog-editor__section-label" htmlFor="order">Order</label>
            <input
              className="admin-form__input admin-form__input--narrow"
              id="order"
              min={0}
              name="order"
              onChange={(e) => { setForm((f) => ({ ...f, order: Number(e.target.value) })); setDirty(true) }}
              type="number"
              value={form.order}
            />
          </div>
        </div>

        <div className="blog-editor__section blog-editor__content">
          <label className="blog-editor__section-label">Description</label>
          <MDXEditor
            key={editorKey}
            ref={editorRef}
            markdown={form.description}
            onChange={(val) => { setForm((f) => ({ ...f, description: val })); setDirty(true) }}
            plugins={[
              headingsPlugin(),
              listsPlugin(),
              quotePlugin(),
              thematicBreakPlugin(),
              linkPlugin(),
              linkDialogPlugin(),
              imagePlugin({ imageUploadHandler }),
              codeBlockPlugin({ defaultCodeBlockLanguage: '' }),
              codeMirrorPlugin({
                codeBlockLanguages: {
                  '': 'Plain Text',
                  js: 'JavaScript',
                  ts: 'TypeScript',
                  tsx: 'TSX',
                  jsx: 'JSX',
                  java: 'Java',
                  kotlin: 'Kotlin',
                  python: 'Python',
                  css: 'CSS',
                  html: 'HTML',
                  json: 'JSON',
                  yaml: 'YAML',
                  bash: 'Bash',
                  shell: 'Shell',
                  sql: 'SQL',
                  xml: 'XML',
                  dockerfile: 'Dockerfile',
                  groovy: 'Groovy',
                },
              }),
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
                    <InsertCodeBlock />
                    <button
                      className="mdx-library-btn"
                      type="button"
                      title="Insert from Media Library"
                      onClick={handleInsertFromLibrary}
                    >
                      <FolderOpen size={16} />
                      Library
                    </button>
                  </>
                ),
              }),
            ]}
          />
        </div>

        <div className="form-actions">
          <button
            className="admin-btn admin-btn--primary"
            disabled={saving}
            type="submit"
          >
            {saving ? 'Saving...' : isNew ? 'Create Step' : 'Save Changes'}
          </button>
          <button
            className="admin-btn"
            onClick={() => navigate('/admin/tour-steps')}
            type="button"
          >
            Cancel
          </button>
        </div>
      </form>

      {showMediaLibrary && (
        <MediaLibrary
          onSelect={(asset) => {
            editorRef.current?.insertMarkdown(`![${asset.fileName}](${asset.originalPath})`)
            setShowMediaLibrary(false)
            setDirty(true)
          }}
          onClose={() => setShowMediaLibrary(false)}
        />
      )}
    </div>
  )
}
