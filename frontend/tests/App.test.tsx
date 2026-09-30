import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from '../src/App'

// No network: the header's Portfolio menu and the home carousel both read this.
vi.mock('../src/services/portfolioApi', () => ({
  fetchPortfolio: vi.fn().mockResolvedValue([]),
  fetchPortfolioProject: vi.fn().mockResolvedValue(null),
}))

vi.mock('../src/pages/HomePage', () => ({
  HomePage: () => <h1>Homepage</h1>,
}))

vi.mock('../src/pages/AboutPage', () => ({
  AboutPage: () => <h1>About</h1>,
}))

vi.mock('../src/hooks/useProfile', () => ({
  useProfile: () => ({
    profile: null,
    loading: true,
    error: null,
    retry: vi.fn(),
  }),
}))

vi.mock('../src/components/tour/TourProvider', () => ({
  TourProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TourContext: { Provider: ({ children }: { children: React.ReactNode }) => children },
}))

vi.mock('../src/hooks/useTour', () => ({
  useTour: () => ({
    isActive: false,
    currentStepIndex: 0,
    steps: [],
    searchValue: '',
    autoAdvancePaused: false,
    start: vi.fn(),
    next: vi.fn(),
    prev: vi.fn(),
    exit: vi.fn(),
    setSearchValue: vi.fn(),
    pauseAutoAdvance: vi.fn(),
    resumeAutoAdvance: vi.fn(),
  }),
}))

vi.mock('../src/components/tour/TourOverlay', () => ({
  TourOverlay: () => null,
}))

vi.mock('../src/pages/BlogListingPage', () => ({
  BlogListingPage: () => <h1>Blog listing</h1>,
}))

vi.mock('../src/pages/BlogDetailPage', () => ({
  BlogDetailPage: () => <h1>Blog detail</h1>,
}))

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal('scrollTo', vi.fn())
  })

  it('routes / to HomePage', () => {
    window.history.pushState({}, '', '/')
    render(<App />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Homepage')
  })

  it('routes /about to AboutPage', async () => {
    window.history.pushState({}, '', '/about')
    render(<App />)

    // AboutPage is lazy-loaded, so it resolves via Suspense on the next tick.
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('About')
  })

  // Profile and Experience merged into /about; posted chat answers and shared links still
  // carry the old addresses, so each must land on the same thing it used to open.
  it.each([
    ['/profile', '/about'],
    ['/profile#contact', '/about#contact'],
    ['/experience', '/about#roles'],
    ['/experience?job=x', '/about?job=x#roles'],
    ['/experience?skillGroup=g', '/about?skillGroup=g#roles'],
    ['/experience#skills', '/about#skills'],
  ])('redirects %s to %s', async (from, to) => {
    window.history.pushState({}, '', from)
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('About')
    const { pathname, search, hash } = window.location
    expect(`${pathname}${search}${hash}`).toBe(to)
  })

  it('renders the site footer inside the public layout', () => {
    window.history.pushState({}, '', '/')
    render(<App />)

    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
  })

  it('redirects the legacy /blog path to the blog listing', async () => {
    window.history.pushState({}, '', '/blog')
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Blog listing')
    expect(window.location.pathname).toBe('/blogs')
  })

  it('redirects a legacy /blog/:id path to the canonical post address', async () => {
    window.history.pushState({}, '', '/blog/6612f0a1c3d4e5f60718293a')
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Blog detail')
    expect(window.location.pathname).toBe('/blogs/6612f0a1c3d4e5f60718293a')
  })

  it('renders the not-found page for an unknown path, inside the site chrome', async () => {
    window.history.pushState({}, '', '/no-such-page')
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Page not found')
    // The 404 must sit inside the normal layout, not on a bare page.
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to home/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /read the blog/i })).toBeInTheDocument()
  })
})
