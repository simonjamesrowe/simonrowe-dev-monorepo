import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { Sidebar, type NavigationItem } from '../components/layout/Sidebar'
import { BlogDetail } from '../components/blog/BlogDetail'
import { useProfile } from '../hooks/useProfile'
import { fetchBlogById } from '../services/blogApi'
import type { BlogDetail as BlogDetailType } from '../types/blog'

const navigationItems: NavigationItem[] = [
  { id: 'profile', label: 'Profile', route: '/' },
  { id: 'about', label: 'About' },
  { id: 'experience', label: 'Experience' },
  { id: 'skills', label: 'Skills' },
  { id: 'blog', label: 'Blog', route: '/blogs' },
  { id: 'contact', label: 'Contact' },
]

export function BlogDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [blog, setBlog] = useState<BlogDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const { profile } = useProfile()

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
    <div className="blog-page-layout">
      {profile && (
        <Sidebar
          aboutImageUrl={profile.sidebarImage.url}
          items={navigationItems}
          socialLinks={profile.socialMediaLinks}
        />
      )}
      <main className="blog-page-layout__content">
        <Link className="page__back-link" to="/blogs">&larr; Back to Blog</Link>
        <BlogDetail blog={blog} />
      </main>
    </div>
  )
}
