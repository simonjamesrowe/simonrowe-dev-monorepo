import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SiteSearch } from '../../../src/components/search/SiteSearch'
import type { GroupedSearchResponse } from '../../../src/services/searchApi'

vi.mock('../../../src/services/searchApi', () => ({
  siteSearch: vi.fn(),
}))

import { siteSearch } from '../../../src/services/searchApi'

const mockResults: GroupedSearchResponse = {
  blogs: [{ name: 'Spring Blog', image: '/img/spring.jpg', url: '/blogs/spring' }],
  jobs: [{ name: 'Senior Dev', image: '/img/dev.png', url: '/employment' }],
  skills: [],
}

describe('SiteSearch', () => {
  beforeEach(() => {
    vi.mocked(siteSearch).mockReset()
  })

  it('renders search input', () => {
    render(
      <MemoryRouter>
        <SiteSearch />
      </MemoryRouter>,
    )

    expect(screen.getByLabelText('Search across all content')).toBeInTheDocument()
  })

  it('shows dropdown with results after typing', async () => {
    vi.mocked(siteSearch).mockResolvedValue(mockResults)

    render(
      <MemoryRouter>
        <SiteSearch />
      </MemoryRouter>,
    )

    const input = screen.getByLabelText('Search across all content')
    await userEvent.type(input, 'spring')

    await waitFor(
      () => {
        expect(screen.getByText('Spring Blog')).toBeInTheDocument()
        expect(screen.getByText('Senior Dev')).toBeInTheDocument()
      },
      { timeout: 500 },
    )
  })

  it('does not search when query is shorter than 2 characters', async () => {
    render(
      <MemoryRouter>
        <SiteSearch />
      </MemoryRouter>,
    )

    const input = screen.getByLabelText('Search across all content')
    await userEvent.type(input, 'x')

    await waitFor(
      () => {
        expect(siteSearch).not.toHaveBeenCalled()
      },
      { timeout: 500 },
    )
  })

  it('closes dropdown on Escape key', async () => {
    vi.mocked(siteSearch).mockResolvedValue(mockResults)

    render(
      <MemoryRouter>
        <SiteSearch />
      </MemoryRouter>,
    )

    const input = screen.getByLabelText('Search across all content')
    await userEvent.type(input, 'spring')

    await waitFor(
      () => {
        expect(screen.getByText('Spring Blog')).toBeInTheDocument()
      },
      { timeout: 500 },
    )

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByText('Spring Blog')).not.toBeInTheDocument()
  })
})
