import type { BlogDetail as BlogDetailType } from '../../types/blog'
import { formatDate } from '../../utils/dateFormat'
import { MarkdownRenderer } from './MarkdownRenderer'

const PLACEHOLDER_IMAGE = '/images/blogs/placeholder.jpg'

interface BlogDetailProps {
  blog: BlogDetailType
}

export function BlogDetail({ blog }: BlogDetailProps) {
  const imageUrl = blog.featuredImageUrl ?? PLACEHOLDER_IMAGE
  const formattedDate = formatDate(blog.createdDate)

  return (
    <article className="blog-detail">
      <div className="blog-detail__hero">
        <img
          alt={blog.title}
          className="blog-detail__featured-image"
          onError={(e) => {
            ;(e.currentTarget as HTMLImageElement).src = PLACEHOLDER_IMAGE
          }}
          src={imageUrl}
        />
      </div>
      <div className="blog-detail__header">
        <h1 className="blog-detail__title">{blog.title}</h1>
        <div className="blog-detail__meta">
          <span className="blog-detail__author">Simon Rowe</span>
          <time className="blog-detail__date" dateTime={blog.createdDate}>
            {formattedDate}
          </time>
        </div>
        {blog.tags.length > 0 && (
          <ul aria-label="Tags" className="blog-detail__tags">
            {blog.tags.map((tag) => (
              <li className="blog-detail__tag" key={tag.name}>
                {tag.name}
              </li>
            ))}
          </ul>
        )}
        {blog.skills && blog.skills.length > 0 && (
          <div className="blog-detail__skills">
            <h2 className="blog-detail__skills-label">Related Skills</h2>
            <ul className="blog-detail__skills-list">
              {blog.skills.map((skill) => (
                <li className="blog-detail__skill" key={skill.id}>
                  {skill.name}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      <div className="blog-detail__content">
        <MarkdownRenderer content={blog.content} />
      </div>
    </article>
  )
}
