/**
 * PROTOTYPE — the home-page portfolio section, one layout per variant:
 * A numbered carousel (adpower's 01/02/03 cards), B bento grid, C editorial numbered list.
 */
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useRef } from 'react'
import { Link } from 'react-router-dom'

import { PORTFOLIO, PORTFOLIO_ROUTE } from './data'
import { Silhouette } from './Silhouette'
import type { VariantKey } from './variant'

const number = (index: number) => String(index + 1).padStart(2, '0')

function Heading({ withArrows, onArrow }: { withArrows?: boolean; onArrow?: (direction: 1 | -1) => void }) {
  return (
    <div className="pp-heading">
      <div>
        <p className="pp-heading__eyebrow">Portfolio</p>
        <h2 className="pp-heading__title">What I&rsquo;m building</h2>
        <p className="pp-heading__sub">Four products in the workshop. Each one runs on the same platform as this site.</p>
      </div>
      <div className="pp-heading__actions">
        {withArrows ? (
          <div className="featured-writing__controls">
            <button aria-label="Previous project" className="featured-writing__arrow" onClick={() => onArrow?.(-1)} type="button"><ChevronLeft size={18} /></button>
            <button aria-label="Next project" className="featured-writing__arrow" onClick={() => onArrow?.(1)} type="button"><ChevronRight size={18} /></button>
          </div>
        ) : null}
        <Link className="featured-writing__link" to={PORTFOLIO_ROUTE}>All projects &rarr;</Link>
      </div>
    </div>
  )
}

function Carousel() {
  const track = useRef<HTMLUListElement>(null)
  const scroll = (direction: 1 | -1) => {
    const card = track.current?.querySelector('li')
    track.current?.scrollBy({ left: ((card?.getBoundingClientRect().width ?? 320) + 24) * direction, behavior: 'smooth' })
  }
  return (
    <section className="pp pp--carousel">
      <Heading onArrow={scroll} withArrows />
      <ul className="pp-carousel" ref={track}>
        {PORTFOLIO.map((project, index) => (
          <li className="pp-carousel__card" key={project.slug}>
            <Silhouette number={number(index)} project={project} size="lg" />
            <h3>{project.name}</h3>
            <p>{project.tagline}</p>
          </li>
        ))}
      </ul>
    </section>
  )
}

function Bento() {
  return (
    <section className="pp pp--bento">
      <Heading />
      <div className="pp-bento">
        {PORTFOLIO.map((project, index) => (
          <article className={`pp-bento__cell pp-bento__cell--${index}`} key={project.slug}>
            <Silhouette project={project} size={index === 0 ? 'lg' : 'md'} />
            <div className="pp-bento__text">
              <h3>{project.name}</h3>
              <p>{project.tagline}</p>
            </div>
          </article>
        ))}
      </div>
    </section>
  )
}

function Editorial() {
  return (
    <section className="pp pp--editorial">
      <Heading />
      <ol className="pp-editorial">
        {PORTFOLIO.map((project, index) => (
          <li className="pp-editorial__row" key={project.slug}>
            <span className="pp-editorial__num">{number(index)}</span>
            <div className="pp-editorial__text">
              <h3>{project.name}</h3>
              <p>{project.tagline}</p>
            </div>
            <span className="proto-badge">Coming soon</span>
            <Silhouette hideBadge project={project} size="sm" />
          </li>
        ))}
      </ol>
    </section>
  )
}

export function PortfolioSection({ variant }: { variant: VariantKey }) {
  if (variant === 'A') return <Carousel />
  if (variant === 'B') return <Bento />
  if (variant === 'C') return <Editorial />
  return null
}
