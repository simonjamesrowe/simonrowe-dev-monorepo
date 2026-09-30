import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { SearchResultGroup } from '../../../src/components/search/SearchResultGroup'
import { DrawerProvider } from '../../../src/hooks/useDrawer'
import type { SearchResult } from '../../../src/services/searchApi'

const results: SearchResult[] = [
  { name: 'Spring Boot', image: '/img/spring.png', url: '/blogs/spring-boot' },
  { name: 'Java', image: null, url: '/skills' },
]

describe('SearchResultGroup', () => {
  it('renders title and results', () => {
    render(
      <MemoryRouter>
        <DrawerProvider><SearchResultGroup onResultClick={vi.fn()} results={results} title="Blogs" />
      </DrawerProvider></MemoryRouter>,
    )

    expect(screen.getByText('Blogs')).toBeInTheDocument()
    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
    expect(screen.getByText('Java')).toBeInTheDocument()
  })

  it('calls onResultClick when a result is clicked', async () => {
    const onResultClick = vi.fn()

    render(
      <MemoryRouter>
        <DrawerProvider><SearchResultGroup onResultClick={onResultClick} results={results} title="Blogs" />
      </DrawerProvider></MemoryRouter>,
    )

    await userEvent.click(screen.getByText('Spring Boot'))

    expect(onResultClick).toHaveBeenCalledTimes(1)
  })

  it('draws a neutral tile when a result has no image', () => {
    render(
      <MemoryRouter>
        <DrawerProvider><SearchResultGroup onResultClick={vi.fn()} results={results} title="Skills" />
      </DrawerProvider></MemoryRouter>,
    )

    const images = document.querySelectorAll<HTMLImageElement>('img.search-result-group__thumbnail')
    expect(images).toHaveLength(1)
    expect(images[0].src).toContain('/img/spring.png')
    expect(document.querySelectorAll('.search-result-group__thumbnail--empty')).toHaveLength(1)
  })

  it('falls back to the tile once when an image fails, rather than retrying in a loop', () => {
    render(
      <MemoryRouter>
        <DrawerProvider><SearchResultGroup onResultClick={vi.fn()} results={results} title="Skills" />
      </DrawerProvider></MemoryRouter>,
    )

    fireEvent.error(document.querySelector('img.search-result-group__thumbnail')!)

    expect(document.querySelector('img.search-result-group__thumbnail')).toBeNull()
    expect(document.querySelectorAll('.search-result-group__thumbnail--empty')).toHaveLength(2)
  })
})
