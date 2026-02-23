import type { BlogSummary } from '../../types/blog'
import { BlogCard } from './BlogCard'

interface BlogGridProps {
  blogs: BlogSummary[]
}

/**
 * Implements the 6-item repeating layout cycle:
 *   index % 6 === 0 → vertical (1/3 width)
 *   index % 6 === 1 → horizontal-right (2/3 width, image right)
 *   index % 6 === 2 → renders inside the pair started at index 1 (horizontal-left)
 *   index % 6 === 3 → horizontal-left (2/3 width, image left)
 *   index % 6 === 4 → renders inside the pair started at index 3 (horizontal-right)
 *   index % 6 === 5 → vertical (1/3 width)
 */
export function BlogGrid({ blogs }: BlogGridProps) {
  const rows: React.ReactNode[] = []
  let i = 0

  while (i < blogs.length) {
    const position = i % 6

    if (position === 0 || position === 5) {
      rows.push(<BlogCard blog={blogs[i]} key={blogs[i].id} variant="vertical" />)
      i++
    } else if (position === 1) {
      const first = blogs[i]
      const second = blogs[i + 1]
      rows.push(
        <div className="blog-grid__pair" key={`pair-${first.id}`}>
          <BlogCard blog={first} variant="horizontal-right" />
          {second && <BlogCard blog={second} variant="horizontal-left" />}
        </div>,
      )
      i += 2
    } else if (position === 3) {
      const first = blogs[i]
      const second = blogs[i + 1]
      rows.push(
        <div className="blog-grid__pair" key={`pair-${first.id}`}>
          <BlogCard blog={first} variant="horizontal-left" />
          {second && <BlogCard blog={second} variant="horizontal-right" />}
        </div>,
      )
      i += 2
    } else {
      // Fallback for any unexpected position
      rows.push(<BlogCard blog={blogs[i]} key={blogs[i].id} variant="vertical" />)
      i++
    }
  }

  return <div className="blog-grid">{rows}</div>
}
