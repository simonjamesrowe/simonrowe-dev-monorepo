import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { SearchResultGroup } from '../../../src/components/search/SearchResultGroup'
import type { SearchResult } from '../../../src/services/searchApi'

const results: SearchResult[] = [
  { name: 'Spring Boot', image: '/img/spring.png', url: '/blogs/spring-boot' },
  { name: 'Java', image: null, url: '/skills' },
]

describe('SearchResultGroup', () => {
  it('renders title and results', () => {
    render(
      <MemoryRouter>
        <SearchResultGroup onResultClick={vi.fn()} results={results} title="Blogs" />
      </MemoryRouter>,
    )

    expect(screen.getByText('Blogs')).toBeInTheDocument()
    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
    expect(screen.getByText('Java')).toBeInTheDocument()
  })

  it('calls onResultClick when a result is clicked', async () => {
    const onResultClick = vi.fn()

    render(
      <MemoryRouter>
        <SearchResultGroup onResultClick={onResultClick} results={results} title="Blogs" />
      </MemoryRouter>,
    )

    await userEvent.click(screen.getByText('Spring Boot'))

    expect(onResultClick).toHaveBeenCalledTimes(1)
  })

  it('uses placeholder image when image is null', () => {
    render(
      <MemoryRouter>
        <SearchResultGroup onResultClick={vi.fn()} results={results} title="Skills" />
      </MemoryRouter>,
    )

    const images = document.querySelectorAll<HTMLImageElement>('.search-result-group__thumbnail')
    expect(images[0].src).toContain('/img/spring.png')
    expect(images[1].src).toContain('/images/placeholder.png')
  })
})
