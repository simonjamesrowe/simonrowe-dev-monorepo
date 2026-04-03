import { useEffect, useMemo, useState } from 'react'

import { ArticleCard } from '../components/blog/ArticleCard'
import { CategoryFilters } from '../components/blog/CategoryFilters'
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
  const [activeTag, setActiveTag] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')

  useEffect(() => {
    trackPageView('/blogs')
    document.title = 'Blog | The Digital Architect'
  }, [])

  useEffect(() => {
    fetchBlogs()
      .then(setBlogs)
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  const allTags = useMemo(() => {
    const tagSet = new Set<string>()
    blogs.forEach((blog) => blog.tags.forEach((t) => tagSet.add(t.name)))
    return Array.from(tagSet)
  }, [blogs])

  const filteredBlogs = useMemo(() => {
    let result = blogs

    if (activeTag !== null) {
      result = result.filter((blog) => blog.tags.some((t) => t.name === activeTag))
    }

    if (searchQuery.trim() !== '') {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (blog) =>
          blog.title.toLowerCase().includes(q) ||
          blog.shortDescription.toLowerCase().includes(q),
      )
    }

    return result
  }, [blogs, activeTag, searchQuery])

  const featuredBlog = filteredBlogs[0] ?? null
  const gridBlogs = filteredBlogs.slice(1)

  if (loading) {
    return <LoadingIndicator />
  }

  if (error) {
    return <ErrorMessage message={error} />
  }

  return (
    <div className="blog-listing-page">
      <section className="blog-listing-page__hero">
        <h1 className="display-lg blog-listing-page__headline">
          Technical <span className="blog-listing-page__headline-accent">Luminescence.</span>
        </h1>
        <p className="blog-listing-page__subtitle body-lg">
          Deep dives into cloud architecture, security engineering, and the AI-native frontier.
        </p>
      </section>

      <CategoryFilters
        tags={allTags}
        activeTag={activeTag}
        onTagSelect={setActiveTag}
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
      />
      {featuredBlog && <FeaturedArticle blog={featuredBlog} />}
      {gridBlogs.length > 0 && (
        <div className="blog-listing-page__grid">
          {gridBlogs.map((blog) => (
            <ArticleCard key={blog.id} blog={blog} />
          ))}
        </div>
      )}
      <div className="blog-listing-page__archive">
        <button type="button" className="button button--secondary blog-listing-page__archive-btn">
          Load Archive
        </button>
      </div>
    </div>
  )
}
