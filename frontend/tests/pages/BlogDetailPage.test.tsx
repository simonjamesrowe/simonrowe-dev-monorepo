import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogDetailPage } from '../../src/pages/BlogDetailPage'
import type { BlogDetail } from '../../src/types/blog'

vi.mock('../../src/services/blogApi', () => ({
  fetchBlogById: vi.fn(),
}))

import { fetchBlogById } from '../../src/services/blogApi'

const blog: BlogDetail = {
  id: 'b-1',
  title: 'Spring Boot Tips',
  shortDescription: 'About Spring',
  content: '# Spring Boot\n\nContent here.',
  featuredImageUrl: '/images/blogs/post.jpg',
  createdDate: '2024-06-01T10:00:00Z',
  tags: [{ name: 'Spring' }],
  skills: [],
}

describe('BlogDetailPage', () => {
  beforeEach(() => {
    vi.mocked(fetchBlogById).mockReset()
  })

  it('renders loading state initially', () => {
    vi.mocked(fetchBlogById).mockImplementation(() => new Promise(() => {}))

    render(
      <MemoryRouter initialEntries={['/blogs/b-1']}>
        <Routes>
          <Route element={<BlogDetailPage />} path="/blogs/:id" />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
  })

  it('renders blog detail when data loads', async () => {
    vi.mocked(fetchBlogById).mockResolvedValue(blog)

    render(
      <MemoryRouter initialEntries={['/blogs/b-1']}>
        <Routes>
          <Route element={<BlogDetailPage />} path="/blogs/:id" />
        </Routes>
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: 'Spring Boot Tips' })).toBeInTheDocument()
    })
  })

  it('renders error state when blog not found', async () => {
    vi.mocked(fetchBlogById).mockRejectedValue(new Error('Blog post not found'))

    render(
      <MemoryRouter initialEntries={['/blogs/nonexistent']}>
        <Routes>
          <Route element={<BlogDetailPage />} path="/blogs/:id" />
        </Routes>
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Blog post not found')
    })
  })
})
