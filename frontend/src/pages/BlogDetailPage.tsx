import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { track } from '../analytics/analytics'
import { useAdminRole } from '../auth/useAdminRole'
import { BlogDetail } from '../components/blog/BlogDetail'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { SnippetLayer } from '../components/snippets/SnippetLayer'
import { postSource } from '../components/snippets/snippetSource'
import { usePageTitle } from '../hooks/usePageTitle'
import { fetchBlogById } from '../services/blogApi'
import type { BlogDetail as BlogDetailType } from '../types/blog'

export function BlogDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [blog, setBlog] = useState<BlogDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  // Snippets (spec 054) are the admin's private notes. Everyone else gets the post exactly as
  // before, and the snippets API is never called for them.
  const isAdmin = useAdminRole()
  const proseRef = useRef<HTMLDivElement>(null)

  // Passes `undefined` until the post arrives, then re-runs with the real title.
  usePageTitle(blog?.title)

  useEffect(() => {
    if (!id) {
      setError('Blog post not found.')
      setLoading(false)
      return
    }

    let cancelled = false

    setLoading(true)
    setError(null)

    fetchBlogById(id)
      .then((data) => {
        if (!cancelled) {
          setBlog(data)
          track('Blog Viewed', { blogId: data.id })
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
        <BlogDetail
          blog={blog}
          proseRef={proseRef}
          snippetLayer={isAdmin ? (
            <SnippetLayer body={blog.content} proseRef={proseRef} source={postSource(blog)} />
          ) : undefined}
        />
      </div>
    </div>
  )
}
