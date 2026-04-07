import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Code2, Pencil, Plus, Trash2 } from 'lucide-react'
import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import {
  deleteAdminCodeExample,
  fetchAdminCodeExamples,
  fetchAdminSkills,
  type AdminCodeExample,
  type AdminSkill,
  type PageResponse,
} from '../../services/adminApi'

const LANGUAGES = ['bash', 'go', 'java', 'kotlin', 'python', 'sql', 'typescript', 'yaml']

export function CodeExamplesAdmin() {
  const { getAccessToken } = useAuth()
  const [examples, setExamples] = useState<PageResponse<AdminCodeExample> | null>(null)
  const [skills, setSkills] = useState<AdminSkill[]>([])
  const [page, setPage] = useState(0)
  const [skillFilter, setSkillFilter] = useState('')
  const [languageFilter, setLanguageFilter] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  useEffect(() => {
    fetchAdminSkills(getAccessToken, 0, 100)
      .then((page) => setSkills(page.content))
      .catch(() => {
        // non-fatal: filters still work without skills list
      })
  }, [getAccessToken])

  const loadExamples = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchAdminCodeExamples(getAccessToken, page, 20, {
        skill: skillFilter || undefined,
        language: languageFilter || undefined,
        search: searchQuery || undefined,
      })
      setExamples(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load code examples')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, page, skillFilter, languageFilter, searchQuery])

  useEffect(() => {
    loadExamples()
  }, [loadExamples])

  const handleFilterChange = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLSelectElement | HTMLInputElement>) => {
    setter(e.target.value)
    setPage(0)
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminCodeExample(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      loadExamples()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete')
      setDeleteTarget(null)
    }
  }

  if (error) return <div className="error">{error}</div>

  return (
    <div>
      <div className="admin-header">
        <h1>
          <Code2 size={24} />
          Code Examples
        </h1>
        <Link to="/admin/code-examples/new" className="admin-btn admin-btn--primary">
          <Plus size={16} />
          New Code Example
        </Link>
      </div>

      <div className="code-examples-filters">
        <input
          type="text"
          className="code-examples-filters__search"
          placeholder="Search..."
          value={searchQuery}
          onChange={handleFilterChange(setSearchQuery)}
        />
        <select
          className="code-examples-filters__select"
          value={skillFilter}
          onChange={handleFilterChange(setSkillFilter)}
        >
          <option value="">All skills</option>
          {skills.map((skill) => (
            <option key={skill.id} value={skill.id}>
              {skill.name}
            </option>
          ))}
        </select>
        <select
          className="code-examples-filters__select"
          value={languageFilter}
          onChange={handleFilterChange(setLanguageFilter)}
        >
          <option value="">All languages</option>
          {LANGUAGES.map((lang) => (
            <option key={lang} value={lang}>
              {lang}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <div>Loading...</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Language</th>
              <th>Skills</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {examples?.content.map((example) => (
              <tr key={example.id}>
                <td>
                  <Link to={`/admin/code-examples/${example.id}`}>{example.title}</Link>
                </td>
                <td>
                  <span className="code-examples-badge code-examples-badge--language">
                    {example.language}
                  </span>
                </td>
                <td>
                  <div className="code-examples-skill-tags">
                    {example.skills.map((skillId) => {
                      const skill = skills.find((s) => s.id === skillId)
                      return (
                        <span key={skillId} className="code-examples-badge code-examples-badge--skill">
                          {skill?.name ?? skillId}
                        </span>
                      )
                    })}
                  </div>
                </td>
                <td>
                  <Link
                    to={`/admin/code-examples/${example.id}`}
                    className="admin-btn admin-btn--icon"
                    title="Edit"
                  >
                    <Pencil size={16} />
                  </Link>
                  <button
                    className="admin-btn admin-btn--icon admin-btn--danger-icon"
                    onClick={() => setDeleteTarget({ id: example.id, name: example.title })}
                    title="Delete"
                  >
                    <Trash2 size={16} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {examples && examples.totalPages > 1 && (
        <div className="pagination">
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span>
            Page {page + 1} of {examples.totalPages}
          </span>
          <button
            disabled={page >= examples.totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Code Example"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
