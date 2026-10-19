import { ExternalLink } from 'lucide-react'

import { track } from '../../analytics/analytics'
import { authorLine, purchaseLabel, type Book } from '../../types/book'
import { BookCover } from './BookCover'

interface BookCardProps {
  book: Book
  onOpen: (slug: string) => void
}

/**
 * One book: cover, title, authors and the summary paragraph. The title is the button that opens
 * the drawer, stretched over the whole card, so the card reads as one control without wrapping
 * a heading and a link inside a button. The purchase link sits above that stretch.
 */
export function BookCard({ book, onOpen }: BookCardProps) {
  return (
    <article className="book-card">
      <BookCover book={book} size="card" />
      <div className="book-card__body">
        <h3 className="book-card__title">
          <button className="book-card__open" onClick={() => onOpen(book.slug)} type="button">
            {book.title}
          </button>
        </h3>
        <p className="book-card__authors">{authorLine(book.authors)}</p>
        <p className="book-card__summary">{book.summary}</p>
        <div className="book-card__actions">
          <span aria-hidden="true" className="book-card__more">More about this book</span>
          {book.purchaseUrl ? (
            <a
              className="book-card__buy"
              href={book.purchaseUrl}
              onClick={() => track('Book Purchase Clicked', { slug: book.slug, from: 'card' })}
              rel="noopener noreferrer"
              target="_blank"
            >
              {purchaseLabel(book.purchaseUrl)}
              <ExternalLink aria-hidden="true" size={14} />
            </a>
          ) : null}
        </div>
      </div>
    </article>
  )
}
