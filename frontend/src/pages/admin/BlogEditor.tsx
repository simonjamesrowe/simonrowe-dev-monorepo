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
  createAdminBlog,
  fetchAdminBlogById,
  fetchAdminSkills,
  fetchAdminTags,
  updateAdminBlog,
  uploadAdminMedia,
  type AdminSkill,
  type AdminTag,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import { TogglePill } from '../../components/admin/TogglePill'
import { TagInput } from '../../components/admin/TagInput'
import { ImagePicker } from '../../components/admin/ImagePicker'
import { MediaLibrary } from '../../components/admin/MediaLibrary'
import type { BlogContentType } from '../../types/blog'

interface BlogFormState {
  title: string
  shortDescription: string
  content: string
  published: boolean
  featuredImageUrl: string
  tags: string[]
  skills: string[]
  contentType: BlogContentType
}

const emptyForm: BlogFormState = {
  title: '',
  shortDescription: '',
  content: '',
  published: false,
  featuredImageUrl: '',
  tags: [],
  skills: [],
  // Preselected rather than an empty option, so the default is visible not implied.
  contentType: 'ENGINEERING',
}

export function BlogEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const editorRef = useRef<MDXEditorMethods>(null)
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tags, setTags] = useState<AdminTag[]>([])
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<BlogFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)
  const [showMediaLibrary, setShowMediaLibrary] = useState(false)
  const [editorKey, setEditorKey] = useState(0)

  useUnsavedChanges(dirty)

  useEffect(() => {
    const loadOptions = async () => {
      try {
        const [tagList, skillPage] = await Promise.all([
          fetchAdminTags(getAccessToken),
          fetchAdminSkills(getAccessToken, 0, 100),
        ])
        setTags(tagList)
        setSkills(skillPage.content)
      } catch {
        // non-fatal: options just won't appear
      }
    }
    loadOptions()
  }, [getAccessToken])

  const loadBlog = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const blog = await fetchAdminBlogById(getAccessToken, id)
      setForm({
        title: blog.title,
        shortDescription: blog.shortDescription,
        content: blog.content ?? '',
        published: blog.published,
        featuredImageUrl: blog.featuredImageUrl ?? '',
        tags: blog.tags ?? [],
        skills: blog.skills ?? [],
        contentType: blog.contentType ?? 'ENGINEERING',
      })
      setEditorKey((k) => k + 1)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load blog')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadBlog()
  }, [loadBlog])

  const imageUploadHandler = useCallback(async (file: File) => {
    const asset = await uploadAdminMedia(getAccessToken, file)
    return asset.originalPath
  }, [getAccessToken])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const content = editorRef.current?.getMarkdown() ?? form.content
      const payload = {
        title: form.title,
        shortDescription: form.shortDescription,
        content,
        published: form.published,
        featuredImageUrl: form.featuredImageUrl,
        tags: form.tags,
        skills: form.skills,
        contentType: form.contentType,
      }
      if (isNew) {
        await createAdminBlog(getAccessToken, payload)
      } else {
        await updateAdminBlog(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/blogs')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  const handleInsertFromLibrary = () => {
    setShowMediaLibrary(true)
  }

  if (loading) return <div>Loading...</div>

  return (
    <div className="blog-editor">
      <div className="blog-editor__header">
        <h1>{isNew ? 'New Blog' : 'Edit Blog'}</h1>
        <TogglePill
          checked={form.published}
          onChange={(checked) => { setForm({ ...form, published: checked }); setDirty(true) }}
        />
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
              <label className="blog-editor__section-label">Short Description</label>
              <textarea
                className="admin-form__input admin-form__textarea"
                value={form.shortDescription}
                onChange={(e) => { setForm({ ...form, shortDescription: e.target.value }); setDirty(true) }}
                required
                rows={3}
              />
            </div>

            <div className="blog-editor__section">
              <label className="blog-editor__section-label" htmlFor="contentType">
                Content type
              </label>
              <select
                className="admin-form__input"
                id="contentType"
                value={form.contentType}
                onChange={(e) => {
                  setForm({ ...form, contentType: e.target.value as BlogContentType })
                  setDirty(true)
                }}
              >
                <option value="ENGINEERING">Engineering</option>
                <option value="DIGEST">Weekly Digest</option>
              </select>
            </div>
          </div>

          <div className="blog-editor__top-right">
            <div className="blog-editor__section">
              <label className="blog-editor__section-label">Featured Image</label>
              <ImagePicker
                value={form.featuredImageUrl || null}
                onChange={(url) => { setForm({ ...form, featuredImageUrl: url }); setDirty(true) }}
              />
            </div>
          </div>
        </div>

        <div className="blog-editor__section blog-editor__two-col">
          <div>
            <label className="blog-editor__section-label">Tags</label>
            <TagInput
              options={tags.map((t) => ({ id: t.id, name: t.name }))}
              selected={form.tags}
              onChange={(selected) => { setForm({ ...form, tags: selected }); setDirty(true) }}
              placeholder="Search tags..."
            />
          </div>
          <div>
            <label className="blog-editor__section-label">Skills</label>
            <TagInput
              options={skills.map((s) => ({ id: s.id, name: s.name }))}
              selected={form.skills}
              onChange={(selected) => { setForm({ ...form, skills: selected }); setDirty(true) }}
              placeholder="Search skills..."
            />
          </div>
        </div>

        <div className="blog-editor__section blog-editor__content">
          <label className="blog-editor__section-label">Content</label>
          <MDXEditor
            key={editorKey}
            ref={editorRef}
            markdown={form.content}
            onChange={(val) => { setForm((f) => ({ ...f, content: val })); setDirty(true) }}
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
          <button type="submit" className="admin-btn admin-btn--primary" disabled={saving}>
            {saving ? 'Saving...' : 'Save'}
          </button>
          <button type="button" className="admin-btn" onClick={() => navigate('/admin/blogs')}>
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
