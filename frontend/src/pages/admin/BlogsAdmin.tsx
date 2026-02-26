import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import {
  deleteAdminBlog,
  fetchAdminBlogs,
  type AdminBlog,
  type PageResponse,
} from '../../services/adminApi'

export function BlogsAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()
  const [blogs, setBlogs] = useState<PageResponse<AdminBlog> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  const loadBlogs = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchAdminBlogs(getAccessToken, page)
      setBlogs(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load blogs')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, page])

  useEffect(() => {
    loadBlogs()
  }, [loadBlogs])

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminBlog(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      loadBlogs()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete')
      setDeleteTarget(null)
    }
  }

  if (loading) return <div>Loading...</div>
  if (error) return <div className="error">{error}</div>

  return (
    <div>
      <div className="admin-header">
        <h1>Blogs</h1>
        <button onClick={() => navigate('/admin/blogs/new')}>New Blog</button>
      </div>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Title</th>
            <th>Published</th>
            <th>Created</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {blogs?.content.map((blog) => (
            <tr key={blog.id}>
              <td>
                <Link to={`/admin/blogs/${blog.id}`}>{blog.title}</Link>
              </td>
              <td>{blog.published ? 'Yes' : 'No'}</td>
              <td>{new Date(blog.createdAt).toLocaleDateString()}</td>
              <td>
                <button onClick={() => navigate(`/admin/blogs/${blog.id}`)}>Edit</button>
                <button onClick={() => setDeleteTarget({ id: blog.id, name: blog.title })}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {blogs && blogs.totalPages > 1 && (
        <div className="pagination">
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span>
            Page {page + 1} of {blogs.totalPages}
          </span>
          <button
            disabled={page >= blogs.totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      )}
      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Blog"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
