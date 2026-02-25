import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import {
  deleteAdminJob,
  fetchAdminJobs,
  type AdminJob,
  type PageResponse,
} from '../../services/adminApi'

export function JobsAdmin() {
  const { getAccessToken } = useAuth()
  const navigate = useNavigate()
  const [jobs, setJobs] = useState<PageResponse<AdminJob> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  const loadJobs = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchAdminJobs(getAccessToken, page)
      setJobs(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load jobs')
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, page])

  useEffect(() => {
    loadJobs()
  }, [loadJobs])

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminJob(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      loadJobs()
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
        <h1>Jobs</h1>
        <button onClick={() => navigate('/admin/jobs/new')}>New Job</button>
      </div>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Title</th>
            <th>Company</th>
            <th>Start Date</th>
            <th>Education</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {jobs?.content.map((job) => (
            <tr key={job.id}>
              <td>
                <Link to={`/admin/jobs/${job.id}`}>{job.title}</Link>
              </td>
              <td>{job.company}</td>
              <td>{new Date(job.startDate).toLocaleDateString()}</td>
              <td>{job.education ? 'Yes' : 'No'}</td>
              <td>
                <button onClick={() => navigate(`/admin/jobs/${job.id}`)}>Edit</button>
                <button onClick={() => setDeleteTarget({ id: job.id, name: job.title })}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {jobs && jobs.totalPages > 1 && (
        <div className="pagination">
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span>
            Page {page + 1} of {jobs.totalPages}
          </span>
          <button
            disabled={page >= jobs.totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      )}
      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Job"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
