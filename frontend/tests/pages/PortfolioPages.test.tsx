import { fireEvent, render, screen, within } from '@testing-library/react'
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
import type { PortfolioProject } from '../../src/types/portfolio'

function renderProjectPage(path: string) {
  return render(
    <MemoryRouter initialEntries={[`/portfolio/${path}`]}>
      <Routes><Route element={<PortfolioProjectPage />} path="/portfolio/:slug/:pageSlug?" /></Routes>
    </MemoryRouter>,
  )
}

const TERM_TIME: PortfolioProject = {
  slug: 'term-time', name: 'Term Time', tagline: 'For parents', status: 'BETA', accentHue: 152,
  displayOrder: 1, liveUrl: 'https://term-time.simonrowe.dev',
  image: { url: '/media/portfolio/term-time/hero.webp' } as PortfolioProject['image'],
  headline: 'School life,\none question away.',
  summary: 'Answers about school.',
  statement: { label: 'Why I built it', text: 'Schools send a lot.', points: [{ title: 'The inbox', text: 'Newsletters.' }] },
  exampleQuestions: ['When is half term?'],
  highlights: [{ title: 'Term dates', text: 'From the calendar.', imageUrl: '/uploads/dates.webp', imageAlt: 'Dates' }],
  demo: {
    title: 'A walkthrough', videoUrl: '/media/demo.mp4', captionsUrl: '/media/demo.vtt',
    chapters: [{ startSeconds: 0, label: 'Asking' }, { startSeconds: 65, label: 'Admin' }],
  },
  pages: [
    { slug: 'how-it-works', title: 'How it works', navHint: 'sources', summary: 'Four sources.', body: '## Four sources\n\n| a | b |\n| - | - |\n| 1 | 2 |' },
    { slug: 'architecture', title: 'Architecture', body: '```java\nclass A {}\n```' },
  ],
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

  it('shows the overview sections the CMS has content for', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue(TERM_TIME)
    renderProjectPage('term-time')

    const heading = await screen.findByRole('heading', { level: 1 })
    expect(heading).toHaveTextContent('School life,one question away.')
    expect(heading.querySelector('em')).toHaveTextContent('one question away.')
    expect(screen.getByText('Schools send a lot.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'When is half term?' })).toHaveAttribute(
      'href', 'https://term-time.simonrowe.dev/?q=When+is+half+term%3F')
    // A media-library upload loads from the API; a bundled /media asset from this origin.
    expect(screen.getByRole('img', { name: 'Dates' }).getAttribute('src')).toMatch(/\/uploads\/dates\.webp$/)
    expect(document.querySelector('.project-hero__browser img')).toHaveAttribute('src', '/media/portfolio/term-time/hero.webp')
    const tabs = within(screen.getByRole('navigation', { name: 'Term Time pages' }))
    expect(tabs.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
    expect(tabs.getByRole('link', { name: /How it works/ })).toHaveAttribute('href', '/portfolio/term-time/how-it-works')
    expect(screen.getByRole('link', { name: /Next · How it works/ })).toHaveAttribute('href', '/portfolio/term-time/how-it-works')
    expect(document.querySelector('video track')).toHaveAttribute('src', '/media/demo.vtt')
  })

  it('jumps the demo to a chapter', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue(TERM_TIME)
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
    renderProjectPage('term-time')

    fireEvent.click(await screen.findByRole('button', { name: /1:05\s*Admin/ }))

    expect(document.querySelector('video')!.currentTime).toBe(65)
    expect(play).toHaveBeenCalled()
    play.mockRestore()
  })

  it('renders a sub-page from its markdown, with the tabs', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue(TERM_TIME)
    renderProjectPage('term-time/how-it-works')

    expect(await screen.findByRole('heading', { level: 1, name: 'How it works' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Four sources' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'Term Time pages' }))
      .getByRole('link', { name: /How it works/ })).toHaveAttribute('aria-current', 'page')
    expect(document.title).toContain('How it works')
  })

  it('is a not-found page for a sub-page the project does not have', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue(TERM_TIME)
    renderProjectPage('term-time/nope')
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })

  it('has no tabs for a project without sub-pages', async () => {
    vi.mocked(fetchPortfolioProject).mockResolvedValue({
      slug: 'plain', name: 'Plain', tagline: 'Just a description', status: 'LIVE', accentHue: 1, displayOrder: 0,
    })
    renderProjectPage('plain')
    expect(await screen.findByRole('heading', { level: 1, name: 'Plain' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.getByText('Just a description')).toBeInTheDocument()
  })
})
