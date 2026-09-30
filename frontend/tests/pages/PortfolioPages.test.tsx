import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/services/portfolioApi', () => ({
  fetchPortfolio: vi.fn(),
  fetchPortfolioProject: vi.fn(),
}))
vi.mock('../../src/services/analytics', () => ({ trackPageView: vi.fn() }))

import { clearPortfolioCache } from '../../src/hooks/usePortfolio'
import { PortfolioPage } from '../../src/pages/PortfolioPage'
import { PortfolioProjectPage } from '../../src/pages/PortfolioProjectPage'
import { fetchPortfolio, fetchPortfolioProject } from '../../src/services/portfolioApi'

function renderProjectPage(slug: string) {
  return render(
    <MemoryRouter initialEntries={[`/portfolio/${slug}`]}>
      <Routes><Route element={<PortfolioProjectPage />} path="/portfolio/:slug" /></Routes>
    </MemoryRouter>,
  )
}

describe('Portfolio pages', () => {
  beforeEach(() => {
    clearPortfolioCache()
    vi.mocked(fetchPortfolio).mockReset()
    vi.mocked(fetchPortfolioProject).mockReset()
  })

  it('lists every published project', async () => {
    vi.mocked(fetchPortfolio).mockResolvedValue([
      { slug: 'a', name: 'Alpha', tagline: 'First', status: 'COMING_SOON', accentHue: 1, displayOrder: 0 },
      { slug: 'b', name: 'Beta', tagline: 'Second', status: 'BETA', accentHue: 2, displayOrder: 1 },
    ])
    render(<MemoryRouter><PortfolioPage /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: 'Alpha' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Beta' })).toBeInTheDocument()
    expect(document.title).toContain('Portfolio')
  })

  it('shows a launched project with its link and description', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue({
      slug: 'term-time', name: 'Term Time', tagline: 'For parents', status: 'LIVE', accentHue: 1,
      displayOrder: 0, description: 'Built on **Spring**.', liveUrl: 'https://term-time.simonrowe.dev',
    })
    renderProjectPage('term-time')
    expect(await screen.findByRole('heading', { level: 1, name: 'Term Time' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open Term Time/ })).toHaveAttribute('href', 'https://term-time.simonrowe.dev')
    expect(screen.getByText('Spring')).toBeInTheDocument()
    expect(fetchPortfolioProject).toHaveBeenCalledWith('term-time')
  })

  it('is a plain not-found page for a slug with no page', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue(null)
    renderProjectPage('clinicians-veil')
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
