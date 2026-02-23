import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogSearch } from '../../../src/components/blog/BlogSearch'
import type { BlogSearchResult } from '../../../src/types/blog'

vi.mock('../../../src/services/blogApi', () => ({
  searchBlogs: vi.fn(),
}))

import { searchBlogs } from '../../../src/services/blogApi'

const searchResults: BlogSearchResult[] = [
  { id: 'b-1', title: 'Spring Boot Tips', createdDate: '2024-01-01T00:00:00Z' },
  { id: 'b-2', title: 'Kubernetes Deep Dive', createdDate: '2024-02-01T00:00:00Z' },
]

describe('BlogSearch', () => {
  beforeEach(() => {
    vi.mocked(searchBlogs).mockReset()
  })

  it('renders search input', () => {
    render(
      <MemoryRouter>
        <BlogSearch />
      </MemoryRouter>,
    )

    expect(screen.getByRole('combobox', { name: 'Search blog posts' })).toBeInTheDocument()
  })

  it('shows no results message when search returns empty', async () => {
    vi.mocked(searchBlogs).mockResolvedValue([])

    render(
      <MemoryRouter>
        <BlogSearch />
      </MemoryRouter>,
    )

    const input = screen.getByRole('combobox')
    await userEvent.type(input, 'xyz')

    await waitFor(
      () => {
        expect(screen.getByText('No results found')).toBeInTheDocument()
      },
      { timeout: 500 },
    )
  })

  it('shows search results when query matches', async () => {
    vi.mocked(searchBlogs).mockResolvedValue(searchResults)

    render(
      <MemoryRouter>
        <BlogSearch />
      </MemoryRouter>,
    )

    const input = screen.getByRole('combobox')
    await userEvent.type(input, 'Spring')

    await waitFor(
      () => {
        expect(screen.getByText('Spring Boot Tips')).toBeInTheDocument()
        expect(screen.getByText('Kubernetes Deep Dive')).toBeInTheDocument()
      },
      { timeout: 500 },
    )
  })

  it('does not search when query is shorter than 2 characters', async () => {
    render(
      <MemoryRouter>
        <BlogSearch />
      </MemoryRouter>,
    )

    const input = screen.getByRole('combobox')
    await userEvent.type(input, 'S')

    await waitFor(
      () => {
        expect(searchBlogs).not.toHaveBeenCalled()
      },
      { timeout: 500 },
    )
  })
})
