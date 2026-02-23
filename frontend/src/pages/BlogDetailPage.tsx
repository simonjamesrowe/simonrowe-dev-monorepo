import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { BlogDetail } from '../components/blog/BlogDetail'
import { fetchBlogById } from '../services/blogApi'
import type { BlogDetail as BlogDetailType } from '../types/blog'

export function BlogDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [blog, setBlog] = useState<BlogDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) {
      setError('Blog post not found.')
      setLoading(false)
      return
    }

    let cancelled = false

    fetchBlogById(id)
      .then((data) => {
        if (!cancelled) {
          setBlog(data)
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message)
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [id])

  if (loading) {
    return <LoadingIndicator />
  }

  if (error || !blog) {
    return <ErrorMessage message={error ?? 'Blog post not found.'} />
  }

  return (
    <main className="page blog-detail-page">
      <BlogDetail blog={blog} />
    </main>
  )
}
