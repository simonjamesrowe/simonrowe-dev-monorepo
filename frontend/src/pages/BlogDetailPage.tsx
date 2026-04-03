import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { BlogDetail } from '../components/blog/BlogDetail'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { fetchBlogById } from '../services/blogApi'
import { trackPageView } from '../services/analytics'
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

    trackPageView(`/blogs/${id}`)

    fetchBlogById(id)
      .then((data) => {
        if (!cancelled) {
          setBlog(data)
          document.title = `${data.title} | The Digital Architect`
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
    <div className="blog-detail-page">
      <Link className="blog-detail-page__back-link" to="/blogs">&larr; Back to Blog</Link>
      <div className="blog-detail__reading-rail">
        <BlogDetail blog={blog} />
      </div>
    </div>
  )
}
