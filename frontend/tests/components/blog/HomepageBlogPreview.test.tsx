import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HomepageBlogPreview } from '../../../src/components/blog/HomepageBlogPreview'
import type { BlogSummary } from '../../../src/types/blog'

vi.mock('../../../src/services/blogApi', () => ({
  fetchLatestBlogs: vi.fn(),
}))

import { fetchLatestBlogs } from '../../../src/services/blogApi'

const latestBlogs: BlogSummary[] = [
  {
    id: 'b-1',
    title: 'Latest Post 1',
    shortDescription: 'Description 1',
    featuredImageUrl: '/images/post1.jpg',
    createdDate: '2024-06-01T10:00:00Z',
    tags: [],
  },
  {
    id: 'b-2',
    title: 'Latest Post 2',
    shortDescription: 'Description 2',
    createdDate: '2024-05-01T10:00:00Z',
    tags: [],
  },
]

describe('HomepageBlogPreview', () => {
  beforeEach(() => {
    vi.mocked(fetchLatestBlogs).mockReset()
  })

  it('renders latest blog previews', async () => {
    vi.mocked(fetchLatestBlogs).mockResolvedValue(latestBlogs)

    render(
      <MemoryRouter>
        <HomepageBlogPreview />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('Latest Post 1')).toBeInTheDocument()
      expect(screen.getByText('Latest Post 2')).toBeInTheDocument()
    })
  })

  it('renders view all posts link', async () => {
    vi.mocked(fetchLatestBlogs).mockResolvedValue(latestBlogs)

    render(
      <MemoryRouter>
        <HomepageBlogPreview />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'View All Posts' })).toHaveAttribute('href', '/blogs')
    })
  })

  it('renders nothing when no blogs returned', async () => {
    vi.mocked(fetchLatestBlogs).mockResolvedValue([])

    const { container } = render(
      <MemoryRouter>
        <HomepageBlogPreview />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(container.firstChild).toBeNull()
    })
  })

  it('renders nothing when fetch fails', async () => {
    vi.mocked(fetchLatestBlogs).mockRejectedValue(new Error('Network error'))

    const { container } = render(
      <MemoryRouter>
        <HomepageBlogPreview />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(container.firstChild).toBeNull()
    })
  })
})
