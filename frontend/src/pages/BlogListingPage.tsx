import { useEffect, useState } from 'react'

import { ArticleCard } from '../components/blog/ArticleCard'
import { FeaturedArticle } from '../components/blog/FeaturedArticle'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { fetchBlogs } from '../services/blogApi'
import { trackPageView } from '../services/analytics'
import type { BlogSummary } from '../types/blog'

export function BlogListingPage() {
  const [blogs, setBlogs] = useState<BlogSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    trackPageView('/blogs')
    document.title = 'Blog'
  }, [])

  useEffect(() => {
    fetchBlogs()
      .then(setBlogs)
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  const featuredBlog = blogs[0] ?? null
  const gridBlogs = blogs.slice(1)

  if (loading) {
    return <LoadingIndicator />
  }

  if (error) {
    return <ErrorMessage message={error} />
  }

  return (
    <div className="blog-listing-page">
      {featuredBlog && <FeaturedArticle blog={featuredBlog} />}
      {gridBlogs.length > 0 && (
        <div className="blog-listing-page__grid">
          {gridBlogs.map((blog) => (
            <ArticleCard key={blog.id} blog={blog} />
          ))}
        </div>
      )}
    </div>
  )
}
