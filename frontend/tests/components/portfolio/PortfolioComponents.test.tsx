import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { PortfolioCarousel } from '../../../src/components/home/PortfolioCarousel'
import { ProjectCard } from '../../../src/components/portfolio/ProjectCard'
import type { PortfolioProject } from '../../../src/types/portfolio'

const soon: PortfolioProject = {
  slug: 'clinicians-veil', name: "Clinician's Veil", tagline: 'Details soon.', status: 'COMING_SOON',
  accentHue: 266, displayOrder: 3,
}
const live: PortfolioProject = {
  slug: 'term-time', name: 'Term Time', tagline: 'For parents', status: 'LIVE', accentHue: 152,
  displayOrder: 0, image: { url: '/uploads/term-time.png' }, liveUrl: 'https://term-time.simonrowe.dev',
}

const renderIn = (node: React.ReactNode) => render(<MemoryRouter>{node}</MemoryRouter>)

describe('ProjectCard', () => {
  it('draws a Coming soon project as an unlinked silhouette', () => {
    const { container } = renderIn(<ProjectCard project={soon} />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(container.querySelector('.project-silhouette')).toBeInTheDocument()
    expect(screen.getByText('Coming soon')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: "Clinician's Veil" })).toBeInTheDocument()
  })

  it('links a launched project to its page and shows its image', () => {
    const { container } = renderIn(<ProjectCard project={live} />)
    expect(screen.getByRole('link')).toHaveAttribute('href', '/portfolio/term-time')
    expect(container.querySelector('.project-card__image img')?.getAttribute('src')).toMatch(/term-time\.png$/)
    expect(screen.getByText('Live')).toBeInTheDocument()
  })

  it('falls back to a silhouette for a launched project with no image', () => {
    const { container } = renderIn(<ProjectCard project={{ ...live, image: undefined }} />)
    expect(container.querySelector('.project-silhouette')).toBeInTheDocument()
    expect(screen.getByRole('link')).toHaveAttribute('href', '/portfolio/term-time')
  })
})

describe('PortfolioCarousel', () => {
  it('numbers the projects in order and links to All projects', () => {
    renderIn(<PortfolioCarousel projects={[live, soon]} />)
    expect(screen.getByRole('heading', { name: /What I.m building/ })).toBeInTheDocument()
    expect(screen.getByText('01')).toBeInTheDocument()
    expect(screen.getByText('02')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /All projects/ })).toHaveAttribute('href', '/portfolio')
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  it('renders nothing when no project is published', () => {
    const { container } = renderIn(<PortfolioCarousel projects={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
