import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NewsEventsPage } from '../../src/pages/NewsEventsPage'
import type { ArticlePage, ArticleResponse } from '../../src/types/news'
import type { EventPage, EventResponse } from '../../src/types/events'

vi.mock('../../src/services/newsApi', () => ({
  fetchNews: vi.fn(),
  fetchNewsById: vi.fn(),
  fetchNewsSources: vi.fn(),
}))

vi.mock('../../src/services/eventsApi', () => ({
  fetchEvents: vi.fn(),
  fetchEventsById: vi.fn(),
}))

vi.mock('../../src/services/favouritesApi', () => ({
  getFavourites: vi.fn(),
}))

vi.mock('../../src/hooks/useFavourites', () => ({
  useFavourites: () => ({
    isFavourite: () => false,
    toggleFavourite: vi.fn(),
    ensureAuthenticated: vi.fn(),
    loading: false,
  }),
}))

vi.mock('../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn().mockResolvedValue({
    state: 'READY', version: 2, body: 'Generated prose.', retryable: false, message: '',
  }),
  fetchSummarisedArticleIds: vi.fn(() => Promise.resolve([])),
  requestArticleSummary: vi.fn().mockResolvedValue({
    state: 'READY', version: 2, body: 'Generated prose.', retryable: false, message: '',
  }),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    getAccessToken: vi.fn().mockResolvedValue('token'),
    loginWithPopup: vi.fn(),
  }),
}))

import { fetchNews, fetchNewsById, fetchNewsSources } from '../../src/services/newsApi'
import { fetchEvents, fetchEventsById } from '../../src/services/eventsApi'
import { getFavourites } from '../../src/services/favouritesApi'
import { NarrationAudioStub } from '../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../testUtils/narrationAudioValue'

function article(id: string, title: string): ArticleResponse {
  return {
    id,
    title,
    sourceName: 'InfoQ',
    originalUrl: `https://example.com/${id}`,
    summary: `${title} summary`,
    author: null,
    publishedDate: '2026-07-01T00:00:00Z',
    fetchedAt: '2026-07-01T01:00:00Z',
    visible: true,
    imageUrl: null,
    shortUrl: `https://simonrowe.dev/s/${id}`,
  }
}

function event(id: string, title: string): EventResponse {
  return {
    id,
    title,
    sourceName: 'Meetup',
    originalUrl: `https://example.com/${id}`,
    summary: `${title} summary`,
    description: null,
    eventDate: '2026-12-01T18:00:00Z',
    eventEndDate: null,
    venue: 'Somewhere',
    location: 'London',
    fetchedAt: '2026-07-01T01:00:00Z',
    visible: true,
    shortUrl: `https://simonrowe.dev/s/${id}`,
  }
}

function newsPage(content: ArticleResponse[]): ArticlePage {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 24, last: true }
}

function eventPage(content: EventResponse[]): EventPage {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 }
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <NarrationAudioStub value={narrationAudioStub()}>
        <NewsEventsPage />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

describe('NewsEventsPage deep links', () => {
  beforeEach(() => {
    vi.mocked(fetchNews).mockReset()
    vi.mocked(fetchNewsById).mockReset()
    vi.mocked(fetchNewsSources).mockReset()
    vi.mocked(fetchEvents).mockReset()
    vi.mocked(fetchEventsById).mockReset()
    vi.mocked(getFavourites).mockReset()

    vi.mocked(fetchNewsSources).mockResolvedValue([{ name: 'InfoQ', count: 5 }])
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))
    vi.mocked(fetchEvents).mockResolvedValue(eventPage([]))
  })

  describe('?article=', () => {
    it('opens the summary panel for an article on the loaded page', async () => {
      renderAt('/news-events?article=a-1')

      expect(await screen.findByText('Generated prose.')).toBeInTheDocument()
      // Already on the page, so no targeted fetch was needed.
      expect(fetchNewsById).not.toHaveBeenCalled()
    })

    it('fetches an article that has fallen off the loaded page and still opens it', async () => {
      // The case a shared link hits most often once it is a few weeks old, and the one
      // most likely to be missed: without the targeted fetch the page loads and then
      // silently does nothing at all.
      vi.mocked(fetchNewsById).mockResolvedValue(article('a-99', 'An older article'))

      renderAt('/news-events?article=a-99')

      await waitFor(() => expect(fetchNewsById).toHaveBeenCalledWith('a-99'))
      expect(await screen.findByText('Generated prose.')).toBeInTheDocument()
    })

    it('leaves the rest of the feed working when the shared article is gone', async () => {
      vi.mocked(fetchNewsById).mockRejectedValue(new Error('Article not found'))

      renderAt('/news-events?article=deleted')

      await waitFor(() => expect(fetchNewsById).toHaveBeenCalledWith('deleted'))
      // No page-wide error banner over one dead link.
      expect(await screen.findByText('First article')).toBeInTheDocument()
      expect(screen.queryByText('Unable to load News & Events')).not.toBeInTheDocument()
    })

    it('opens nothing when there is no query parameter', async () => {
      renderAt('/news-events')

      expect(await screen.findByText('First article')).toBeInTheDocument()
      expect(fetchNewsById).not.toHaveBeenCalled()
      expect(screen.queryByText('Generated prose.')).not.toBeInTheDocument()
    })
  })

  describe('?event=', () => {
    it('shows an event already in the loaded timeline', async () => {
      vi.mocked(fetchEvents).mockResolvedValue(eventPage([event('e-1', 'Devoxx UK 2026')]))

      renderAt('/news-events?event=e-1')

      expect(await screen.findByText('Devoxx UK 2026')).toBeInTheDocument()
      expect(fetchEventsById).not.toHaveBeenCalled()
    })

    it('fetches an event that is not in the loaded timeline and shows its card', async () => {
      // Events have no drawer, so the card is the destination — it has to exist, whether
      // the event is past, off the page, or both.
      vi.mocked(fetchEventsById).mockResolvedValue(event('e-99', 'A past meetup'))

      renderAt('/news-events?event=e-99')

      await waitFor(() => expect(fetchEventsById).toHaveBeenCalledWith('e-99'))
      expect(await screen.findByText('A past meetup')).toBeInTheDocument()
    })
  })

  describe('card identifiers', () => {
    it('gives every article and event card its own id so a link can scroll to it', async () => {
      // useScrollToHash was already mounted but had nothing to find: only the #news and
      // #events sections carried ids.
      vi.mocked(fetchEvents).mockResolvedValue(eventPage([event('e-1', 'Devoxx UK 2026')]))

      const { container } = renderAt('/news-events')

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      expect(container.querySelector('#a-1')).not.toBeNull()
      expect(container.querySelector('#e-1')).not.toBeNull()
    })
  })

  describe('the Share control', () => {
    it('appears on an article card that has a share link', async () => {
      renderAt('/news-events')

      expect(await screen.findByRole('button', { name: 'Share First article' }))
        .toBeInTheDocument()
    })

    it('is absent on an article with no link minted yet', async () => {
      const withoutLink = { ...article('a-1', 'First article'), shortUrl: null }
      vi.mocked(fetchNews).mockResolvedValue(newsPage([withoutLink]))

      renderAt('/news-events')

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /^Share / })).not.toBeInTheDocument()
    })

    it('appears on an event card that has a share link', async () => {
      vi.mocked(fetchEvents).mockResolvedValue(eventPage([event('e-1', 'Devoxx UK 2026')]))

      renderAt('/news-events')

      expect(await screen.findByRole('button', { name: 'Share Devoxx UK 2026' }))
        .toBeInTheDocument()
    })
  })
})
