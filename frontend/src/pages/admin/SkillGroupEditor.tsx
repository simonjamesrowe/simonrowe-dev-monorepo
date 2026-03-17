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
import { FolderOpen, Pencil, Plus, Trash2 } from 'lucide-react'
import { useAuth } from '../../auth/useAuth'
import {
  createAdminSkillGroup,
  deleteAdminSkill,
  fetchAdminSkillGroupById,
  fetchAdminSkills,
  updateAdminSkillGroup,
  uploadAdminMedia,
  type AdminSkill,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { MediaLibrary } from '../../components/admin/MediaLibrary'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import { SkillEditorModal } from '../../components/admin/SkillEditorModal'

function parseSkillId(value: string): string {
  if (!value.startsWith('{')) return value
  try {
    const obj = JSON.parse(value)
    return obj._id?.$oid ?? obj._id ?? value
  } catch {
    return value
  }
}

interface SkillGroupFormState {
  name: string
  rating: number
  description: string
  image: string
  order: number
  skills: string[]
}

const emptyForm: SkillGroupFormState = {
  name: '',
  rating: 0,
  description: '',
  image: '',
  order: 0,
  skills: [],
}

export function SkillGroupEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const editorRef = useRef<MDXEditorMethods>(null)
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<SkillGroupFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)
  const [editorKey, setEditorKey] = useState(0)
  const [showMediaLibrary, setShowMediaLibrary] = useState(false)
  const [allSkills, setAllSkills] = useState<AdminSkill[]>([])
  const [skillModalOpen, setSkillModalOpen] = useState(false)
  const [editingSkillId, setEditingSkillId] = useState<string | null>(null)
  const [skillDeleteTarget, setSkillDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  useUnsavedChanges(dirty)

  const loadAllSkills = useCallback(async () => {
    try {
      const page = await fetchAdminSkills(getAccessToken, 0, 100)
      setAllSkills(page.content)
    } catch {
      // non-fatal
    }
  }, [getAccessToken])

  useEffect(() => {
    loadAllSkills()
  }, [loadAllSkills])

  const loadGroup = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const group = await fetchAdminSkillGroupById(getAccessToken, id)
      setForm({
        name: group.name,
        rating: group.rating ?? 0,
        description: group.description ?? '',
        image: group.image?.url ?? '',
        order: group.order,
        skills: (group.skills ?? []).map(parseSkillId),
      })
      setEditorKey((k) => k + 1)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load skill group')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadGroup()
  }, [loadGroup])

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
        skills: form.skills,
      }
      if (isNew) {
        await createAdminSkillGroup(getAccessToken, payload)
      } else {
        await updateAdminSkillGroup(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/skills')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  const handleInsertFromLibrary = () => {
    setShowMediaLibrary(true)
  }

  const handleSkillSaved = (skill: AdminSkill) => {
    setSkillModalOpen(false)
    setEditingSkillId(null)
    if (!form.skills.includes(skill.id)) {
      setForm((f) => ({ ...f, skills: [...f.skills, skill.id] }))
      setDirty(true)
    }
    loadAllSkills()
  }

  const handleDeleteSkillConfirm = async () => {
    if (!skillDeleteTarget) return
    try {
      await deleteAdminSkill(getAccessToken, skillDeleteTarget.id)
      setForm((f) => ({ ...f, skills: f.skills.filter((sid) => sid !== skillDeleteTarget.id) }))
      setDirty(true)
      setSkillDeleteTarget(null)
      loadAllSkills()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete skill')
      setSkillDeleteTarget(null)
    }
  }

  const linkedSkills = allSkills.filter((s) => form.skills.includes(s.id))

  if (loading) return <div>Loading...</div>

  return (
    <div className="blog-editor">
      <div className="blog-editor__header">
        <h1>{isNew ? 'New Skill Group' : 'Edit Skill Group'}</h1>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="blog-editor__top-row">
          <div className="blog-editor__top-left">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Name</label>
              <input
                type="text"
                className="admin-form__input"
                value={form.name}
                onChange={(e) => { setForm({ ...form, name: e.target.value }); setDirty(true) }}
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
                onChange={(e) => { setForm({ ...form, rating: Number(e.target.value) }); setDirty(true) }}
              />
            </div>
          </div>
          <div className="blog-editor__top-right">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Image</label>
              <ImagePicker
                value={form.image || null}
                onChange={(url) => { setForm({ ...form, image: url }); setDirty(true) }}
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
            onChange={(val) => { setForm((f) => ({ ...f, description: val })); setDirty(true) }}
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

        <div className="blog-editor__section">
          <div className="admin-header">
            <label className="blog-editor__section-label">Skills</label>
            <button
              type="button"
              className="admin-btn admin-btn--primary admin-btn--sm"
              onClick={() => { setEditingSkillId(null); setSkillModalOpen(true) }}
            >
              <Plus size={14} /> Add Skill
            </button>
          </div>
          {linkedSkills.length > 0 ? (
            <table className="admin-table">
              <thead>
                <tr>
                  <th style={{ width: 48 }}>Image</th>
                  <th>Name</th>
                  <th>Rating</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {linkedSkills.map((skill) => (
                  <tr key={skill.id}>
                    <td>
                      {skill.image?.url ? (
                        <img
                          src={skill.image.url}
                          alt={skill.name}
                          className="admin-table__thumb"
                        />
                      ) : (
                        <span className="admin-table__thumb-empty" />
                      )}
                    </td>
                    <td>{skill.name}</td>
                    <td>{skill.rating ?? '-'}</td>
                    <td>
                      <button
                        type="button"
                        className="admin-btn admin-btn--icon"
                        onClick={() => { setEditingSkillId(skill.id); setSkillModalOpen(true) }}
                        title="Edit"
                      >
                        <Pencil size={16} />
                      </button>
                      <button
                        type="button"
                        className="admin-btn admin-btn--icon admin-btn--danger-icon"
                        onClick={() => setSkillDeleteTarget({ id: skill.id, name: skill.name })}
                        title="Delete"
                      >
                        <Trash2 size={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p style={{ color: 'var(--color-muted)', fontSize: '0.88rem' }}>
              No skills linked to this group yet.
            </p>
          )}
        </div>

        <div className="form-actions">
          <button type="submit" className="admin-btn admin-btn--primary" disabled={saving}>
            {saving ? 'Saving...' : 'Save'}
          </button>
          <button type="button" className="admin-btn" onClick={() => navigate('/admin/skills')}>
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

      <SkillEditorModal
        open={skillModalOpen}
        skillId={editingSkillId}
        getAccessToken={getAccessToken}
        onSave={handleSkillSaved}
        onClose={() => { setSkillModalOpen(false); setEditingSkillId(null) }}
      />

      <ConfirmDialog
        open={skillDeleteTarget !== null}
        title="Delete Skill"
        message={`Are you sure you want to delete "${skillDeleteTarget?.name}"? This will permanently remove the skill.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteSkillConfirm}
        onCancel={() => setSkillDeleteTarget(null)}
      />
    </div>
  )
}
