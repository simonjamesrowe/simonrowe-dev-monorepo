import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { BlogDetail } from '../components/blog/BlogDetail'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { usePageTitle } from '../hooks/usePageTitle'
import { fetchBlogById } from '../services/blogApi'
import { trackPageView } from '../services/analytics'
import type { BlogDetail as BlogDetailType } from '../types/blog'

export function BlogDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [blog, setBlog] = useState<BlogDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  // Passes `undefined` until the post arrives, then re-runs with the real title.
  usePageTitle(blog?.title)

  useEffect(() => {
    if (!id) {
      setError('Blog post not found.')
      setLoading(false)
      return
    }

    let cancelled = false

    trackPageView(`/blogs/${id}`)

    setLoading(true)
    setError(null)

    fetchBlogById(id)
      .then((data) => {
        if (!cancelled) {
          setBlog(data)
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message)
          setBlog(null)
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
  }, [id, attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  if (loading) {
    return <LoadingIndicator message="Loading blog post..." />
  }

  if (error || !blog) {
    return (
      <ErrorMessage
        message={error ?? 'Blog post not found.'}
        onRetry={id ? retry : undefined}
        title="Unable to load this post"
      />
    )
  }

  return (
    <div className="blog-detail-page">
      <Link className="blog-detail-page__back-link" to="/blogs">&larr; Back to Blog</Link>
      <div className="blog-detail__reading-rail">
        <BlogDetail blog={blog} />
      </div>
    </div>
  )
}
