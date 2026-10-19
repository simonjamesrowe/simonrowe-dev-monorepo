import { ExternalLink, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

import { track } from '../../analytics/analytics'
import { authorLine, purchaseLabel, type Book } from '../../types/book'
import { closeOnBackdropClick } from '../../utils/backdropClick'
import { BookCover } from './BookCover'

/** A site path stays in this tab; anywhere else opens a new one. */
function SourceLink({ label, url }: { label: string; url: string }) {
  return url.startsWith('/')
    ? <a href={url}>{label}</a>
    : <a href={url} rel="noopener noreferrer" target="_blank">{label}</a>
}

interface BookDrawerProps {
  book: Book
  onClose: () => void
}

/**
 * The detail drawer for one book, in the site's shared `.drawer` shell. Everything it shows came
 * with the list, so it opens instantly and needs no loading state.
 */
export function BookDrawer({ book, onClose }: BookDrawerProps) {
  const closeButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    document.body.style.overflow = 'hidden'
    closeButton.current?.focus()
    return () => { document.body.style.overflow = '' }
  }, [])

  useEffect(() => {
    const handleEsc = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleEsc)
    return () => document.removeEventListener('keydown', handleEsc)
  }, [onClose])

  const facts = [book.category, book.publishedYear ? `First published ${book.publishedYear}` : null]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="drawer-overlay" onClick={closeOnBackdropClick(onClose)} role="presentation">
      <div aria-labelledby="book-drawer-title" aria-modal="true" className="drawer book-drawer" role="dialog">
        <div className="drawer__header">
          <span className="drawer__title">Reading list</span>
          <button aria-label="Close" className="drawer__close" onClick={onClose} ref={closeButton} type="button">
            <X size={18} />
          </button>
        </div>
        <div className="drawer__body book-drawer__body">
          <div className="book-drawer__intro">
            <BookCover book={book} size="drawer" />
            <div className="book-drawer__heading">
              <h2 className="book-drawer__title" id="book-drawer-title">{book.title}</h2>
              {book.subtitle ? <p className="book-drawer__subtitle">{book.subtitle}</p> : null}
              <p className="book-drawer__authors">{authorLine(book.authors)}</p>
              {facts ? <p className="book-drawer__facts">{facts}</p> : null}
              {book.purchaseUrl ? (
                <a
                  className="book-drawer__buy"
                  href={book.purchaseUrl}
                  onClick={() => track('Book Purchase Clicked', { slug: book.slug, from: 'drawer' })}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  {purchaseLabel(book.purchaseUrl)}
                  <ExternalLink aria-hidden="true" size={15} />
                </a>
              ) : null}
            </div>
          </div>

          {book.note ? (
            <section aria-labelledby="book-drawer-note" className="book-drawer__note">
              <h3 className="book-drawer__note-title" id="book-drawer-note">Why it&rsquo;s on my list</h3>
              <ReactMarkdown>{book.note}</ReactMarkdown>
            </section>
          ) : null}

          <div className="book-drawer__description">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{book.description ?? book.summary}</ReactMarkdown>
          </div>

          {book.sourceLabel ? (
            <p className="book-drawer__source">
              Found via{' '}
              {book.sourceUrl ? <SourceLink label={book.sourceLabel} url={book.sourceUrl} /> : book.sourceLabel}
            </p>
          ) : null}
        </div>
      </div>
    </div>
  )
}
