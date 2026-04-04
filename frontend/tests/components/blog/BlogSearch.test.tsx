import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogSearch } from '../../../src/components/blog/BlogSearch'
import type { BlogSearchResult } from '../../../src/services/searchApi'

vi.mock('../../../src/services/searchApi', () => ({
  blogSearch: vi.fn(),
}))

import { blogSearch } from '../../../src/services/searchApi'

const searchResults: BlogSearchResult[] = [
  { title: 'Spring Boot Tips', shortDescription: null, image: null, publishedDate: '2024-01-01T00:00:00Z', url: '/blogs/b-1' },
  { title: 'Kubernetes Deep Dive', shortDescription: null, image: null, publishedDate: '2024-02-01T00:00:00Z', url: '/blogs/b-2' },
]

describe('BlogSearch', () => {
  beforeEach(() => {
    vi.mocked(blogSearch).mockReset()
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
    vi.mocked(blogSearch).mockResolvedValue([])

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
    vi.mocked(blogSearch).mockResolvedValue(searchResults)

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
        expect(blogSearch).not.toHaveBeenCalled()
      },
      { timeout: 500 },
    )
  })
})
