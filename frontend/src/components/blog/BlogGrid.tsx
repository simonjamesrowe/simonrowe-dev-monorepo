import type { BlogSummary } from '../../types/blog'
import { BlogCard } from './BlogCard'

interface BlogGridProps {
  blogs: BlogSummary[]
}

export function BlogGrid({ blogs }: BlogGridProps) {
  return (
    <div className="blog-grid">
      {blogs.map((blog, index) => (
        <BlogCard
          blog={blog}
          imagePosition={index % 2 === 0 ? 'left' : 'right'}
          key={blog.id}
        />
      ))}
    </div>
  )
}
