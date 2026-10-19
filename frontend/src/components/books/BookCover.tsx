import { BookOpen } from 'lucide-react'

import type { Book } from '../../types/book'
import { resolveMediaUrl } from '../../types/portfolio'

interface BookCoverProps {
  book: Book
  size: 'card' | 'drawer'
}

/** The cover, or a plain placeholder for a book saved without one. Decorative: the title is beside it. */
export function BookCover({ book, size }: BookCoverProps) {
  const formats = book.coverImage?.formats
  const url = size === 'card'
    ? formats?.small?.url ?? formats?.medium?.url ?? book.coverImage?.url
    : formats?.medium?.url ?? book.coverImage?.url
  return (
    <div className={`book-cover book-cover--${size}`}>
      {url ? (
        <img alt="" loading={size === 'card' ? 'lazy' : undefined} src={resolveMediaUrl(url)} />
      ) : (
        <span className="book-cover__placeholder"><BookOpen aria-hidden="true" size={28} /></span>
      )}
    </div>
  )
}
