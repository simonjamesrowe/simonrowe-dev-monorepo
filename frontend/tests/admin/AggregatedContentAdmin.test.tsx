import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AggregatedContentAdmin } from '../../src/pages/admin/AggregatedContentAdmin'

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminNews: vi.fn(),
  fetchAdminEvents: vi.fn(),
  toggleArticleVisibility: vi.fn(),
  toggleEventVisibility: vi.fn(),
  deleteArticle: vi.fn(),
  deleteEvent: vi.fn(),
  triggerAggregation: vi.fn(),
  triggerDigest: vi.fn(),
  triggerSearchSync: vi.fn(),
  triggerEmbeddingSync: vi.fn(),
  importArticleUrl: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  fetchAdminEvents,
  fetchAdminNews,
  triggerAggregation,
  triggerDigest,
  triggerEmbeddingSync,
  triggerSearchSync,
  type AdminEvent,
  type AdminNewsArticle,
  type PageResponse,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchAdminNews = vi.mocked(fetchAdminNews)
const mockFetchAdminEvents = vi.mocked(fetchAdminEvents)
const mockTriggerAggregation = vi.mocked(triggerAggregation)
const mockTriggerDigest = vi.mocked(triggerDigest)
const mockTriggerSearchSync = vi.mocked(triggerSearchSync)
const mockTriggerEmbeddingSync = vi.mocked(triggerEmbeddingSync)
const mockUseAuth = vi.mocked(useAuth)

const mockGetAccessToken = vi.fn().mockResolvedValue('test-token')

function makeArticle(id: string): AdminNewsArticle {
  return {
    id,
    title: `Article ${id}`,
    sourceName: 'Example Source',
    publishedDate: '2026-01-01T00:00:00Z',
    visible: true,
    url: `https://example.com/${id}`,
  }
}

function makeEvent(id: string): AdminEvent {
  return {
    id,
    title: `Event ${id}`,
    sourceName: 'Example Source',
    eventDate: '2026-02-01T00:00:00Z',
    visible: true,
    url: `https://example.com/${id}`,
  }
}

function newsPage(
  options: { count?: number; totalElements?: number; totalPages?: number; number?: number } = {},
): PageResponse<AdminNewsArticle> {
  const { count = 2, totalElements = 2, totalPages = 1, number = 0 } = options
  return {
    content: Array.from({ length: count }, (_, i) => makeArticle(`news-${number}-${i}`)),
    totalElements,
    totalPages,
    size: 20,
    number,
  }
}

function eventsPage(
  options: { count?: number; totalElements?: number; totalPages?: number; number?: number } = {},
): PageResponse<AdminEvent> {
  const { count = 2, totalElements = 2, totalPages = 1, number = 0 } = options
  return {
    content: Array.from({ length: count }, (_, i) => makeEvent(`event-${number}-${i}`)),
    totalElements,
    totalPages,
    size: 20,
    number,
  }
}

/** Renders and waits for both lists to finish their initial load. */
async function renderPage() {
  render(<AggregatedContentAdmin />)
  await waitFor(() => {
    expect(screen.queryByText('Loading news...')).not.toBeInTheDocument()
  })
}

