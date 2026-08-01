import { Link } from 'react-router-dom'

import type { BlogSummary } from '../../types/blog'

interface FeaturedArticleProps {
  blog: BlogSummary
}

export function FeaturedArticle({ blog }: FeaturedArticleProps) {
  const formattedDate = new Date(blog.createdDate).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })

  return (
    <article className="card featured-article">
      <div className="featured-article__image">
        {blog.featuredImageUrl ? (
          <img src={blog.featuredImageUrl} alt={blog.title} />
        ) : (
          <div className="featured-article__image-placeholder" />
        )}
      </div>
      <div className="featured-article__content">
        <div className="featured-article__meta">
          <span className="featured-article__label">FEATURED ARTICLE</span>
          <span className="tag">{formattedDate}</span>
        </div>
        <h2 className="headline-lg featured-article__title">{blog.title}</h2>
        <p className="featured-article__excerpt">{blog.shortDescription}</p>
        <Link className="featured-article__link" to={`/blogs/${blog.id}`}>
          Read post
        </Link>
      </div>
    </article>
  )
}
