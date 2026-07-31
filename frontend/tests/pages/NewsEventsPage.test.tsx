import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NewsEventsPage } from '../../src/pages/NewsEventsPage'
import type { ArticlePage, ArticleResponse } from '../../src/types/news'
import type { EventPage } from '../../src/types/events'

vi.mock('../../src/services/newsApi', () => ({
  fetchNews: vi.fn(),
  fetchNewsSources: vi.fn(),
}))

vi.mock('../../src/services/eventsApi', () => ({
  fetchEvents: vi.fn(),
}))

vi.mock('../../src/services/favouritesApi', () => ({
  getFavourites: vi.fn(),
}))

// Keeps the auth context out of the picture: the hook itself is covered by
// tests/hooks/useFavourites.test.ts.
const favouriteIds = new Set<string>()

vi.mock('../../src/hooks/useFavourites', () => ({
  useFavourites: () => ({
    isFavourite: (id: string) => favouriteIds.has(id),
    toggleFavourite: vi.fn(),
    ensureAuthenticated: vi.fn(),
    loading: false,
  }),
}))

import { fetchNews, fetchNewsSources } from '../../src/services/newsApi'
import { fetchEvents } from '../../src/services/eventsApi'
import { getFavourites } from '../../src/services/favouritesApi'

function article(id: string, title: string, sourceName = 'InfoQ'): ArticleResponse {
  return {
    id,
    title,
    sourceName,
    originalUrl: `https://example.com/${id}`,
    summary: `${title} summary`,
    author: null,
    publishedDate: '2026-07-01T00:00:00Z',
    fetchedAt: '2026-07-01T01:00:00Z',
    visible: true,
    imageUrl: null,
  }
}

function newsPage(content: ArticleResponse[], number = 0, last = true): ArticlePage {
  return { content, totalElements: 120, totalPages: 5, number, size: 24, last }
}

const emptyEventPage: EventPage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
}

function renderPage() {
  return render(
    <MemoryRouter>
      <NewsEventsPage />
    </MemoryRouter>,
  )
}

describe('NewsEventsPage', () => {
  beforeEach(() => {
    favouriteIds.clear()
    vi.mocked(fetchNews).mockReset()
    vi.mocked(fetchNewsSources).mockReset()
    vi.mocked(fetchEvents).mockReset()
    vi.mocked(getFavourites).mockReset()

    vi.mocked(fetchEvents).mockResolvedValue(emptyEventPage)
    vi.mocked(fetchNewsSources).mockResolvedValue(['InfoQ'])
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))
  })

  it('requests the first page of 24 articles with no source filter', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('First article')).toBeInTheDocument()
    })
    expect(fetchNews).toHaveBeenCalledWith(0, 24, undefined)
    expect(fetchNews).toHaveBeenCalledTimes(1)
  })

  it('appends the next page on "Load more" without dropping earlier articles', async () => {
    vi.mocked(fetchNews).mockImplementation((page = 0) =>
      Promise.resolve(
        page === 0
          ? newsPage([article('a-1', 'Page one article')], 0, false)
          : newsPage([article('a-2', 'Page two article')], 1, true),
      ),
    )

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Page one article')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))

    await waitFor(() => {
      expect(screen.getByText('Page two article')).toBeInTheDocument()
    })
    expect(screen.getByText('Page one article')).toBeInTheDocument()
    expect(fetchNews).toHaveBeenCalledWith(1, 24, undefined)
  })

  it('hides "Load more" once the last page is loaded', async () => {
    vi.mocked(fetchNews).mockResolvedValue(
      newsPage([article('a-1', 'Only article')], 0, true),
    )

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Only article')).toBeInTheDocument()
    })

    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('re-queries the backend when a source chip is selected', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue(['Ars Technica', 'InfoQ'])
    vi.mocked(fetchNews).mockImplementation((...args) =>
      Promise.resolve(
        args[2] === 'InfoQ'
          ? newsPage([article('a-2', 'InfoQ only article')])
          : newsPage([article('a-1', 'Ars article', 'Ars Technica')]),
      ),
    )

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Ars article')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByRole('button', { name: 'InfoQ' }))

    await waitFor(() => {
      expect(fetchNews).toHaveBeenCalledWith(0, 24, 'InfoQ')
    })
    expect(screen.getByText('InfoQ only article')).toBeInTheDocument()
  })

  it('renders chips for sources that have no article on the first page', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue(['Ars Technica', 'InfoQ', 'The Pragmatic Engineer'])
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article', 'InfoQ')]))

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('First article')).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: 'Ars Technica' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'The Pragmatic Engineer' })).toBeInTheDocument()
  })

  it('discards a stale page-two response that arrives after a source switch', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue(['Ars Technica', 'InfoQ'])
    let resolvePageTwo: (value: ArticlePage) => void = () => {}
    vi.mocked(fetchNews).mockImplementation((...args) => {
      const [page = 0, , source] = args
      if (source === 'InfoQ') {
        return Promise.resolve(newsPage([article('a-9', 'InfoQ article')], 0, true))
      }
      if (page === 1) {
        return new Promise<ArticlePage>((resolve) => {
          resolvePageTwo = resolve
        })
      }
      return Promise.resolve(newsPage([article('a-1', 'Unfiltered page one', 'Ars Technica')], 0, false))
    })

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Unfiltered page one')).toBeInTheDocument()
    })

    // Load more is in flight when the visitor switches source.
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await userEvent.click(screen.getByRole('button', { name: 'InfoQ' }))
    await waitFor(() => {
      expect(screen.getByText('InfoQ article')).toBeInTheDocument()
    })

    resolvePageTwo(newsPage([article('a-2', 'Stale page two')], 1, true))

    await waitFor(() => {
      expect(screen.queryByText('Stale page two')).not.toBeInTheDocument()
    })
    expect(screen.getByText('InfoQ article')).toBeInTheDocument()
    expect(screen.queryByText('Unfiltered page one')).not.toBeInTheDocument()
  })

  it('shows only favourited articles when the favourites toggle is on', async () => {
    favouriteIds.add('fav-1')
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'Unfavourited article')]))
    vi.mocked(getFavourites).mockImplementation(((type: string) =>
      Promise.resolve(
        type === 'news'
          ? newsPage([article('fav-1', 'Favourite article'), article('a-1', 'Unfavourited article')])
          : emptyEventPage,
      )) as unknown as typeof getFavourites)

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Unfavourited article')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByRole('button', { name: /Show favourites only/ }))

    await waitFor(() => {
      expect(screen.getByText('Favourite article')).toBeInTheDocument()
    })
    expect(screen.queryByText('Unfavourited article')).not.toBeInTheDocument()
    // Favourites are a complete in-memory list, so news paging plays no part.
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
    expect(fetchNews).toHaveBeenCalledTimes(1)
  })

  it('shows a titled error with a working retry when the news request fails', async () => {
    vi.mocked(fetchNews).mockRejectedValueOnce(new Error('Unable to load news data.'))

    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Unable to load News & Events')
    })
    expect(screen.getByRole('alert')).toHaveTextContent('Unable to load news data.')

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => {
      expect(screen.getByText('First article')).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
