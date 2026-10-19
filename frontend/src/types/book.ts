import type { ImageAsset } from './Profile'

/** A book on the Reading list, as `GET /api/books` returns it. Absent fields are omitted. */
export interface Book {
  slug: string
  title: string
  subtitle?: string
  authors: string[]
  /** The year the work was first published, not the year of the edition being sold. */
  publishedYear?: number
  category: string
  summary: string
  /** Markdown, shown in the drawer. */
  description?: string
  /** Why it is on the list, shown in the drawer. */
  note?: string
  coverImage?: ImageAsset
  purchaseUrl?: string
  sourceLabel?: string
  sourceUrl?: string
  displayOrder: number
}

export interface BookShelf {
  category: string
  books: Book[]
}

/**
 * The books grouped under their categories. Categories come in the order the first book of each
 * appears, so reordering the list in the CMS reorders the shelves too.
 */
export function shelvesOf(books: Book[]): BookShelf[] {
  const shelves = new Map<string, Book[]>()
  for (const book of books) {
    const shelf = shelves.get(book.category)
    if (shelf) shelf.push(book)
    else shelves.set(book.category, [book])
  }
  return [...shelves].map(([category, shelfBooks]) => ({ category, books: shelfBooks }))
}

/** "A", "A and B", "A, B and C". */
export function authorLine(authors: string[]): string {
  if (authors.length <= 1) return authors[0] ?? ''
  return `${authors.slice(0, -1).join(', ')} and ${authors[authors.length - 1]}`
}

/** Amazon's storefront hosts. Matched whole, so `amazon.co.uk.example.com` is not Amazon. */
const AMAZON_HOST = /^(?:www\.)?amazon\.(?:com|co\.uk|ie|de|fr|it|es|nl|ca|com\.au|in|co\.jp)$/

/** The purchase button's label, decided from the link's parsed host rather than its text. */
export function purchaseLabel(url: string): string {
  try {
    if (AMAZON_HOST.test(new URL(url).hostname)) return 'Buy on Amazon'
  } catch {
    // An unparseable link still gets a button; the server only stores https addresses.
  }
  return 'Buy the book'
}
