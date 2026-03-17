import { useCallback, useEffect, useRef, useState } from 'react'
import {
  MDXEditor,
  headingsPlugin,
  listsPlugin,
  quotePlugin,
  thematicBreakPlugin,
  linkPlugin,
  linkDialogPlugin,
  imagePlugin,
  markdownShortcutPlugin,
  toolbarPlugin,
  BoldItalicUnderlineToggles,
  BlockTypeSelect,
  CreateLink,
  ListsToggle,
  CodeToggle,
  type MDXEditorMethods,
} from '@mdxeditor/editor'
import '@mdxeditor/editor/style.css'
import { X } from 'lucide-react'
import { ImagePicker } from './ImagePicker'
import {
  createAdminSkill,
  fetchAdminSkillById,
  updateAdminSkill,
  uploadAdminMedia,
  type AdminSkill,
  type GetAccessToken,
} from '../../services/adminApi'

interface SkillEditorModalProps {
  open: boolean
  skillId: string | null
  getAccessToken: GetAccessToken
  onSave: (skill: AdminSkill) => void
  onClose: () => void
}

interface SkillFormState {
  name: string
  rating: number
  description: string
  image: string
  order: number
}

const emptyForm: SkillFormState = {
  name: '',
  rating: 0,
  description: '',
  image: '',
  order: 0,
}

export function SkillEditorModal({ open, skillId, getAccessToken, onSave, onClose }: SkillEditorModalProps) {
  const editorRef = useRef<MDXEditorMethods>(null)
  const [form, setForm] = useState<SkillFormState>(emptyForm)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editorKey, setEditorKey] = useState(0)

  useEffect(() => {
    if (!open) return
    setError(null)
    if (skillId) {
      const load = async () => {
        try {
          setLoading(true)
          const skill = await fetchAdminSkillById(getAccessToken, skillId)
          setForm({
            name: skill.name,
            rating: skill.rating ?? 0,
            description: skill.description ?? '',
            image: skill.image?.url ?? '',
            order: skill.order,
          })
          setEditorKey((k) => k + 1)
        } catch (err) {
          setError(err instanceof Error ? err.message : 'Failed to load skill')
        } finally {
          setLoading(false)
        }
      }
      load()
    } else {
      setForm(emptyForm)
      setEditorKey((k) => k + 1)
    }
  }, [open, skillId, getAccessToken])

  useEffect(() => {
    if (!open) return
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [open, onClose])

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
      const payload = {
        name: form.name,
        rating: form.rating,
        description,
        image: form.image,
        order: form.order,
      }
      let saved: AdminSkill
      if (skillId) {
        saved = await updateAdminSkill(getAccessToken, skillId, payload)
      } else {
        saved = await createAdminSkill(getAccessToken, payload)
      }
      onSave(saved)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save skill')
    } finally {
      setSaving(false)
    }
  }

  if (!open) return null

  return (
    <div className="drawer-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div className="drawer" onClick={(e) => e.stopPropagation()}>
        <div className="drawer__header">
          <h2 className="drawer__title">{skillId ? 'Edit Skill' : 'New Skill'}</h2>
          <button type="button" className="drawer__close" onClick={onClose} title="Close">
            <X size={18} />
          </button>
        </div>

        <div className="drawer__body">
          {error && <div className="admin-error-banner">{error}</div>}

          {loading ? (
            <div className="drawer__loading">Loading...</div>
          ) : (
            <form onSubmit={handleSubmit}>
              <div className="blog-editor__top-row">
                <div className="blog-editor__top-left">
                  <div className="blog-editor__section">
                    <label className="blog-editor__section-label">Name</label>
                    <input
                      type="text"
                      className="admin-form__input"
                      value={form.name}
                      onChange={(e) => setForm({ ...form, name: e.target.value })}
                      required
                    />
                  </div>
                  <div className="blog-editor__section">
                    <label className="blog-editor__section-label">Rating (0-10)</label>
                    <input
                      type="number"
                      className="admin-form__input"
                      min={0}
                      max={10}
                      value={form.rating}
                      onChange={(e) => setForm({ ...form, rating: Number(e.target.value) })}
                    />
                  </div>
                </div>
                <div className="blog-editor__top-right">
                  <div className="blog-editor__section">
                    <label className="blog-editor__section-label">Image</label>
                    <ImagePicker
                      value={form.image || null}
                      onChange={(url) => setForm({ ...form, image: url })}
                    />
                  </div>
                </div>
              </div>

              <div className="blog-editor__section blog-editor__content">
                <label className="blog-editor__section-label">Description</label>
                <MDXEditor
                  key={editorKey}
                  ref={editorRef}
                  markdown={form.description}
                  onChange={(val) => setForm((f) => ({ ...f, description: val }))}
                  plugins={[
                    headingsPlugin(),
                    listsPlugin(),
                    quotePlugin(),
                    thematicBreakPlugin(),
                    linkPlugin(),
                    linkDialogPlugin(),
                    imagePlugin({ imageUploadHandler }),
                    markdownShortcutPlugin(),
                    toolbarPlugin({
                      toolbarContents: () => (
                        <>
                          <BoldItalicUnderlineToggles />
                          <BlockTypeSelect />
                          <ListsToggle />
                          <CodeToggle />
                          <CreateLink />
                        </>
                      ),
                    }),
                  ]}
                />
              </div>

              <div className="form-actions">
                <button type="submit" className="admin-btn admin-btn--primary" disabled={saving}>
                  {saving ? 'Saving...' : 'Save'}
                </button>
                <button type="button" className="admin-btn" onClick={onClose}>
                  Cancel
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}
