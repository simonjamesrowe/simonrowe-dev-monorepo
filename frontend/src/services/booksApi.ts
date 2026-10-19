import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { Book } from '../types/book'

const BOOKS_ENDPOINT = `${API_BASE_URL}/api/books`

/** Every published book, in display order, with everything the drawer shows. */
export async function fetchBooks(): Promise<Book[]> {
  return fetchWithRetry<Book[]>(BOOKS_ENDPOINT, {
    fallbackMessage: 'Unable to load the reading list.',
  })
}
