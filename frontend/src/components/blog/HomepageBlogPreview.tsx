import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { fetchLatestBlogs } from '../../services/blogApi'
import type { BlogSummary } from '../../types/blog'
import { formatDate } from '../../utils/dateFormat'

const PLACEHOLDER_IMAGE = '/images/blogs/placeholder.jpg'

export function HomepageBlogPreview() {
  const [blogs, setBlogs] = useState<BlogSummary[]>([])

  useEffect(() => {
    fetchLatestBlogs(3)
      .then(setBlogs)
      .catch(() => {
        // Silently fail - homepage preview is non-critical
      })
  }, [])

  if (blogs.length === 0) {
    return null
  }

  return (
    <section className="panel homepage-blog-preview" id="blog">
      <h3>Latest from the Blog</h3>
      <ul className="homepage-blog-preview__list">
        {blogs.map((blog) => {
          const imageUrl = blog.featuredImageUrl ?? PLACEHOLDER_IMAGE
          return (
            <li className="homepage-blog-preview__item" key={blog.id}>
              <Link className="homepage-blog-preview__link" to={`/blogs/${blog.id}`}>
                <img
                  alt={blog.title}
                  className="homepage-blog-preview__image"
                  onError={(e) => {
                    ;(e.currentTarget as HTMLImageElement).src = PLACEHOLDER_IMAGE
                  }}
                  src={imageUrl}
                />
                <div className="homepage-blog-preview__content">
                  <h4 className="homepage-blog-preview__title">{blog.title}</h4>
                  <p className="homepage-blog-preview__description">{blog.shortDescription}</p>
                  <time className="homepage-blog-preview__date" dateTime={blog.createdDate}>
                    {formatDate(blog.createdDate)}
                  </time>
                </div>
              </Link>
            </li>
          )
        })}
      </ul>
      <Link className="homepage-blog-preview__view-all" to="/blogs">
        View All Posts
      </Link>
    </section>
  )
}
