import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NewsEventsPage } from '../../src/pages/NewsEventsPage'
import type { ArticlePage, ArticleResponse, SourceSummary } from '../../src/types/news'
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

// The summary hook itself is covered by tests/hooks/useArticleSummaries.test.ts; here we
// only need the id set it exposes, to assert which label each card gets.
const summarisedIds = new Set<string>()

vi.mock('../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn().mockResolvedValue({
    state: 'NOT_REQUESTED', version: 0, retryable: false, message: '',
  }),
  fetchSummarisedArticleIds: vi.fn(() => Promise.resolve([...summarisedIds])),
  requestArticleSummary: vi.fn().mockResolvedValue({
    state: 'READY', version: 2, body: 'Prose.', retryable: false, message: '',
  }),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    getAccessToken: vi.fn().mockResolvedValue('token'),
    loginWithPopup: vi.fn(),
  }),
}))

import { fetchNews, fetchNewsSources } from '../../src/services/newsApi'
import { fetchEvents } from '../../src/services/eventsApi'
import { getFavourites } from '../../src/services/favouritesApi'
import { NarrationAudioStub } from '../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../testUtils/narrationAudioValue'

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

function source(name: string, count = 5): SourceSummary {
  return { name, count }
}

const emptyEventPage: EventPage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
}

/**
 * `narration` is reassigned per test so a case can seed "this article has audio" or an in-flight
 * stage before rendering. `renderPage` reads whatever is current.
 */
let narration = narrationAudioStub()

