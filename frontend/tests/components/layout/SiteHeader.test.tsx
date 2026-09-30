import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SiteHeader } from '../../../src/components/layout/SiteHeader'
import { groupIsActive, NAV_GROUPS, visibleGroups } from '../../../src/components/layout/navModel'

const openChat = vi.fn()
const startTour = vi.fn()
const portfolioState = vi.fn()

vi.mock('../../../src/hooks/usePortfolio', () => ({ usePortfolio: () => portfolioState() }))

vi.mock('../../../src/auth/useAdminRole', () => ({ useAdminRole: () => false }))
vi.mock('../../../src/contexts/ChatContext', () => ({ useChat: () => ({ openChat }) }))
vi.mock('../../../src/contexts/ThemeContext', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: vi.fn() }),
}))
vi.mock('../../../src/hooks/useTour', () => ({ useTour: () => ({ start: startTour }) }))
// A stand-in with the same class and input as the real field, which is all the header touches.
vi.mock('../../../src/components/search/SiteSearch', () => ({
  SiteSearch: () => (
    <div className="site-search tour-search">
      <input aria-label="Search or ask a question" type="search" />
    </div>
  ),
}))

function GoTo({ to }: { to: string }) {
  const navigate = useNavigate()
  return <button onClick={() => navigate(to)} type="button">go {to}</button>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SiteHeader />
      <Routes>
        <Route element={<GoTo to="/mcp" />} path="*" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('SiteHeader', () => {
  beforeEach(() => {
    openChat.mockReset()
    startTour.mockReset()
    portfolioState.mockReturnValue({ projects: [], loading: false, error: null })
  })

  it('renders the three populated menus and no empty Portfolio menu', () => {
    renderAt('/')
    expect(screen.getByRole('button', { name: 'About' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Insights' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Under the hood' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Portfolio' })).not.toBeInTheDocument()
  })

  it('opens a panel on click, exposes its state, and closes it on Escape', async () => {
    const user = userEvent.setup()
    renderAt('/')
    const about = screen.getByRole('button', { name: 'About' })

    expect(about).toHaveAttribute('aria-expanded', 'false')
    await user.click(about)
    expect(about).toHaveAttribute('aria-expanded', 'true')
    const panel = document.getElementById(about.getAttribute('aria-controls') ?? '')
    expect(panel).not.toHaveAttribute('hidden')
    expect(within(panel!).getByRole('link', { name: /^Skills/ })).toHaveAttribute('href', '/about#skills')
    expect(within(panel!).getByRole('link', { name: /^Contact/ })).toHaveAttribute('href', '/about#contact')

    await user.keyboard('{Escape}')
    expect(about).toHaveAttribute('aria-expanded', 'false')
  })

  it('keeps only one panel open at a time', async () => {
    const user = userEvent.setup()
    renderAt('/')
    await user.click(screen.getByRole('button', { name: 'About' }))
    await user.click(screen.getByRole('button', { name: 'Insights' }))

    expect(screen.getByRole('button', { name: 'About' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: 'Insights' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('opens from the keyboard alone', async () => {
    const user = userEvent.setup()
    renderAt('/')
    const insights = screen.getByRole('button', { name: 'Insights' })
    insights.focus()
    await user.keyboard('{Enter}')
    expect(insights).toHaveAttribute('aria-expanded', 'true')
  })

  it('closes an open panel when the route changes', async () => {
    const user = userEvent.setup()
    renderAt('/')
    await user.click(screen.getByRole('button', { name: 'Under the hood' }))
    await user.click(screen.getByRole('button', { name: 'go /mcp' }))
    expect(screen.getByRole('button', { name: 'Under the hood' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('marks the group containing the current page, including pages beneath it', () => {
    renderAt('/blogs/some-post')
    expect(screen.getByRole('button', { name: 'Insights' })).toHaveClass('header-menu__trigger--active')
    expect(screen.getByRole('button', { name: 'About' })).not.toHaveClass('header-menu__trigger--active')
  })

  it('starts the tour from the About menu', async () => {
    const user = userEvent.setup()
    renderAt('/')
    await user.click(screen.getByRole('button', { name: 'About' }))
    await user.click(screen.getByRole('button', { name: 'Take a tour' }))
    expect(startTour).toHaveBeenCalledTimes(1)
  })

  it('keeps the class the tour targets on the Ask button, and opens the chat from it', async () => {
    const user = userEvent.setup()
    renderAt('/')
    const ask = screen.getByTestId('open-chat')
    expect(ask).toHaveClass('top-nav__ask-ai')
    expect(ask).toHaveAccessibleName('Ask Simon anything')
    await user.click(ask)
    expect(openChat).toHaveBeenCalledWith()
  })

  it('keeps the search a visible field, focused by / and by Ctrl+K', () => {
    renderAt('/')
    const input = screen.getByRole('searchbox', { name: /search or ask a question/i })
    expect(document.querySelector('.site-header .tour-search')).toBeInTheDocument()
    // jsdom lays nothing out, so offsetParent is always null there; stand in for a visible field.
    Object.defineProperty(input, 'offsetParent', { configurable: true, get: () => document.body })

    act(() => { fireEvent.keyDown(window, { key: '/' }) })
    expect(input).toHaveFocus()

    input.blur()
    act(() => { fireEvent.keyDown(window, { key: 'k', ctrlKey: true }) })
    expect(input).toHaveFocus()
  })

  it('does not steal a / typed into another field', () => {
    render(
      <MemoryRouter>
        <SiteHeader />
        <textarea aria-label="notes" />
      </MemoryRouter>,
    )
    const notes = screen.getByRole('textbox', { name: 'notes' })
    notes.focus()
    fireEvent.keyDown(notes, { key: '/' })
    expect(notes).toHaveFocus()
  })
})

describe('SiteHeader Portfolio menu', () => {
  beforeEach(() => {
    portfolioState.mockReset()
  })

  it('lists published projects from the CMS, marks Coming soon ones, and ends with All projects', async () => {
    portfolioState.mockReturnValue({
      loading: false,
      error: null,
      projects: [
        { slug: 'term-time', name: 'Term Time', tagline: 'For parents', status: 'LIVE', accentHue: 1, displayOrder: 0 },
        { slug: 'co-parents', name: 'Co-Parents', tagline: 'Two homes', status: 'COMING_SOON', accentHue: 2, displayOrder: 1 },
      ],
    })
    const user = userEvent.setup()
    renderAt('/')
    await user.click(screen.getByRole('button', { name: 'Portfolio' }))

    const panel = document.getElementById('header-menu-portfolio')!
    const links = within(panel).getAllByRole('link')
    expect(links.map(link => link.getAttribute('href'))).toEqual(['/portfolio/term-time', '/portfolio', '/portfolio'])
    expect(links[1]).toHaveTextContent('Soon')
    expect(links[2]).toHaveTextContent('All projects')
  })

  it('still offers All projects when the list could not be loaded', async () => {
    portfolioState.mockReturnValue({ projects: [], loading: false, error: 'down' })
    const user = userEvent.setup()
    renderAt('/')
    await user.click(screen.getByRole('button', { name: 'Portfolio' }))
    expect(within(document.getElementById('header-menu-portfolio')!).getByRole('link', { name: /All projects/ }))
      .toHaveAttribute('href', '/portfolio')
  })

  it('lights Portfolio on a project page', () => {
    portfolioState.mockReturnValue({ projects: [], loading: false, error: 'down' })
    renderAt('/portfolio/term-time')
    expect(screen.getByRole('button', { name: 'Portfolio' })).toHaveClass('header-menu__trigger--active')
  })
})

describe('navModel', () => {
  it('hides a group with no destinations', () => {
    expect(visibleGroups().map(group => group.key)).toEqual(['about', 'insights', 'under-the-hood'])
  })

  it('treats a hash as the same page and a sub-path as inside the group', () => {
    const about = NAV_GROUPS.find(group => group.key === 'about')!
    const insights = NAV_GROUPS.find(group => group.key === 'insights')!
    expect(groupIsActive(about, '/about')).toBe(true)
    expect(groupIsActive(insights, '/blogs/abc')).toBe(true)
    expect(groupIsActive(insights, '/blogsmith')).toBe(false)
  })
})
