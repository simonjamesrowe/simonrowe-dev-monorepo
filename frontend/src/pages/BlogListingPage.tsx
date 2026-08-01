import { useCallback, useEffect, useState } from 'react'

import { ArticleCard } from '../components/blog/ArticleCard'
import { BlogContentTabs, type BlogContentFilter } from '../components/blog/BlogContentTabs'
import { FeaturedArticle } from '../components/blog/FeaturedArticle'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { usePageTitle } from '../hooks/usePageTitle'
import { fetchBlogs } from '../services/blogApi'
import { trackPageView } from '../services/analytics'
import type { BlogSummary } from '../types/blog'

/** `GET /api/blogs` is unpaged (~43 posts), so the split is a client-side filter. */
function filterByTab(blogs: BlogSummary[], tab: BlogContentFilter): BlogSummary[] {
  return tab === 'ALL' ? blogs : blogs.filter((blog) => blog.contentType === tab)
}

/**
 * The featured slot is reserved for engineering writing: on the All and Engineering
 * tabs it is the newest `ENGINEERING` post, never whichever post happens to be
 * newest. Only the Weekly Digest tab features a digest.
 */
function pickFeatured(blogs: BlogSummary[], tab: BlogContentFilter): BlogSummary | null {
  const preferred = tab === 'DIGEST' ? 'DIGEST' : 'ENGINEERING'
  return blogs.find((blog) => blog.contentType === preferred) ?? null
}

export function BlogListingPage() {
  const [blogs, setBlogs] = useState<BlogSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [activeTab, setActiveTab] = useState<BlogContentFilter>('ENGINEERING')

  usePageTitle('Blog')

  useEffect(() => {
    trackPageView('/blogs')
  }, [])

  useEffect(() => {
    let cancelled = false

    setLoading(true)
    setError(null)

    fetchBlogs()
      .then((data) => {
        if (!cancelled) {
          setBlogs(data)
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message)
          setBlogs([])
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
  }, [attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  if (loading) {
    return <LoadingIndicator message="Loading blogs..." />
  }

  if (error) {
    return <ErrorMessage message={error} onRetry={retry} title="Unable to load the blog" />
  }

  const visibleBlogs = filterByTab(blogs, activeTab)
  const featuredBlog = pickFeatured(visibleBlogs, activeTab)
  const gridBlogs = visibleBlogs.filter((blog) => blog.id !== featuredBlog?.id)

  return (
    <div className="blog-listing-page tour-blogs">
      <BlogContentTabs active={activeTab} onChange={setActiveTab} />
      {visibleBlogs.length === 0 ? (
        <p className="blog-listing-page__empty">
          {blogs.length === 0
            ? 'No posts published yet. Check back soon.'
            : 'No posts in this section yet. Try another tab.'}
        </p>
      ) : (
        <>
          {featuredBlog && <FeaturedArticle blog={featuredBlog} />}
          {gridBlogs.length > 0 && (
            <div className="blog-listing-page__grid">
              {gridBlogs.map((blog) => (
                <ArticleCard key={blog.id} blog={blog} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
