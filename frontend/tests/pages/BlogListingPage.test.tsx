import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogListingPage } from '../../src/pages/BlogListingPage'
import type { BlogSummary } from '../../src/types/blog'

vi.mock('../../src/services/blogApi', () => ({
  fetchBlogs: vi.fn(),
  searchBlogs: vi.fn().mockResolvedValue([]),
}))

import { fetchBlogs } from '../../src/services/blogApi'

const blogs: BlogSummary[] = [
  {
    id: 'b-1',
    title: 'Spring Boot Tips',
    shortDescription: 'About Spring',
    createdDate: '2024-06-01T10:00:00Z',
    tags: [{ name: 'Spring' }],
  },
]

describe('BlogListingPage', () => {
  beforeEach(() => {
    vi.mocked(fetchBlogs).mockReset()
  })

  it('renders loading state initially', () => {
    vi.mocked(fetchBlogs).mockImplementation(() => new Promise(() => {}))

    render(
      <MemoryRouter>
        <BlogListingPage />
      </MemoryRouter>,
    )

    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
  })

  it('renders blog listing when data loads', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(blogs)

    render(
      <MemoryRouter>
        <BlogListingPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('Spring Boot Tips')).toBeInTheDocument()
    })
  })

  it('renders error state when fetch fails', async () => {
    vi.mocked(fetchBlogs).mockRejectedValue(new Error('Network error'))

    render(
      <MemoryRouter>
        <BlogListingPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Network error')
    })
  })
})
