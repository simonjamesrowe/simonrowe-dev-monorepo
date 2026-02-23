import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { SearchDropdown } from '../../../src/components/search/SearchDropdown'
import type { GroupedSearchResponse } from '../../../src/services/searchApi'

const onResultClick = vi.fn()

const results: GroupedSearchResponse = {
  blogs: [{ name: 'Blog Post', image: '/img/blog.jpg', url: '/blogs/post' }],
  jobs: [{ name: 'Developer', image: '/img/dev.png', url: '/employment' }],
  skills: [{ name: 'Java', image: '/img/java.png', url: '/skills' }],
}

describe('SearchDropdown', () => {
  it('shows loading state', () => {
    render(
      <MemoryRouter>
        <SearchDropdown hasResults={false} loading={true} onResultClick={onResultClick} results={null} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Searching...')).toBeInTheDocument()
  })

  it('shows no results message', () => {
    render(
      <MemoryRouter>
        <SearchDropdown hasResults={false} loading={false} onResultClick={onResultClick} results={null} />
      </MemoryRouter>,
    )

    expect(screen.getByText('No results found')).toBeInTheDocument()
  })

  it('renders grouped results', () => {
    render(
      <MemoryRouter>
        <SearchDropdown hasResults={true} loading={false} onResultClick={onResultClick} results={results} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Blogs')).toBeInTheDocument()
    expect(screen.getByText('Blog Post')).toBeInTheDocument()
    expect(screen.getByText('Jobs')).toBeInTheDocument()
    expect(screen.getByText('Developer')).toBeInTheDocument()
    expect(screen.getByText('Skills')).toBeInTheDocument()
    expect(screen.getByText('Java')).toBeInTheDocument()
  })

  it('omits empty groups', () => {
    const partialResults: GroupedSearchResponse = {
      blogs: [{ name: 'Blog Post', image: null, url: '/blogs/post' }],
      jobs: [],
      skills: [],
    }

    render(
      <MemoryRouter>
        <SearchDropdown hasResults={true} loading={false} onResultClick={onResultClick} results={partialResults} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Blogs')).toBeInTheDocument()
    expect(screen.queryByText('Jobs')).not.toBeInTheDocument()
    expect(screen.queryByText('Skills')).not.toBeInTheDocument()
  })
})
