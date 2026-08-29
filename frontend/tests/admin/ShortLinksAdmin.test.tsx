import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ShortLinksAdmin } from '../../src/pages/admin/ShortLinksAdmin'

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminShortLinks: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import { fetchAdminShortLinks, type AdminShortLink } from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetch = vi.mocked(fetchAdminShortLinks)
const mockUseAuth = vi.mocked(useAuth)
const getAccessToken = vi.fn().mockResolvedValue('test-token')

function link(overrides: Partial<AdminShortLink> = {}): AdminShortLink {
  return {
    slug: 'exactly-once',
    shortUrl: 'https://simonrowe.dev/s/exactly-once',
    contentType: 'BLOG',
    contentId: 'blog-1',
    title: 'Exactly-once semantics',
    clickCount: 0,
    lastClickedAt: null,
    createdAt: '2026-08-01T00:00:00Z',
    ...overrides,
  }
}

/** Row order as rendered, by the slug cell. */
function slugOrder(): string[] {
  return screen.getAllByRole('row')
    .slice(1)
    .map(row => row.querySelector('td')?.textContent?.trim() ?? '')
}

describe('ShortLinksAdmin', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    mockUseAuth.mockReturnValue({ getAccessToken } as any)
  })

  it('shows a loading state before the links arrive', () => {
    mockFetch.mockReturnValue(new Promise(() => {}))

    render(<ShortLinksAdmin />)

    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('renders a row per link with its address, type and statistics', async () => {
    mockFetch.mockResolvedValue([
      link({ clickCount: 3, lastClickedAt: '2026-08-28T09:00:00Z' }),
    ])

    render(<ShortLinksAdmin />)

    expect(await screen.findByText('/s/exactly-once')).toBeInTheDocument()
    expect(screen.getByText('Exactly-once semantics')).toBeInTheDocument()
    expect(screen.getByText('Blog')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    // The address is offered as a working link, not just as text.
    expect(screen.getByRole('link', { name: /exactly-once/ }))
      .toHaveAttribute('href', 'https://simonrowe.dev/s/exactly-once')
  })

  it('labels each content type in the reader’s language, not the enum’s', async () => {
    mockFetch.mockResolvedValue([
      link({ slug: 'a', contentType: 'BLOG' }),
      link({ slug: 'b', contentType: 'ARTICLE' }),
      link({ slug: 'c', contentType: 'EVENT' }),
    ])

    render(<ShortLinksAdmin />)

    await waitFor(() => expect(screen.getByText('Blog')).toBeInTheDocument())
    expect(screen.getByText('News')).toBeInTheDocument()
    expect(screen.getByText('Event')).toBeInTheDocument()
  })

  it('marks a link whose content has been deleted rather than hiding the row', async () => {
    // Slugs are never reclaimed, so an orphan is exactly the row someone may need to
    // know about — dropping it would hide the thing worth seeing.
    mockFetch.mockResolvedValue([link({ title: null })])

    render(<ShortLinksAdmin />)

    expect(await screen.findByText('Deleted content')).toBeInTheDocument()
    expect(screen.getByText('/s/exactly-once')).toBeInTheDocument()
  })

  it('shows an em dash for a link nobody has opened', async () => {
    mockFetch.mockResolvedValue([link({ clickCount: 0, lastClickedAt: null })])

    render(<ShortLinksAdmin />)

    expect(await screen.findByText('—')).toBeInTheDocument()
  })

  it('sorts by clicks, most opened first, before anything is clicked', async () => {
    mockFetch.mockResolvedValue([
      link({ slug: 'quiet', contentId: '1', clickCount: 1 }),
      link({ slug: 'busy', contentId: '2', clickCount: 40 }),
      link({ slug: 'middling', contentId: '3', clickCount: 7 }),
    ])

    render(<ShortLinksAdmin />)

    await waitFor(() => expect(screen.getByText('/s/busy')).toBeInTheDocument())
    expect(slugOrder()).toEqual(['/s/busy', '/s/middling', '/s/quiet'])
  })

  it('flips direction when the active column is clicked again', async () => {
    mockFetch.mockResolvedValue([
      link({ slug: 'quiet', contentId: '1', clickCount: 1 }),
      link({ slug: 'busy', contentId: '2', clickCount: 40 }),
    ])

    render(<ShortLinksAdmin />)
    await waitFor(() => expect(screen.getByText('/s/busy')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Clicks/ }))

    expect(slugOrder()).toEqual(['/s/quiet', '/s/busy'])
  })

  it('switches to another column descending rather than keeping the old direction', async () => {
    mockFetch.mockResolvedValue([
      link({ slug: 'aaa', contentId: '1', title: 'Aaa' }),
      link({ slug: 'zzz', contentId: '2', title: 'Zzz' }),
    ])

    render(<ShortLinksAdmin />)
    await waitFor(() => expect(screen.getByText('/s/aaa')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Link/ }))

    expect(slugOrder()).toEqual(['/s/zzz', '/s/aaa'])
  })

  it('sorts never-opened links below opened ones in both directions', async () => {
    mockFetch.mockResolvedValue([
      link({ slug: 'never', contentId: '1', lastClickedAt: null }),
      link({ slug: 'recent', contentId: '2', lastClickedAt: '2026-08-28T09:00:00Z' }),
    ])

    render(<ShortLinksAdmin />)
    await waitFor(() => expect(screen.getByText('/s/never')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Last opened/ }))
    expect(slugOrder()).toEqual(['/s/recent', '/s/never'])

    fireEvent.click(screen.getByRole('button', { name: /Last opened/ }))
    expect(slugOrder()[0]).toBe('/s/never')
  })

  it('says so plainly when nothing has been minted', async () => {
    mockFetch.mockResolvedValue([])

    render(<ShortLinksAdmin />)

    expect(await screen.findByText('No share links yet.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('surfaces a load failure instead of an empty table', async () => {
    mockFetch.mockRejectedValue(new Error('Network is down'))

    render(<ShortLinksAdmin />)

    expect(await screen.findByText('Network is down')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('falls back to a generic message when the failure is not an Error', async () => {
    mockFetch.mockRejectedValue('something odd')

    render(<ShortLinksAdmin />)

    expect(await screen.findByText('Failed to load short links')).toBeInTheDocument()
  })
})
