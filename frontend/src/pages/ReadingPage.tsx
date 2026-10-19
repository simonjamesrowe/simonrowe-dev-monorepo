import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { track } from '../analytics/analytics'
import { BookCard } from '../components/books/BookCard'
import { BookDrawer } from '../components/books/BookDrawer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { usePageTitle } from '../hooks/usePageTitle'
import { fetchBooks } from '../services/booksApi'
import { shelvesOf, type Book } from '../types/book'

/**
 * The Reading list: published books grouped under their categories, each opening a drawer with
 * the longer description and why it is on the list. The open book is `?book=<slug>` in the
 * address, so a drawer can be linked to and the back button closes it.
 */
export function ReadingPage() {
  usePageTitle('Reading list')
  const [books, setBooks] = useState<Book[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const openSlug = searchParams.get('book')

  useEffect(() => {
    let cancelled = false
    fetchBooks()
      .then(result => { if (!cancelled) setBooks(result) })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load the reading list.')
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  const shelves = useMemo(() => shelvesOf(books), [books])
  // An unknown or unpublished slug simply opens nothing.
  const openBook = openSlug ? books.find(book => book.slug === openSlug) : undefined

  const open = useCallback((slug: string) => {
    track('Book Opened', { slug })
    setSearchParams(params => {
      const next = new URLSearchParams(params)
      next.set('book', slug)
      return next
    })
  }, [setSearchParams])

  const close = useCallback(() => {
    setSearchParams(params => {
      const next = new URLSearchParams(params)
      next.delete('book')
      return next
    })
  }, [setSearchParams])

  return (
    <div className="reading-page">
      <header className="reading-page__header">
        <p className="portfolio-carousel__eyebrow">Reading list</p>
        <h1 className="reading-page__title">Recommended reading</h1>
        <p className="reading-page__intro">
          Books on building software and leading the people who build it, each with a note on why
          it is here. Open one for more, or go straight to buying it.
        </p>
      </header>
      {loading ? <LoadingIndicator message="Loading the reading list..." /> : null}
      {error ? (
        <ErrorMessage message={error} onRetry={() => window.location.reload()} title="Unable to load the reading list" />
      ) : null}
      {!loading && !error && books.length === 0 ? (
        <p className="reading-page__empty">Nothing on the shelf yet.</p>
      ) : null}
      {shelves.map(shelf => (
        <section aria-labelledby={`shelf-${slugOf(shelf.category)}`} className="reading-page__shelf" key={shelf.category}>
          <h2 className="reading-page__shelf-title" id={`shelf-${slugOf(shelf.category)}`}>{shelf.category}</h2>
          <ul className="reading-page__grid">
            {shelf.books.map(book => (
              <li key={book.slug}><BookCard book={book} onOpen={open} /></li>
            ))}
          </ul>
        </section>
      ))}
      {openBook ? <BookDrawer book={openBook} onClose={close} /> : null}
    </div>
  )
}

function slugOf(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
}
