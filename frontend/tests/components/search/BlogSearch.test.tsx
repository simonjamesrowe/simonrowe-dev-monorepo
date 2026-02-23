import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogSearch } from '../../../src/components/search/BlogSearch'
import type { BlogSearchResult } from '../../../src/services/searchApi'

vi.mock('../../../src/services/searchApi', () => ({
  blogSearch: vi.fn(),
}))

import { blogSearch } from '../../../src/services/searchApi'

const searchResults: BlogSearchResult[] = [
  {
    title: 'Spring Boot Tips',
    shortDescription: 'A guide to Spring',
    image: '/img/spring.jpg',
    publishedDate: '2025-11-15T00:00:00Z',
    url: '/blogs/spring-boot',
  },
  {
    title: 'Kubernetes Guide',
    shortDescription: 'K8s deep dive',
    image: '/img/k8s.jpg',
    publishedDate: '2025-12-01T00:00:00Z',
    url: '/blogs/kubernetes',
  },
]

describe('BlogSearch (search package)', () => {
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
        expect(screen.getByText('Kubernetes Guide')).toBeInTheDocument()
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

  it('supports keyboard navigation with ArrowDown and Enter', async () => {
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
      },
      { timeout: 500 },
    )

    await userEvent.keyboard('{ArrowDown}')

    const options = screen.getAllByRole('option')
    expect(options[0]).toHaveAttribute('aria-selected', 'true')
  })

  it('closes dropdown on Escape', async () => {
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
      },
      { timeout: 500 },
    )

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByText('Spring Boot Tips')).not.toBeInTheDocument()
  })
})
