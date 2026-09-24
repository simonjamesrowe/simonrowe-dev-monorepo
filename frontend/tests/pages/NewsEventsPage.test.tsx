import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NewsEventsPage } from '../../src/pages/NewsEventsPage'
import type { ArticlePage, ArticleResponse, SourceSummary } from '../../src/types/news'
import type { EventPage, EventResponse } from '../../src/types/events'

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

function sampleEvent(id: string, title: string): EventResponse {
  return {
    id,
    title,
    sourceName: 'Meetup',
    originalUrl: `https://example.com/${id}`,
    summary: `${title} summary`,
    eventDate: '2099-09-01T18:00:00Z',
    venue: 'Somewhere',
    location: 'London',
    imageUrl: null,
  } as EventResponse
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

/** The sources multi-select is behind one click; every filter test starts here. */
async function openSources() {
  await userEvent.click(screen.getByRole('button', { name: /sources|^All sources$/ }))
}

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

  it('requests the first page of 24 articles with no filters', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('First article')).toBeInTheDocument()
    })
    expect(fetchNews).toHaveBeenCalledWith(0, 24, { sources: [], query: '' })
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
    expect(fetchNews).toHaveBeenCalledWith(1, 24, { sources: [], query: '' })
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

  it('re-queries the backend when a source is ticked in the dropdown', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ')])
    vi.mocked(fetchNews).mockImplementation((...args) =>
      Promise.resolve(
        args[2]?.sources?.includes('InfoQ')
          ? newsPage([article('a-2', 'InfoQ only article')])
          : newsPage([article('a-1', 'Ars article', 'Ars Technica')]),
      ),
    )

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Ars article')).toBeInTheDocument()
    })

    await openSources()
    await userEvent.click(screen.getByRole('checkbox', { name: /InfoQ/ }))

    await waitFor(() => {
      expect(fetchNews).toHaveBeenCalledWith(0, 24, { sources: ['InfoQ'], query: '' })
    })
    expect(screen.getByText('InfoQ only article')).toBeInTheDocument()
  })

  it('sends every ticked source, because they narrow together rather than replace', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()
    await userEvent.click(screen.getByRole('checkbox', { name: /Rundown AI/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /Spring Blog/ }))

    await waitFor(() => {
      expect(fetchNews).toHaveBeenCalledWith(
        0, 24, { sources: ['Rundown AI', 'Spring Blog'], query: '' })
    })
  })

  it('unticking a source removes only that source from the query', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()
    await userEvent.click(screen.getByRole('checkbox', { name: /Rundown AI/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /Spring Blog/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /Rundown AI/ }))

    await waitFor(() => {
      expect(fetchNews).toHaveBeenLastCalledWith(
        0, 24, { sources: ['Spring Blog'], query: '' })
    })
  })

  it('names the selection on the closed toggle so the filter stays visible', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /All sources/ })).toBeInTheDocument()

    await openSources()
    await userEvent.click(screen.getByRole('checkbox', { name: /Spring Blog/ }))

    expect(screen.getByRole('button', { name: /^Spring Blog/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: /Rundown AI/ }))

    expect(screen.getByRole('button', { name: /2 sources/ })).toBeInTheDocument()
  })

  it('"Clear" puts the feed back to every source', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Rundown AI', 298)])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()
    await userEvent.click(screen.getByRole('checkbox', { name: /Rundown AI/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))

    await waitFor(() => {
      expect(fetchNews).toHaveBeenLastCalledWith(0, 24, { sources: [], query: '' })
    })
    expect(screen.getByRole('button', { name: /All sources/ })).toBeInTheDocument()
  })

  it('lists every source in the dropdown, however few articles it holds', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('blog.cloudflare.com', 2),
      source('ssntpl.com', 1),
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()

    // No threshold and no overflow menu any more: a one-off manual import is as
    // selectable as the busiest feed, which is what the pill row could not manage.
    expect(screen.getByRole('checkbox', { name: /blog\.cloudflare\.com/ })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /ssntpl\.com/ })).toBeInTheDocument()
  })

  it('orders sources by article count, busiest first', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Dan Vega', 16),
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()

    const names = screen.getAllByRole('checkbox').map(box => box.closest('label')?.textContent)
    expect(names).toEqual(['Rundown AI298', 'Spring Blog81', 'Dan Vega16'])
  })

  it('closes the source dropdown on a click outside it', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Rundown AI', 298)])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()
    expect(screen.getByRole('checkbox', { name: /Rundown AI/ })).toBeInTheDocument()

    await userEvent.click(document.body)

    expect(screen.queryByRole('checkbox', { name: /Rundown AI/ })).toBeNull()
  })

  it('closes the source dropdown on Escape and returns focus to the toggle', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Rundown AI', 298)])
    renderPage()
    await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

    await openSources()
    expect(screen.getByRole('checkbox', { name: /Rundown AI/ })).toBeInTheDocument()

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('checkbox', { name: /Rundown AI/ })).toBeNull()
    expect(screen.getByRole('button', { name: /All sources/ })).toHaveFocus()
  })

  it('discards a stale page-two response that arrives after a source switch', async () => {
    vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ')])
    let resolvePageTwo: (value: ArticlePage) => void = () => {}
    vi.mocked(fetchNews).mockImplementation((...args) => {
      const [page = 0, , filters] = args
      if (filters?.sources?.includes('InfoQ')) {
        return Promise.resolve(newsPage([article('a-9', 'InfoQ article')], 0, true))
      }
      if (page === 1) {
        return new Promise<ArticlePage>((resolve) => {
          resolvePageTwo = resolve
        })
      }
      return Promise.resolve(
        newsPage([article('a-1', 'Unfiltered page one', 'Ars Technica')], 0, false))
    })

    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Unfiltered page one')).toBeInTheDocument()
    })

    // Load more is in flight when the visitor switches source.
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await openSources()
    await userEvent.click(screen.getByRole('checkbox', { name: /InfoQ/ }))
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
    expect(screen.getByRole('button', { name: /All sources/ })).toBeInTheDocument()

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

  describe('the search box', () => {
    it('re-queries the feed with what was typed', async () => {
      vi.mocked(fetchNews).mockImplementation((...args) =>
        Promise.resolve(
          args[2]?.query === 'sdlc'
            ? newsPage([article('a-9', 'The AI-Native SDLC playbook')])
            : newsPage([article('a-1', 'First article')]),
        ),
      )

      renderPage()
      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'sdlc')

      await waitFor(() => {
        expect(screen.getByText('The AI-Native SDLC playbook')).toBeInTheDocument()
      })
      expect(fetchNews).toHaveBeenLastCalledWith(0, 24, { sources: [], query: 'sdlc' })
    })

    /**
     * Four keystrokes, one request. Each one re-reads page zero and replaces the grid, so
     * without the debounce typing a word visibly thrashes the list.
     */
    it('makes one request for a word rather than one per keystroke', async () => {
      renderPage()
      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
      vi.mocked(fetchNews).mockClear()

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'sdlc')

      await waitFor(() => {
        expect(fetchNews).toHaveBeenCalledWith(0, 24, { sources: [], query: 'sdlc' })
      })
      expect(fetchNews).toHaveBeenCalledTimes(1)
    })

    it('carries the text into "Load more", so paging stays inside the matching set', async () => {
      vi.mocked(fetchNews).mockImplementation((page = 0) =>
        Promise.resolve(page === 0
          ? newsPage([article('a-1', 'First article')], 0, false)
          : newsPage([article('a-2', 'Second article')], 1, true)))

      renderPage()
      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'sdlc')
      await waitFor(() => {
        expect(fetchNews).toHaveBeenCalledWith(0, 24, { sources: [], query: 'sdlc' })
      })
      await userEvent.click(screen.getByRole('button', { name: 'Load more' }))

      await waitFor(() => {
        expect(fetchNews).toHaveBeenLastCalledWith(0 + 1, 24, { sources: [], query: 'sdlc' })
      })
    })

    it('combines the text with the ticked sources', async () => {
      vi.mocked(fetchNewsSources).mockResolvedValue([source('Claude Blog', 40)])

      renderPage()
      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

      await openSources()
      await userEvent.click(screen.getByRole('checkbox', { name: /Claude Blog/ }))
      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'agents')

      await waitFor(() => {
        expect(fetchNews).toHaveBeenLastCalledWith(
          0, 24, { sources: ['Claude Blog'], query: 'agents' })
      })
    })

    it('clears back to the unfiltered feed', async () => {
      renderPage()
      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'sdlc')
      await waitFor(() => {
        expect(fetchNews).toHaveBeenCalledWith(0, 24, { sources: [], query: 'sdlc' })
      })

      await userEvent.click(screen.getByRole('button', { name: 'Clear search' }))

      await waitFor(() => {
        expect(fetchNews).toHaveBeenLastCalledWith(0, 24, { sources: [], query: '' })
      })
    })

    /**
     * "Nothing here" and "nothing here because of what you typed" are different states,
     * and only the second one has a way out worth offering.
     */
    it('names what was searched for when nothing matches, and offers a way back', async () => {
      vi.mocked(fetchNews).mockImplementation((...args) =>
        Promise.resolve(
          args[2]?.query ? newsPage([]) : newsPage([article('a-1', 'First article')]),
        ),
      )

      renderPage()
      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'zzz')

      await waitFor(() => {
        expect(screen.getByText('Nothing matches \u201Czzz\u201D.')).toBeInTheDocument()
      })

      await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

      await waitFor(() => expect(screen.getByText('First article')).toBeInTheDocument())
    })

    /**
     * "No upcoming events match" stacked above "Nothing matches" reads as a page that
     * half-loaded. One search, one answer.
     */
    it('shows one empty state, not two, when nothing matched anywhere', async () => {
      vi.mocked(fetchEvents).mockImplementation(((_page: number, _size: number, upcoming: boolean) =>
        Promise.resolve(upcoming
          ? { ...emptyEventPage, content: [sampleEvent('e-1', 'A conference')], totalElements: 1 }
          : emptyEventPage)) as unknown as typeof fetchEvents)
      vi.mocked(fetchNews).mockImplementation((...args) =>
        Promise.resolve(
          args[2]?.query ? newsPage([]) : newsPage([article('a-1', 'First article')]),
        ),
      )

      renderPage()
      await waitFor(() => expect(screen.getByText('A conference')).toBeInTheDocument())

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'zzz')

      await waitFor(() => {
        expect(screen.getByText('Nothing matches \u201Czzz\u201D.')).toBeInTheDocument()
      })
      expect(screen.queryByText(/No upcoming events match/)).not.toBeInTheDocument()
    })

    /** But it is worth saying when the articles did match and the events did not. */
    it('still reports an unmatched timeline when the articles matched', async () => {
      vi.mocked(fetchEvents).mockImplementation(((_page: number, _size: number, upcoming: boolean) =>
        Promise.resolve(upcoming
          ? { ...emptyEventPage, content: [sampleEvent('e-1', 'A conference')], totalElements: 1 }
          : emptyEventPage)) as unknown as typeof fetchEvents)
      vi.mocked(fetchNews).mockResolvedValue(newsPage([article('a-1', 'An SDLC article')]))

      renderPage()
      await waitFor(() => expect(screen.getByText('A conference')).toBeInTheDocument())

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'sdlc')

      await waitFor(() => {
        expect(screen.getByText('No upcoming events match \u201Csdlc\u201D.')).toBeInTheDocument()
      })
      expect(screen.getByText('An SDLC article')).toBeInTheDocument()
    })

    /**
     * Events are a complete in-memory list that never goes back to the server, so the
     * filtering the backend does for articles has to be done here for them.
     */
    it('filters the events timeline too', async () => {
      vi.mocked(fetchEvents).mockImplementation(((_page: number, _size: number, upcoming: boolean) =>
        Promise.resolve(upcoming
          ? {
            ...emptyEventPage,
            content: [
              { ...sampleEvent('e-1', 'Spring I/O Barcelona'), venue: 'Barcelona' },
              { ...sampleEvent('e-2', 'A Kafka meetup'), venue: 'London' },
            ],
            totalElements: 2,
          }
          : emptyEventPage)) as unknown as typeof fetchEvents)
      vi.mocked(fetchNews).mockResolvedValue(newsPage([]))

      renderPage()
      await waitFor(() => expect(screen.getByText('Spring I/O Barcelona')).toBeInTheDocument())
      expect(screen.getByText('A Kafka meetup')).toBeInTheDocument()

      await userEvent.type(screen.getByRole('searchbox', { name: 'Search news and events' }), 'kafka')

      await waitFor(() => {
        expect(screen.queryByText('Spring I/O Barcelona')).not.toBeInTheDocument()
      })
      expect(screen.getByText('A Kafka meetup')).toBeInTheDocument()
    })
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
