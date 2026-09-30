import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import type { PortfolioProject } from '../../types/portfolio'
import { ProjectCard } from '../portfolio/ProjectCard'

interface PortfolioCarouselProps {
  projects: PortfolioProject[]
}

const numbered = (index: number) => String(index + 1).padStart(2, '0')

/**
 * "What I'm building": published projects as a numbered, horizontally scrolling row.
 *
 * Same mechanics as `FeaturedWriting`: native scroll with snap points, so touch, trackpad and
 * keyboard work for free, and arrows on top. Not auto-rotating, for the same reason: the cards
 * carry text. Renders nothing when no project is published.
 */
export function PortfolioCarousel({ projects }: PortfolioCarouselProps) {
  const trackRef = useRef<HTMLUListElement>(null)
  const [atStart, setAtStart] = useState(true)
  const [atEnd, setAtEnd] = useState(false)

  const syncArrows = useCallback(() => {
    const track = trackRef.current
    if (!track) return
    setAtStart(track.scrollLeft <= 1)
    setAtEnd(track.scrollLeft + track.clientWidth >= track.scrollWidth - 1)
  }, [])

  useEffect(() => {
    syncArrows()
    window.addEventListener('resize', syncArrows)
    return () => window.removeEventListener('resize', syncArrows)
  }, [syncArrows, projects.length])

  const scrollByCard = (direction: 1 | -1) => {
    const track = trackRef.current
    if (!track) return
    const card = track.querySelector('li')
    const step = card ? card.getBoundingClientRect().width + 24 : track.clientWidth * 0.8
    track.scrollBy({ left: step * direction, behavior: 'smooth' })
  }

  if (projects.length === 0) {
    return null
  }

  return (
    <section aria-labelledby="portfolio-carousel-heading" className="portfolio-carousel">
      <div className="portfolio-carousel__header">
        <div>
          <p className="portfolio-carousel__eyebrow">Portfolio</p>
          <h2 className="portfolio-carousel__heading" id="portfolio-carousel-heading">
            What I&rsquo;m building
          </h2>
        </div>
        <div className="featured-writing__header-actions">
          <div className="featured-writing__controls">
            <button
              aria-label="Scroll to previous projects"
              className="featured-writing__arrow"
              disabled={atStart}
              onClick={() => scrollByCard(-1)}
              type="button"
            >
              <ChevronLeft size={18} />
            </button>
            <button
              aria-label="Scroll to more projects"
              className="featured-writing__arrow"
              disabled={atEnd}
              onClick={() => scrollByCard(1)}
              type="button"
            >
              <ChevronRight size={18} />
            </button>
          </div>
          <Link className="featured-writing__link" to="/portfolio">All projects &rarr;</Link>
        </div>
      </div>
      <ul aria-label="Portfolio projects" className="portfolio-carousel__track" onScroll={syncArrows} ref={trackRef}>
        {projects.map((project, index) => (
          <li className="portfolio-carousel__slide" key={project.slug}>
            <ProjectCard number={numbered(index)} project={project} />
          </li>
        ))}
      </ul>
    </section>
  )
}
