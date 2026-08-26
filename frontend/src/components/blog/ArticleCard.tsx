import { Link } from 'react-router-dom'

import { ListenButton } from '../narration/ListenButton'
import type { BlogSummary } from '../../types/blog'

interface ArticleCardProps {
  blog: BlogSummary
}

export function ArticleCard({ blog }: ArticleCardProps) {
  const formattedDate = new Date(blog.createdDate).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })

  const primaryTag = blog.tags[0]?.name ?? null

  return (
    <article className="card article-card">
      <div className="article-card__thumbnail">
        {blog.featuredImageUrl ? (
          <img src={blog.featuredImageUrl} alt={blog.title} />
        ) : (
          <div className="article-card__thumbnail-placeholder" />
        )}
      </div>
      <div className="article-card__content">
        <div className="article-card__meta">
          {primaryTag && <span className="article-card__category">{primaryTag}</span>}
          <span className="tag">{formattedDate}</span>
        </div>
        <h3 className="title-lg article-card__title">{blog.title}</h3>
        <p className="article-card__excerpt">{blog.shortDescription}</p>
        {/* Shared with the home page's Featured Writing, which inherits the listen control
            deliberately: the most prominent posts there should not be the ones you cannot
            play. */}
        <div className="article-card__actions">
          <Link className="article-card__link" to={`/blogs/${blog.id}`}>
            Read post
          </Link>
          <ListenButton
            contentId={blog.id}
            contentType="BLOG"
            href={`/blogs/${blog.id}`}
            title={blog.title}
          />
        </div>
      </div>
    </article>
  )
}
