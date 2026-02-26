import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminTags,
  createAdminTag,
  bulkCreateAdminTags,
  updateAdminTag,
  deleteAdminTag,
  type AdminTag,
} from '../../services/adminApi'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'

export function TagsAdmin() {
  const { getAccessToken } = useAuth()

  const [tags, setTags] = useState<AdminTag[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [newName, setNewName] = useState('')
  const [addSaving, setAddSaving] = useState(false)

  const [bulkInput, setBulkInput] = useState('')
  const [bulkSaving, setBulkSaving] = useState(false)
  const [bulkSuccess, setBulkSuccess] = useState<string | null>(null)

  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [editSaving, setEditSaving] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  const loadTags = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const data = await fetchAdminTags(getAccessToken)
      setTags(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load tags')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    loadTags()
  }, [loadTags])

  const handleAdd = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!newName.trim()) return
    try {
      setAddSaving(true)
      setError(null)
      await createAdminTag(getAccessToken, { name: newName.trim() })
      setNewName('')
      await loadTags()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create tag')
    } finally {
      setAddSaving(false)
    }
  }

  const handleBulkCreate = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!bulkInput.trim()) return
    const names = bulkInput
      .split(',')
      .map((n) => n.trim())
      .filter(Boolean)
    if (names.length === 0) return
    try {
      setBulkSaving(true)
      setBulkSuccess(null)
      setError(null)
      const created = await bulkCreateAdminTags(getAccessToken, { names })
      setBulkInput('')
      setBulkSuccess(`Created ${created.length} tag(s).`)
      await loadTags()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to bulk create tags')
    } finally {
      setBulkSaving(false)
    }
  }

  const handleEditStart = (tag: AdminTag) => {
    setEditingId(tag.id)
    setEditName(tag.name)
  }

  const handleEditSave = async (id: string) => {
    if (!editName.trim()) return
    try {
      setEditSaving(true)
      setError(null)
      await updateAdminTag(getAccessToken, id, { name: editName.trim() })
      setEditingId(null)
      await loadTags()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update tag')
    } finally {
      setEditSaving(false)
    }
  }

  const handleEditCancel = () => {
    setEditingId(null)
    setEditName('')
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      setError(null)
      await deleteAdminTag(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      await loadTags()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete tag')
      setDeleteTarget(null)
    }
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Tags</h1>

      {error && <div className="admin-error-banner">{error}</div>}

      <section className="admin-section">
        <h2 className="admin-section__title">Add Tag</h2>
        <form className="admin-form admin-form--inline" onSubmit={handleAdd}>
          <div className="admin-form__row">
            <div className="admin-form__field">
              <label className="admin-form__label" htmlFor="new-tag-name">
                Tag Name
              </label>
              <input
                className="admin-form__input"
                id="new-tag-name"
                onChange={(e) => setNewName(e.target.value)}
                placeholder="Tag name"
                required
                type="text"
                value={newName}
              />
            </div>
            <div className="admin-form__actions admin-form__actions--inline">
              <button
                className="admin-btn admin-btn--primary"
                disabled={addSaving}
                type="submit"
              >
                {addSaving ? 'Adding...' : 'Add Tag'}
              </button>
            </div>
          </div>
        </form>
      </section>

      <section className="admin-section">
        <h2 className="admin-section__title">Bulk Create</h2>
        {bulkSuccess && <div className="admin-success-banner">{bulkSuccess}</div>}
        <form className="admin-form admin-form--inline" onSubmit={handleBulkCreate}>
          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="bulk-tags">
              Comma-separated tag names
            </label>
            <input
              className="admin-form__input"
              id="bulk-tags"
              onChange={(e) => setBulkInput(e.target.value)}
              placeholder="react, typescript, spring-boot"
              type="text"
              value={bulkInput}
            />
          </div>
          <div className="admin-form__actions">
            <button
              className="admin-btn admin-btn--primary"
              disabled={bulkSaving}
              type="submit"
            >
              {bulkSaving ? 'Creating...' : 'Bulk Create'}
            </button>
          </div>
        </form>
      </section>

      <section className="admin-section">
        <h2 className="admin-section__title">All Tags ({tags.length})</h2>

        {loading ? (
          <div className="admin-loading">Loading tags...</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th className="admin-table__th">Name</th>
                <th className="admin-table__th">Created</th>
                <th className="admin-table__th">Actions</th>
              </tr>
            </thead>
            <tbody>
              {tags.length === 0 && (
                <tr>
                  <td className="admin-table__td admin-table__td--empty" colSpan={3}>
                    No tags found.
                  </td>
                </tr>
              )}
              {tags.map((tag) =>
                editingId === tag.id ? (
                  <tr key={tag.id} className="admin-table__row admin-table__row--editing">
                    <td className="admin-table__td">
                      <input
                        autoFocus
                        className="admin-form__input admin-form__input--compact"
                        onChange={(e) => setEditName(e.target.value)}
                        type="text"
                        value={editName}
                      />
                    </td>
                    <td className="admin-table__td">
                      {new Date(tag.createdAt).toLocaleDateString()}
                    </td>
                    <td className="admin-table__td admin-table__td--actions">
                      <button
                        className="admin-btn admin-btn--sm admin-btn--primary"
                        disabled={editSaving}
                        onClick={() => handleEditSave(tag.id)}
                        type="button"
                      >
                        {editSaving ? 'Saving...' : 'Save'}
                      </button>
                      <button
                        className="admin-btn admin-btn--sm"
                        onClick={handleEditCancel}
                        type="button"
                      >
                        Cancel
                      </button>
                    </td>
                  </tr>
                ) : (
                  <tr key={tag.id} className="admin-table__row">
                    <td className="admin-table__td">{tag.name}</td>
                    <td className="admin-table__td">
                      {new Date(tag.createdAt).toLocaleDateString()}
                    </td>
                    <td className="admin-table__td admin-table__td--actions">
                      <button
                        className="admin-btn admin-btn--sm"
                        onClick={() => handleEditStart(tag)}
                        type="button"
                      >
                        Edit
                      </button>
                      <button
                        className="admin-btn admin-btn--sm admin-btn--danger"
                        onClick={() => setDeleteTarget({ id: tag.id, name: tag.name })}
                        type="button"
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        )}
      </section>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Tag"
        message={`Are you sure you want to delete the tag "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