const showEventsTab = () => fireEvent.click(screen.getByRole('button', { name: /^Events \(/ }))
const showNewsTab = () => fireEvent.click(screen.getByRole('button', { name: /^News \(/ }))

// ConfirmDialog renders inside an aria-hidden backdrop, so it is invisible to
// role-based queries — reach for it by class and its buttons by text instead.
const confirmDialog = () => document.querySelector('.confirm-dialog')
const dialogButton = (label: string) =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('.confirm-dialog__btn')).find(
    (btn) => btn.textContent === label,
  ) as HTMLButtonElement

describe('AggregatedContentAdmin', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: undefined,
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: mockGetAccessToken,
    })
    mockFetchAdminNews.mockResolvedValue(newsPage())
    mockFetchAdminEvents.mockResolvedValue(eventsPage())
    mockTriggerAggregation.mockResolvedValue({})
    mockTriggerDigest.mockResolvedValue({})
    mockTriggerSearchSync.mockResolvedValue({})
    mockTriggerEmbeddingSync.mockResolvedValue({})
  })

  describe('paging', () => {
    it('renders pagination controls when there is more than one page', async () => {
      mockFetchAdminNews.mockResolvedValue(newsPage({ count: 20, totalElements: 45, totalPages: 3 }))

      await renderPage()

      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Previous' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument()
    })

    it('does not render pagination controls when there is only one page', async () => {
      await renderPage()

      expect(screen.queryByRole('button', { name: 'Previous' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
    })

    it('refetches with the incremented page when Next is clicked', async () => {
      mockFetchAdminNews.mockResolvedValue(newsPage({ count: 20, totalElements: 45, totalPages: 3 }))

      await renderPage()
      expect(mockFetchAdminNews).toHaveBeenLastCalledWith(mockGetAccessToken, 0)

      fireEvent.click(screen.getByRole('button', { name: 'Next' }))

      await waitFor(() => {
        expect(mockFetchAdminNews).toHaveBeenLastCalledWith(mockGetAccessToken, 1)
      })
      expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
    })

    it('refetches with the decremented page when Previous is clicked', async () => {
      mockFetchAdminNews.mockResolvedValue(newsPage({ count: 20, totalElements: 45, totalPages: 3 }))

      await renderPage()

      fireEvent.click(screen.getByRole('button', { name: 'Next' }))
      await waitFor(() => {
        expect(mockFetchAdminNews).toHaveBeenLastCalledWith(mockGetAccessToken, 1)
      })

      fireEvent.click(await screen.findByRole('button', { name: 'Previous' }))

      await waitFor(() => {
        expect(mockFetchAdminNews).toHaveBeenLastCalledWith(mockGetAccessToken, 0)
      })
      expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    })

    it('disables Previous on the first page and Next on the last page', async () => {
      mockFetchAdminNews.mockResolvedValue(newsPage({ count: 20, totalElements: 30, totalPages: 2 }))

      await renderPage()

      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()

      fireEvent.click(screen.getByRole('button', { name: 'Next' }))

      await waitFor(() => {
        expect(screen.getByText('Page 2 of 2')).toBeInTheDocument()
      })
      expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
    })

    it('renders tab counts from totalElements, not the size of the current page', async () => {
      mockFetchAdminNews.mockResolvedValue(newsPage({ count: 20, totalElements: 412, totalPages: 21 }))
      mockFetchAdminEvents.mockResolvedValue(eventsPage({ count: 20, totalElements: 87, totalPages: 5 }))

      await renderPage()

      expect(screen.getByRole('button', { name: 'News (412)' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Events (87)' })).toBeInTheDocument()
    })

    it('keeps independent page positions for the news and events tabs', async () => {
      mockFetchAdminNews.mockResolvedValue(newsPage({ count: 20, totalElements: 60, totalPages: 3 }))
      mockFetchAdminEvents.mockResolvedValue(eventsPage({ count: 20, totalElements: 60, totalPages: 3 }))

      await renderPage()

      // Advance news to page 2.
      fireEvent.click(screen.getByRole('button', { name: 'Next' }))
      await waitFor(() => {
        expect(mockFetchAdminNews).toHaveBeenLastCalledWith(mockGetAccessToken, 1)
      })
      expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()

      // Events is still on page 1 and was never refetched for a later page.
      showEventsTab()
      expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
      expect(mockFetchAdminEvents).toHaveBeenCalledTimes(1)
      expect(mockFetchAdminEvents).toHaveBeenLastCalledWith(mockGetAccessToken, 0)

      // Advance events to page 2, then confirm news kept its own position.
      fireEvent.click(screen.getByRole('button', { name: 'Next' }))
      await waitFor(() => {
        expect(mockFetchAdminEvents).toHaveBeenLastCalledWith(mockGetAccessToken, 1)
      })

      showNewsTab()
      expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
    })
  })

  describe('header actions', () => {
    it('calls triggerAggregation directly, with no confirmation dialog', async () => {
      await renderPage()

      fireEvent.click(screen.getByRole('button', { name: 'Fetch New Articles' }))

      await waitFor(() => {
        expect(mockTriggerAggregation).toHaveBeenCalledTimes(1)
      })
      expect(confirmDialog()).toBeNull()
      expect(mockTriggerAggregation).toHaveBeenCalledWith(mockGetAccessToken)
    })

    it('reloads both lists after aggregation completes', async () => {
      await renderPage()
      expect(mockFetchAdminNews).toHaveBeenCalledTimes(1)
      expect(mockFetchAdminEvents).toHaveBeenCalledTimes(1)

      fireEvent.click(screen.getByRole('button', { name: 'Fetch New Articles' }))

      await waitFor(() => {
        expect(mockFetchAdminNews).toHaveBeenCalledTimes(2)
        expect(mockFetchAdminEvents).toHaveBeenCalledTimes(2)
      })
    })

    it('does not call triggerDigest until the confirmation dialog is confirmed', async () => {
      await renderPage()

      fireEvent.click(screen.getByRole('button', { name: 'Generate Digest Blog Post' }))

      const dialog = confirmDialog()
      expect(dialog).not.toBeNull()
      expect(dialog).toHaveTextContent('Generate Digest Blog Post')
      expect(dialog).toHaveTextContent(
        'This will use AI to write a new blog post summarising recent blogs and articles, and publish it live on the site immediately. Continue?',
      )
      expect(mockTriggerDigest).not.toHaveBeenCalled()

      fireEvent.click(dialogButton('Generate'))

      await waitFor(() => {
        expect(mockTriggerDigest).toHaveBeenCalledTimes(1)
      })
      expect(mockTriggerDigest).toHaveBeenCalledWith(mockGetAccessToken)
      expect(confirmDialog()).toBeNull()
    })

    it('fires no digest request when the confirmation is cancelled', async () => {
      await renderPage()

      fireEvent.click(screen.getByRole('button', { name: 'Generate Digest Blog Post' }))
      expect(confirmDialog()).not.toBeNull()

      fireEvent.click(dialogButton('Cancel'))

      await waitFor(() => {
        expect(confirmDialog()).toBeNull()
      })
      expect(mockTriggerDigest).not.toHaveBeenCalled()
    })

    it('calls triggerSearchSync from the Maintenance menu', async () => {
      await renderPage()

      fireEvent.click(screen.getByRole('button', { name: /Maintenance/ }))
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rebuild Search Index' }))

      await waitFor(() => {
        expect(mockTriggerSearchSync).toHaveBeenCalledTimes(1)
      })
      expect(mockTriggerSearchSync).toHaveBeenCalledWith(mockGetAccessToken)
      expect(await screen.findByText('Search sync triggered.')).toBeInTheDocument()
    })

    it('calls triggerEmbeddingSync from the Maintenance menu', async () => {
      await renderPage()

      fireEvent.click(screen.getByRole('button', { name: /Maintenance/ }))
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rebuild Embeddings' }))

      await waitFor(() => {
        expect(mockTriggerEmbeddingSync).toHaveBeenCalledTimes(1)
      })
      expect(mockTriggerEmbeddingSync).toHaveBeenCalledWith(mockGetAccessToken)
      expect(await screen.findByText('Embedding sync triggered.')).toBeInTheDocument()
    })
  })
})
