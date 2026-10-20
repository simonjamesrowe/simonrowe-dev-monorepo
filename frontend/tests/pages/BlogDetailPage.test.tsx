import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogDetailPage } from '../../src/pages/BlogDetailPage'
import type { BlogDetail } from '../../src/types/blog'

vi.mock('../../src/services/blogApi', () => ({
  fetchBlogById: vi.fn(),
}))

// Snippets are the admin's alone: the role is switched per test, and the API is always
// mocked so an unmocked fetch can never reach the production API the test env points at.
let isAdmin = false

vi.mock('../../src/auth/useAdminRole', () => ({
  useAdminRole: () => isAdmin,
}))

vi.mock('../../src/services/snippetsApi', () => ({
  fetchSnippetsFor: vi.fn(),
  fetchSnippetTags: vi.fn(),
  createSnippet: vi.fn(),
  updateSnippet: vi.fn(),
  deleteSnippet: vi.fn(),
}))

import { fetchBlogById } from '../../src/services/blogApi'
import { fetchSnippetsFor, fetchSnippetTags } from '../../src/services/snippetsApi'

const blog: BlogDetail = {
  id: 'b-1',
  title: 'Spring Boot Tips',
  shortDescription: 'About Spring',
  content: '# Spring Boot\n\nContent here.',
  featuredImageUrl: '/images/blogs/post.jpg',
  createdDate: '2024-06-01T10:00:00Z',
  tags: [{ name: 'Spring' }],
  skills: [],
  contentType: 'ENGINEERING',
}

function renderDetail(path = '/blogs/b-1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<BlogDetailPage />} path="/blogs/:id" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('BlogDetailPage', () => {
  beforeEach(() => {
    isAdmin = false
    vi.mocked(fetchBlogById).mockReset()
    vi.mocked(fetchSnippetsFor).mockReset().mockResolvedValue([])
    vi.mocked(fetchSnippetTags).mockReset().mockResolvedValue([])
  })

  it('offers the admin snippets of the post, under it', async () => {
    isAdmin = true
    vi.mocked(fetchBlogById).mockResolvedValue(blog)

    renderDetail()

    expect(await screen.findByRole('region', { name: 'Your snippets' })).toBeInTheDocument()
    expect(fetchSnippetsFor).toHaveBeenCalledWith(expect.any(Function), 'b-1', 'BLOG')
  })

  it('shows anyone else the post as before, without calling the snippets API', async () => {
    vi.mocked(fetchBlogById).mockResolvedValue(blog)

    renderDetail()

    await screen.findByRole('heading', { level: 1, name: 'Spring Boot Tips' })
    expect(screen.queryByRole('region', { name: 'Your snippets' })).not.toBeInTheDocument()
    expect(fetchSnippetsFor).not.toHaveBeenCalled()
    expect(fetchSnippetTags).not.toHaveBeenCalled()
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

    expect(screen.getByText('Loading blog post...')).toBeInTheDocument()
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

  it('sets the document title from the loaded post', async () => {
    vi.mocked(fetchBlogById).mockResolvedValue(blog)

    renderDetail()

    await waitFor(() => {
      expect(document.title).toBe('Spring Boot Tips · Simon Rowe')
    })
  })

  it('gives the error state a title and a retry that refetches', async () => {
    vi.mocked(fetchBlogById)
      .mockRejectedValueOnce(new Error('Unable to load blog data.'))
      .mockResolvedValueOnce(blog)

    renderDetail()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Unable to load this post')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: 'Spring Boot Tips' })).toBeInTheDocument()
    })
    expect(vi.mocked(fetchBlogById)).toHaveBeenCalledTimes(2)
  })
})
