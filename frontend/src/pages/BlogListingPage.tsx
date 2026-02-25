import { useEffect, useState } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { Sidebar, type NavigationItem } from '../components/layout/Sidebar'
import { BlogGrid } from '../components/blog/BlogGrid'
import { BlogSearch } from '../components/search/BlogSearch'
import { useProfile } from '../hooks/useProfile'
import { fetchBlogs } from '../services/blogApi'
import type { BlogSummary } from '../types/blog'

const navigationItems: NavigationItem[] = [
  { id: 'profile', label: 'Profile', route: '/' },
  { id: 'about', label: 'About' },
  { id: 'experience', label: 'Experience' },
  { id: 'skills', label: 'Skills' },
  { id: 'blog', label: 'Blog', route: '/blogs' },
  { id: 'contact', label: 'Contact' },
]

export function BlogListingPage() {
  const [blogs, setBlogs] = useState<BlogSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const { profile } = useProfile()

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
    <div className="blog-page-layout">
      {profile && (
        <Sidebar
          aboutImageUrl={profile.sidebarImage.url}
          items={navigationItems}
          socialLinks={profile.socialMediaLinks}
        />
      )}
      <main className="blog-page-layout__content">
        <h1 className="blog-listing-page__title">Blog</h1>
        <BlogSearch />
        <BlogGrid blogs={blogs} />
      </main>
    </div>
  )
}
