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
import { useAuth } from '../../auth/useAuth'
import {
  createAdminJob,
  fetchAdminJobById,
  fetchAdminSkills,
  updateAdminJob,
  uploadAdminMedia,
  type AdminSkill,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import { TogglePill } from '../../components/admin/TogglePill'
import { TagInput } from '../../components/admin/TagInput'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { MediaLibrary } from '../../components/admin/MediaLibrary'

interface JobFormState {
  title: string
  company: string
  companyUrl: string
  companyImage: { url: string } | null
  startDate: string
  endDate: string
  location: string
  shortDescription: string
  longDescription: string
  education: boolean
  includeOnResume: boolean
  skills: string[]
}

const emptyForm: JobFormState = {
  title: '',
  company: '',
  companyUrl: '',
  companyImage: null,
  startDate: '',
  endDate: '',
  location: '',
  shortDescription: '',
  longDescription: '',
  education: false,
  includeOnResume: true,
  skills: [],
}

export function JobEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const editorRef = useRef<MDXEditorMethods>(null)
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<JobFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)
  const [showMediaLibrary, setShowMediaLibrary] = useState(false)
  const [editorKey, setEditorKey] = useState(0)

  useUnsavedChanges(dirty)

  useEffect(() => {
    const loadSkills = async () => {
      try {
        const skillPage = await fetchAdminSkills(getAccessToken, 0, 100)
        setSkills(skillPage.content)
      } catch {
        // non-fatal
      }
    }
    loadSkills()
  }, [getAccessToken])

  const loadJob = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const job = await fetchAdminJobById(getAccessToken, id)
      setForm({
        title: job.title,
        company: job.company,
        companyUrl: job.companyUrl ?? '',
        companyImage: job.companyImage ?? null,
        startDate: job.startDate,
        endDate: job.endDate ?? '',
        location: job.location ?? '',
        shortDescription: job.shortDescription ?? '',
        longDescription: job.longDescription ?? '',
        education: job.education,
        includeOnResume: job.includeOnResume,
        skills: job.skills ?? [],
      })
      setEditorKey((k) => k + 1)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load job')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadJob()
  }, [loadJob])

  const imageUploadHandler = useCallback(async (file: File) => {
    const asset = await uploadAdminMedia(getAccessToken, file)
    return asset.originalPath
  }, [getAccessToken])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const longDescription = editorRef.current?.getMarkdown() ?? form.longDescription
      const payload = {
        title: form.title,
        company: form.company,
        companyUrl: form.companyUrl,
        companyImage: form.companyImage,
        startDate: form.startDate,
        endDate: form.endDate,
        location: form.location,
        shortDescription: form.shortDescription,
        longDescription,
        education: form.education,
        includeOnResume: form.includeOnResume,
        skills: form.skills,
      }
      if (isNew) {
        await createAdminJob(getAccessToken, payload)
      } else {
        await updateAdminJob(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/jobs')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div>Loading...</div>

  return (
    <div className="blog-editor">
      <div className="blog-editor__header">
        <h1>{isNew ? 'New Job' : 'Edit Job'}</h1>
        <div style={{ display: 'flex', gap: '0.75rem' }}>
          <TogglePill
            activeLabel="Education"
            inactiveLabel="Not Education"
            checked={form.education}
            onChange={(v) => { setForm({ ...form, education: v }); setDirty(true) }}
          />
          <TogglePill
            activeLabel="On Resume"
            inactiveLabel="Off Resume"
            checked={form.includeOnResume}
            onChange={(v) => { setForm({ ...form, includeOnResume: v }); setDirty(true) }}
          />
        </div>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="blog-editor__top-row">
          <div className="blog-editor__top-left">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Title</label>
              <input
                type="text"
                className="admin-form__input"
                value={form.title}
                onChange={(e) => { setForm({ ...form, title: e.target.value }); setDirty(true) }}
                required
              />
            </div>
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Company</label>
              <input
                type="text"
                className="admin-form__input"
                value={form.company}
                onChange={(e) => { setForm({ ...form, company: e.target.value }); setDirty(true) }}
                required
              />
            </div>
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Company URL</label>
              <input
                type="text"
                className="admin-form__input"
                value={form.companyUrl}
                onChange={(e) => { setForm({ ...form, companyUrl: e.target.value }); setDirty(true) }}
              />
            </div>
          </div>
          <div className="blog-editor__top-right">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Company Image</label>
              <ImagePicker
                value={form.companyImage?.url ?? null}
                onChange={(url) => {
                  setForm({ ...form, companyImage: url ? { url } : null })
                  setDirty(true)
                }}
                onUpload={async (file) => {
                  const asset = await uploadAdminMedia(getAccessToken, file)
                  return asset.originalPath
                }}
                onBrowse={() => setShowMediaLibrary(true)}
              />
            </div>
          </div>
        </div>

        <div className="blog-editor__two-col">
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">Start Date</label>
            <input
              type="date"
              className="admin-form__input"
              value={form.startDate}
              onChange={(e) => { setForm({ ...form, startDate: e.target.value }); setDirty(true) }}
              required
            />
          </div>
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">End Date</label>
            <input
              type="date"
              className="admin-form__input"
              value={form.endDate}
              onChange={(e) => { setForm({ ...form, endDate: e.target.value }); setDirty(true) }}
            />
          </div>
        </div>

        <div className="blog-editor__two-col">
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">Location</label>
            <input
              type="text"
              className="admin-form__input"
              value={form.location}
              onChange={(e) => { setForm({ ...form, location: e.target.value }); setDirty(true) }}
            />
          </div>
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">Skills</label>
            <TagInput
              options={skills.map((s) => ({ id: s.id, name: s.name }))}
              selected={form.skills}
              onChange={(ids) => { setForm({ ...form, skills: ids }); setDirty(true) }}
              placeholder="Add skills..."
            />
          </div>
        </div>

        <div className="blog-editor__section">
          <label className="blog-editor__section-label">Short Description</label>
          <textarea
            className="admin-form__input admin-form__textarea"
            value={form.shortDescription}
            onChange={(e) => { setForm({ ...form, shortDescription: e.target.value }); setDirty(true) }}
            rows={3}
          />
        </div>

        <div className="blog-editor__section blog-editor__content">
          <label className="blog-editor__section-label">Long Description</label>
          <MDXEditor
            key={editorKey}
            ref={editorRef}
            markdown={form.longDescription}
            onChange={(val) => { setForm((f) => ({ ...f, longDescription: val })); setDirty(true) }}
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
          <button type="button" className="admin-btn" onClick={() => navigate('/admin/jobs')}>
            Cancel
          </button>
        </div>
      </form>

      {showMediaLibrary && (
        <MediaLibrary
          onSelect={(asset) => {
            setForm({ ...form, companyImage: { url: asset.originalPath } })
            setDirty(true)
          }}
          onClose={() => setShowMediaLibrary(false)}
        />
      )}
    </div>
  )
}
