import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SiteSearch } from '../../../src/components/search/SiteSearch'
import { DrawerProvider } from '../../../src/hooks/useDrawer'
import { TourProvider } from '../../../src/components/tour/TourProvider'
import type { GroupedSearchResponse } from '../../../src/services/searchApi'

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(() => ({
    matches: true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })),
})

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
        <TourProvider><DrawerProvider><SiteSearch />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    expect(screen.getByLabelText('Search or ask a question')).toBeInTheDocument()
  })

  it('shows dropdown with results after typing', async () => {
    vi.mocked(siteSearch).mockResolvedValue(mockResults)

    render(
      <MemoryRouter>
        <TourProvider><DrawerProvider><SiteSearch />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    const input = screen.getByLabelText('Search or ask a question')
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
        <TourProvider><DrawerProvider><SiteSearch />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    const input = screen.getByLabelText('Search or ask a question')
    await userEvent.type(input, 'x')

    await waitFor(
      () => {
        expect(siteSearch).not.toHaveBeenCalled()
      },
      { timeout: 500 },
    )
  })

  it('calls onChatStart with query when Enter is pressed with non-empty query', async () => {
    const onChatStart = vi.fn()

    render(
      <MemoryRouter>
        <TourProvider><DrawerProvider><SiteSearch onChatStart={onChatStart} />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    const input = screen.getByLabelText('Search or ask a question')
    await userEvent.type(input, 'spring')
    await userEvent.keyboard('{Enter}')

    expect(onChatStart).toHaveBeenCalledWith('spring')
  })

  it('does not call onChatStart when Enter is pressed with empty query', async () => {
    const onChatStart = vi.fn()

    render(
      <MemoryRouter>
        <TourProvider><DrawerProvider><SiteSearch onChatStart={onChatStart} />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    const input = screen.getByLabelText('Search or ask a question')
    await userEvent.click(input)
    await userEvent.keyboard('{Enter}')

    expect(onChatStart).not.toHaveBeenCalled()
  })

  it('does not call onChatStart when no callback provided', async () => {
    render(
      <MemoryRouter>
        <TourProvider><DrawerProvider><SiteSearch />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    const input = screen.getByLabelText('Search or ask a question')
    await userEvent.type(input, 'spring')
    await userEvent.keyboard('{Enter}')

    // Enter with no callback must be survivable. Assert the component is still mounted
    // and holding its query rather than relying on "no error was thrown" — a test body
    // with no assertion also passes when it never runs at all (Sonar typescript:S2699).
    expect(input).toBeInTheDocument()
    expect(input).toHaveValue('spring')
  })

  it('closes dropdown on Escape key', async () => {
    vi.mocked(siteSearch).mockResolvedValue(mockResults)

    render(
      <MemoryRouter>
        <TourProvider><DrawerProvider><SiteSearch />
      </DrawerProvider></TourProvider></MemoryRouter>,
    )

    const input = screen.getByLabelText('Search or ask a question')
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
