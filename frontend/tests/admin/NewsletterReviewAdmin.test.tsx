import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NewsletterReviewAdmin } from '../../src/pages/admin/NewsletterReviewAdmin'

vi.mock('../../src/services/adminApi', () => ({
  fetchContentSources: vi.fn(),
  fetchNewsletterCandidates: vi.fn(),
  fetchNewsletterReviewSummary: vi.fn(),
  promoteNewsletterCandidate: vi.fn(),
  dismissNewsletterCandidate: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  dismissNewsletterCandidate,
  fetchContentSources,
  fetchNewsletterCandidates,
  fetchNewsletterReviewSummary,
  promoteNewsletterCandidate,
  type AdminContentSource,
  type AdminNewsletterCandidate,
  type NewsletterReviewSummary,
  type PageResponse,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchCandidates = vi.mocked(fetchNewsletterCandidates)
const mockFetchSummary = vi.mocked(fetchNewsletterReviewSummary)
const mockFetchSources = vi.mocked(fetchContentSources)
const mockPromote = vi.mocked(promoteNewsletterCandidate)
const mockDismiss = vi.mocked(dismissNewsletterCandidate)
const mockUseAuth = vi.mocked(useAuth)
const mockGetAccessToken = vi.fn().mockResolvedValue('test-token')

function candidate(
  id: string,
  overrides: Partial<AdminNewsletterCandidate> = {},
): AdminNewsletterCandidate {
  return {
    id,
    sourceName: 'TLDR Dev',
    title: `Story ${id}`,
    url: `https://example.com/${id}`,
    summary: `Summary of ${id}.`,
    section: 'Articles & Tutorials',
    label: '5 minute read',
    issueSubject: 'Building good agent loops',
    receivedAt: '2026-10-06T11:18:26Z',
    relevance: 0.42,
    nearestFavourite: 'Embabel 1.0 Is Here',
    status: 'PENDING',
    reason: 'Relevance 0.42 is below the 0.50 threshold',
    articleId: null,
    ...overrides,
  }
}

function page(items: AdminNewsletterCandidate[]): PageResponse<AdminNewsletterCandidate> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 }
}

const SUMMARY: NewsletterReviewSummary = {
  counts: { PENDING: 2, ACCEPTED: 5, PROMOTED: 1, DISMISSED: 0 },
  relevanceThreshold: 0.5,
  maxAcceptedPerRun: 15,
}

function source(name: string, scrapeStrategy: string): AdminContentSource {
  return {
    id: name,
    name,
    baseUrl: 'https://tldr.tech',
    feedUrl: 'dan@tldrnewsletter.com',
    sitemapUrl: null,
    sourceType: 'NEWS',
    scrapeStrategy,
    active: true,
    lastFetchedAt: null,
    lastError: null,
  }
}

describe('NewsletterReviewAdmin', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuth.mockReturnValue({
      getAccessToken: mockGetAccessToken,
    } as unknown as ReturnType<typeof useAuth>)
    mockFetchSummary.mockResolvedValue(SUMMARY)
    mockFetchSources.mockResolvedValue([
      source('TLDR Dev', 'EMAIL_NEWSLETTER'),
      source('TLDR', 'EMAIL_NEWSLETTER'),
      source('Spring Blog', 'RSS'),
    ])
  })

  it('lists the waiting stories best first with their score and closest heart', async () => {
    mockFetchCandidates.mockResolvedValue(page([candidate('a'), candidate('b')]))

    render(<NewsletterReviewAdmin />)

    expect(await screen.findByText('Story a')).toBeInTheDocument()
    expect(screen.getAllByText('0.42')).toHaveLength(2)
    expect(screen.getAllByText('Embabel 1.0 Is Here')).toHaveLength(2)
    expect(screen.getByRole('tab', { name: 'Waiting for review (2)' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByText(/scoring 0.50 or more are saved automatically/)).toBeInTheDocument()
    expect(mockFetchCandidates).toHaveBeenCalledWith(mockGetAccessToken, {
      status: 'PENDING',
      source: '',
      sort: 'relevance',
      page: 0,
      size: 20,
    })
    expect(screen.getByRole('link', { name: /Story a/ })).toHaveAttribute(
      'href',
      'https://example.com/a',
    )
  })

  it('offers only the email newsletter sources as editions', async () => {
    mockFetchCandidates.mockResolvedValue(page([candidate('a')]))

    render(<NewsletterReviewAdmin />)

    const edition = await screen.findByRole('combobox', { name: 'Edition' })
    await waitFor(() => expect(edition.querySelectorAll('option')).toHaveLength(3))
    expect(screen.queryByRole('option', { name: 'Spring Blog' })).not.toBeInTheDocument()

    fireEvent.change(edition, { target: { value: 'TLDR Dev' } })

    await waitFor(() =>
      expect(mockFetchCandidates).toHaveBeenLastCalledWith(
        mockGetAccessToken,
        expect.objectContaining({ source: 'TLDR Dev', page: 0 }),
      ),
    )
  })

  it('promotes a story and reloads the queue', async () => {
    mockFetchCandidates.mockResolvedValue(page([candidate('a'), candidate('b')]))
    mockPromote.mockResolvedValue(candidate('a', { status: 'PROMOTED' }))

    render(<NewsletterReviewAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Promote Story a' }))

    await waitFor(() => expect(mockPromote).toHaveBeenCalledWith(mockGetAccessToken, 'a'))
    await waitFor(() => expect(mockFetchCandidates).toHaveBeenCalledTimes(2))
  })

  it('dismisses a story', async () => {
    mockFetchCandidates.mockResolvedValue(page([candidate('a'), candidate('b')]))
    mockDismiss.mockResolvedValue(candidate('b', { status: 'DISMISSED' }))

    render(<NewsletterReviewAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss Story b' }))

    await waitFor(() => expect(mockDismiss).toHaveBeenCalledWith(mockGetAccessToken, 'b'))
  })

  it('shows the server message when a decision is refused', async () => {
    mockFetchCandidates.mockResolvedValue(page([candidate('a')]))
    mockPromote.mockRejectedValue(new Error('This story is already on the site'))

    render(<NewsletterReviewAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Promote Story a' }))

    expect(await screen.findByText('This story is already on the site')).toBeInTheDocument()
  })

  it('switching tab asks for that status and offers no dismiss on saved stories', async () => {
    mockFetchCandidates.mockResolvedValue(page([candidate('a')]))

    render(<NewsletterReviewAdmin />)
    await screen.findByText('Story a')
    mockFetchCandidates.mockResolvedValue(
      page([candidate('c', { status: 'ACCEPTED', relevance: 0.61 })]),
    )

    fireEvent.click(screen.getByRole('tab', { name: 'Saved automatically (5)' }))

    expect(await screen.findByText('Story c')).toBeInTheDocument()
    expect(mockFetchCandidates).toHaveBeenLastCalledWith(
      mockGetAccessToken,
      expect.objectContaining({ status: 'ACCEPTED' }),
    )
    expect(screen.queryByRole('button', { name: /Dismiss/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Promote/ })).not.toBeInTheDocument()
  })
})
