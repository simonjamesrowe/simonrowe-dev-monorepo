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
  codeBlockPlugin,
  codeMirrorPlugin,
  markdownShortcutPlugin,
  toolbarPlugin,
  BoldItalicUnderlineToggles,
  BlockTypeSelect,
  CreateLink,
  InsertCodeBlock,
  ListsToggle,
  CodeToggle,
  type MDXEditorMethods,
} from '@mdxeditor/editor'
import '@mdxeditor/editor/style.css'
import { useAuth } from '../../auth/useAuth'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import { TagInput } from '../../components/admin/TagInput'
import {
  fetchAdminCodeExampleById,
  createAdminCodeExample,
  updateAdminCodeExample,
  fetchAdminSkills,
  type AdminSkill,
} from '../../services/adminApi'

const LANGUAGE_OPTIONS = [
  { value: 'java', label: 'Java' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'python', label: 'Python' },
  { value: 'go', label: 'Go' },
  { value: 'kotlin', label: 'Kotlin' },
  { value: 'bash', label: 'Bash' },
  { value: 'sql', label: 'SQL' },
  { value: 'yaml', label: 'YAML' },
  { value: 'json', label: 'JSON' },
  { value: 'other', label: 'Other' },
]

interface CodeExampleFormState {
  title: string
  description: string
  language: string
  code: string
  skills: string[]
}

const emptyForm: CodeExampleFormState = {
  title: '',
  description: '',
  language: 'java',
  code: '',
  skills: [],
}

export function CodeExampleEditor() {
  const { id } = useParams()
  const isNew = !id || id === 'new'
  const navigate = useNavigate()
  const { getAccessToken } = useAuth()
  const descriptionRef = useRef<MDXEditorMethods>(null)
  const codeRef = useRef<MDXEditorMethods>(null)
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [form, setForm] = useState<CodeExampleFormState>(emptyForm)
  const [dirty, setDirty] = useState(false)
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

  const loadCodeExample = useCallback(async () => {
    if (isNew || !id) return
    try {
      setLoading(true)
      const example = await fetchAdminCodeExampleById(getAccessToken, id)
      setForm({
        title: example.title,
        description: example.description,
        language: example.language,
        code: example.code,
        skills: example.skills ?? [],
      })
      setEditorKey((k) => k + 1)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load code example')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, id, isNew])

  useEffect(() => {
    loadCodeExample()
  }, [loadCodeExample])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setSaving(true)
      setError(null)
      const description = descriptionRef.current?.getMarkdown() ?? form.description
      const code = codeRef.current?.getMarkdown() ?? form.code
      const payload = {
        title: form.title,
        description,
        language: form.language,
        code,
        skills: form.skills,
      }
      if (isNew) {
        await createAdminCodeExample(getAccessToken, payload)
      } else {
        await updateAdminCodeExample(getAccessToken, id!, payload)
      }
      setDirty(false)
      navigate('/admin/code-examples')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div>Loading...</div>

  const mdxPlugins = [
    headingsPlugin(),
    listsPlugin(),
    quotePlugin(),
    thematicBreakPlugin(),
    linkPlugin(),
    linkDialogPlugin(),
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
        go: 'Go',
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
          <InsertCodeBlock />
        </>
      ),
    }),
  ]

  return (
    <div className="blog-editor">
      <div className="blog-editor__header">
        <h1>{isNew ? 'New Code Example' : 'Edit Code Example'}</h1>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="blog-editor__two-col">
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">Title</label>
            <input
              type="text"
              className="admin-form__input"
              value={form.title}
              onChange={(e) => { setForm({ ...form, title: e.target.value }); setDirty(true) }}
              maxLength={200}
              required
            />
          </div>
          <div className="blog-editor__section">
            <label className="blog-editor__section-label">Language</label>
            <select
              className="admin-form__input"
              value={form.language}
              onChange={(e) => { setForm({ ...form, language: e.target.value }); setDirty(true) }}
            >
              {LANGUAGE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
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

        <div className="blog-editor__section blog-editor__content">
          <label className="blog-editor__section-label">Description</label>
          <MDXEditor
            key={`desc-${editorKey}`}
            ref={descriptionRef}
            markdown={form.description}
            onChange={(val) => { setForm((f) => ({ ...f, description: val })); setDirty(true) }}
            plugins={mdxPlugins}
          />
        </div>

        <div className="blog-editor__section blog-editor__content">
          <label className="blog-editor__section-label">Code</label>
          <MDXEditor
            key={`code-${editorKey}`}
            ref={codeRef}
            markdown={form.code}
            onChange={(val) => { setForm((f) => ({ ...f, code: val })); setDirty(true) }}
            plugins={mdxPlugins}
          />
        </div>

        <div className="form-actions">
          <button type="submit" className="admin-btn admin-btn--primary" disabled={saving}>
            {saving ? 'Saving...' : 'Save'}
          </button>
          <button
            type="button"
            className="admin-btn"
            onClick={() => navigate('/admin/code-examples')}
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