function renderPage() {
  return render(
    <MemoryRouter>
      <NarrationAudioStub value={narration}>
        <NewsEventsPage />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

describe('NewsEventsPage', () => {
  beforeEach(() => {
    narration = narrationAudioStub()
    favouriteIds.clear()
    summarisedIds.clear()
    vi.mocked(fetchNews).mockReset()
    vi.mocked(fetchNewsSources).mockReset()
    vi.mocked(fetchEvents).mockReset()
    vi.mocked(getFavourites).mockReset()

    vi.mocked(fetchEvents).mockResolvedValue(emptyEventPage)
    vi.mocked(fetchNewsSources).mockResolvedValue([source('InfoQ')])
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
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ')])
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
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ'), source('The Pragmatic Engineer')])
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article', 'InfoQ')]))

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('First article')).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: 'Ars Technica' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'The Pragmatic Engineer' })).toBeInTheDocument()
  })

  it('discards a stale page-two response that arrives after a source switch', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ')])
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

  it('orders source pills by article count, busiest first', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Dan Vega', 16),
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())

    const pills = screen.getAllByRole('button').map(b => b.textContent)
    expect(pills.indexOf('Rundown AI')).toBeLessThan(pills.indexOf('Spring Blog'))
    expect(pills.indexOf('Spring Blog')).toBeLessThan(pills.indexOf('Dan Vega'))
  })

  it('hides sources with fewer than three articles behind the More menu', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('blog.cloudflare.com', 2),
      source('ssntpl.com', 1),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())

    // Regex, not an exact string: a menu row's accessible name is its source name
    // followed by its article count ("blog.cloudflare.com 2").
    expect(screen.queryByRole('button', { name: /blog\.cloudflare\.com/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'More (2)' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'More (2)' }))

    expect(screen.getByRole('button', { name: /blog\.cloudflare\.com/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /ssntpl\.com/ })).toBeInTheDocument()
  })

  it('filters by a source chosen from the More menu and shows it as active', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('ssntpl.com', 1),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'More (1)' }))
    await userEvent.click(screen.getByRole('button', { name: /ssntpl\.com/ }))

    await waitFor(() =>
      expect(fetchNews).toHaveBeenCalledWith(0, 24, 'ssntpl.com'),
    )
    // The menu has closed, so this now matches the toggle itself: the active filter
    // must stay visible even though the source is collapsed out of the main row.
    expect(screen.getByRole('button', { name: 'ssntpl.com' })).toBeInTheDocument()
  })

  it('closes the More menu on Escape and returns focus to the toggle', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('ssntpl.com', 1),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'More (1)' }))
    expect(screen.getByRole('button', { name: /ssntpl\.com/ })).toBeInTheDocument()

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('button', { name: /ssntpl\.com/ })).toBeNull()
    expect(screen.getByRole('button', { name: /^More/ })).toHaveFocus()
  })

  it('renders no More button when every source clears the threshold', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())

    expect(screen.queryByRole('button', { name: /^More/ })).toBeNull()
  })

  it('offers "Summarise" on a card with no summary and "Read summary" on one with', async () => {
    summarisedIds.add('a-2')
    vi.mocked(fetchNews).mockResolvedValue(newsPage([
      article('a-1', 'Unsummarised article'),
      article('a-2', 'Summarised article'),
    ]))

    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('button', {
        name: /Generate an AI summary of Unsummarised article/,
      })).toHaveTextContent('Summarise')
    })
    expect(screen.getByRole('button', {
      name: /Read the AI-generated summary of Summarised article/,
    })).toHaveTextContent('Read summary')
  })

  it('opens the summary drawer over the list without losing the page behind it', async () => {
    summarisedIds.add('a-1')
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))

    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', {
      name: /Read the AI-generated summary of First article/,
    }))

    await waitFor(() =>
      expect(screen.getByText('AI-generated summary')).toBeInTheDocument())
    // The list is still mounted underneath, filters and all.
    expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    await waitFor(() =>
      expect(screen.queryByText('AI-generated summary')).not.toBeInTheDocument())
    expect(screen.getByText('First article')).toBeInTheDocument()
  })

  it('shows no summary control on event timeline items', async () => {
    vi.mocked(fetchEvents).mockResolvedValue({
      ...emptyEventPage,
      content: [{
        id: 'e-1',
        title: 'A conference',
        sourceName: 'Meetup',
        originalUrl: 'https://example.com/e-1',
        summary: 'Event summary',
        eventDate: '2026-09-01T18:00:00Z',
        venue: 'Somewhere',
        location: 'London',
        imageUrl: null,
      }] as never,
      totalElements: 1,
      totalPages: 1,
    })
    vi.mocked(fetchNews).mockResolvedValue(newsPage([]))

    renderPage()

    await waitFor(() => expect(screen.getByText('A conference')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /AI summary of A conference/ }))
      .not.toBeInTheDocument()
    expect(screen.queryByText('Summarise')).not.toBeInTheDocument()
  })

  describe('the listen control', () => {
    it('advertises the duration for an article whose summary already has audio', async () => {
      narration = narrationAudioStub({
        ready: {
          'ARTICLE_SUMMARY:a-1': {
            contentId: 'a-1',
            audioUrl: '/uploads/narrations/aaa/narration.mp3',
            durationSeconds: 180,
          },
        },
      })
      vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))

      renderPage()

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      expect(screen.getByRole('button', {
        name: 'Listen to the 3 min audio version of First article',
      })).toBeInTheDocument()
    })

    it('offers the cold Listen invitation for an article with no audio', async () => {
      vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))

      renderPage()

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      expect(screen.getByRole('button', {
        name: 'Generate an audio version of First article',
      })).toBeInTheDocument()
    })

    /** Three controls maximum: listen, summarise, favourite. */
    it('sits alongside the summary and favourite controls, and no more', async () => {
      vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))

      renderPage()

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      const actions = document.querySelector('.feed__card-actions')!
      expect(actions.querySelectorAll('button')).toHaveLength(3)
      expect(actions.querySelector('.listen-button')).toBeInTheDocument()
      expect(actions.querySelector('.summary-button')).toBeInTheDocument()
    })

    /**
     * The Listen chain can produce a summary as an intermediate step. The provider sits above
     * this page and publishes what finished; the page relays it into `useArticleSummaries`, so
     * the card's summary control has to catch up without a reload.
     */
    it('flips the summary control when the Listen chain generated the summary', async () => {
      narration = narrationAudioStub()
      narration.lastCompleted = {
        contentType: 'ARTICLE_SUMMARY',
        contentId: 'a-1',
        summaryWasGenerated: true,
      }
      vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))

      renderPage()

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      // Would read "Summarise" without the noteSummarised relay: the ids set was fetched
      // before the chain ran and is never refetched.
      await waitFor(() => {
        expect(screen.getByRole('button', {
          name: 'Read the AI-generated summary of First article',
        })).toBeInTheDocument()
      })
    })

    it('leaves the summary control alone when the chain narrated an existing summary', async () => {
      narration = narrationAudioStub()
      narration.lastCompleted = {
        contentType: 'ARTICLE_SUMMARY',
        contentId: 'a-1',
        summaryWasGenerated: false,
      }
      vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'First article')]))

      renderPage()

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      expect(screen.getByRole('button', {
        name: 'Generate an AI summary of First article',
      })).toBeInTheDocument()
    })

    it('shows the stage on the card while its audio is being generated', async () => {
      narration = narrationAudioStub({
        stages: { 'ARTICLE_SUMMARY:a-1': 'summarising' },
      })
      vi.mocked(fetchNews).mockResolvedValue(newsPage([
        article('a-1', 'First article'),
        article('a-2', 'Second article'),
      ]))

      renderPage()

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      expect(screen.getByRole('button', { name: 'Summarising… for First article' }))
        .toBeDisabled()
      // The rest of the list is unaffected.
      expect(screen.getByRole('button', {
        name: 'Generate an audio version of Second article',
      })).toBeEnabled()
    })

    /** Events are never summarised, so they can never have audio. */
    it('is absent from event timeline items', async () => {
      vi.mocked(fetchEvents).mockResolvedValue({
        ...emptyEventPage,
        content: [{
          id: 'e-1',
          title: 'A conference',
          sourceName: 'Meetup',
          originalUrl: 'https://example.com/e-1',
          summary: 'Event summary',
          eventDate: '2026-09-01T18:00:00Z',
          venue: 'Somewhere',
          location: 'London',
          imageUrl: null,
        }] as never,
        totalElements: 1,
        totalPages: 1,
      })
      vi.mocked(fetchNews).mockResolvedValue(newsPage([]))

      renderPage()

      await waitFor(() => expect(screen.getByText('A conference')).toBeInTheDocument())
      expect(screen.queryByText('Listen')).not.toBeInTheDocument()
      expect(document.querySelector('.listen-button')).not.toBeInTheDocument()
    })
  })
})
