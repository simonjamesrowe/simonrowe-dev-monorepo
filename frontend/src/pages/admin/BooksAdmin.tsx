import { CheckCircle, GripVertical, Pencil, Trash2, XCircle } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import {
  deleteAdminBook,
  fetchAdminBooks,
  reorderAdminBooks,
  setAdminBookPublished,
  type AdminBook,
} from '../../services/adminApi'
import { authorLine } from '../../types/book'

/**
 * The Reading list in display order; drag a row to reorder, as the portfolio list does. The
 * published icon is a button, so a draft from the seed can be switched on without opening it.
 */
export function BooksAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()
  const [books, setBooks] = useState<AdminBook[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<AdminBook | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)
  const dragIndex = useRef<number | null>(null)

  const load = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      setBooks(await fetchAdminBooks(getAccessToken))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load books')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    void load()
  }, [load])

  const handleDrop = async (event: React.DragEvent, dropIndex: number) => {
    event.preventDefault()
    const from = dragIndex.current
    dragIndex.current = null
    setOverIndex(null)
    if (from === null || from === dropIndex) return

    const reordered = [...books]
    const [moved] = reordered.splice(from, 1)
    reordered.splice(dropIndex, 0, moved)
    setBooks(reordered)
    try {
      await reorderAdminBooks(getAccessToken, reordered.map(book => book.id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reorder books')
      void load()
    }
  }

  const togglePublished = async (book: AdminBook) => {
    try {
      const saved = await setAdminBookPublished(getAccessToken, book, !book.published)
      setBooks(current => current.map(row => (row.id === saved.id ? saved : row)))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update the book')
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminBook(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete the book')
      setDeleteTarget(null)
    }
  }

  const publishedCount = books.filter(book => book.published).length

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">Reading list</h1>
        <button className="admin-btn admin-btn--primary" onClick={() => navigate('/admin/books/new')} type="button">
          Add book
        </button>
      </div>
      {!loading ? (
        <p className="admin-form__hint">
          {publishedCount} of {books.length} published on <a href="/reading">/reading</a>. Drag to reorder;
          books are grouped under their category in the order the first of each appears.
        </p>
      ) : null}

      {error ? <div className="admin-error-banner">{error}</div> : null}

      {loading ? (
        <div className="admin-loading">Loading books...</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th aria-label="Reorder" style={{ width: 40 }} />
              <th className="admin-table__th">Title</th>
              <th className="admin-table__th">Category</th>
              <th className="admin-table__th">Published</th>
              <th className="admin-table__th">Actions</th>
            </tr>
          </thead>
          <tbody>
            {books.length === 0 ? (
              <tr>
                <td className="admin-table__td admin-table__td--empty" colSpan={5}>No books yet.</td>
              </tr>
            ) : null}
            {books.map((book, index) => (
              <tr
                className={overIndex === index ? 'admin-table__row--drag-over' : ''}
                draggable
                key={book.id}
                onDragEnd={() => {
                  dragIndex.current = null
                  setOverIndex(null)
                }}
                onDragOver={event => {
                  event.preventDefault()
                  setOverIndex(index)
                }}
                onDragStart={() => {
                  dragIndex.current = index
                }}
                onDrop={event => void handleDrop(event, index)}
              >
                <td><span className="admin-table__grip"><GripVertical size={16} /></span></td>
                <td className="admin-table__td">
                  {book.title}
                  <div className="admin-table__sub">{authorLine(book.authors)}</div>
                </td>
                <td className="admin-table__td">{book.category}</td>
                <td className="admin-table__td">
                  <button
                    aria-label={book.published ? `Unpublish ${book.title}` : `Publish ${book.title}`}
                    aria-pressed={book.published}
                    className="admin-btn admin-btn--icon"
                    onClick={() => void togglePublished(book)}
                    title={book.published ? 'Published: click to unpublish' : 'Draft: click to publish'}
                    type="button"
                  >
                    {book.published
                      ? <CheckCircle className="icon-published" size={18} />
                      : <XCircle className="icon-draft" size={18} />}
                  </button>
                </td>
                <td className="admin-table__td admin-table__td--actions">
                  <button
                    aria-label={`Edit ${book.title}`}
                    className="admin-btn admin-btn--icon"
                    onClick={() => navigate(`/admin/books/${book.id}`)}
                    title="Edit"
                    type="button"
                  >
                    <Pencil size={16} />
                  </button>
                  <button
                    aria-label={`Delete ${book.title}`}
                    className="admin-btn admin-btn--icon admin-btn--danger-icon"
                    onClick={() => setDeleteTarget(book)}
                    title="Delete"
                    type="button"
                  >
                    <Trash2 size={16} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <ConfirmDialog
        confirmLabel="Delete"
        message={`Delete "${deleteTarget?.title}"? This cannot be undone. Unpublishing keeps it as a draft instead.`}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => void handleDeleteConfirm()}
        open={deleteTarget !== null}
        title="Delete book"
      />
    </div>
  )
}
