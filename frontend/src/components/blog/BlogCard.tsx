import { Link } from 'react-router-dom'

import type { BlogSummary } from '../../types/blog'
import { formatDate } from '../../utils/dateFormat'

const PLACEHOLDER_IMAGE = '/images/blogs/placeholder.jpg'

type CardVariant = 'vertical' | 'horizontal-right' | 'horizontal-left'

interface BlogCardProps {
  blog: BlogSummary
  variant: CardVariant
}

export function BlogCard({ blog, variant }: BlogCardProps) {
  const imageUrl = blog.featuredImageUrl ?? PLACEHOLDER_IMAGE
  const formattedDate = formatDate(blog.createdDate)

  const imageEl = (
    <div className="blog-card__image-wrapper">
      <img
        alt={blog.title}
        className="blog-card__image"
        loading="lazy"
        onError={(e) => {
          ;(e.currentTarget as HTMLImageElement).src = PLACEHOLDER_IMAGE
        }}
        src={imageUrl}
      />
    </div>
  )

  const contentEl = (
    <div className="blog-card__content">
      <h2 className="blog-card__title">{blog.title}</h2>
      <p className="blog-card__description">{blog.shortDescription}</p>
      <time className="blog-card__date" dateTime={blog.createdDate}>
        {formattedDate}
      </time>
      {blog.tags.length > 0 && (
        <ul aria-label="Tags" className="blog-card__tags">
          {blog.tags.map((tag) => (
            <li className="blog-card__tag" key={tag.name}>
              {tag.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  )

  if (variant === 'vertical') {
    return (
      <article className="blog-card blog-card--vertical">
        <Link aria-label={blog.title} className="blog-card__link" to={`/blogs/${blog.id}`}>
          {imageEl}
          {contentEl}
        </Link>
      </article>
    )
  }

  return (
    <article className={`blog-card blog-card--horizontal blog-card--${variant}`}>
      <Link aria-label={blog.title} className="blog-card__link" to={`/blogs/${blog.id}`}>
        {variant === 'horizontal-right' ? (
          <>
            {imageEl}
            {contentEl}
          </>
        ) : (
          <>
            {contentEl}
            {imageEl}
          </>
        )}
      </Link>
    </article>
  )
}
