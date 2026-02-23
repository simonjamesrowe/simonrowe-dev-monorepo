import { useEffect, useState } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { BlogGrid } from '../components/blog/BlogGrid'
import { BlogSearch } from '../components/blog/BlogSearch'
import { fetchBlogs } from '../services/blogApi'
import type { BlogSummary } from '../types/blog'

export function BlogListingPage() {
  const [blogs, setBlogs] = useState<BlogSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchBlogs()
      .then(setBlogs)
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  if (loading) {
    return <LoadingIndicator />
  }

  if (error) {
    return <ErrorMessage message={error} />
  }

  return (
    <main className="page blog-listing-page">
      <h1 className="blog-listing-page__title">Blog</h1>
      <BlogSearch />
      <BlogGrid blogs={blogs} />
    </main>
  )
}
