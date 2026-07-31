import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { ArticleCard } from '../blog/ArticleCard'
import type { BlogSummary } from '../../types/blog'

interface FeaturedWritingProps {
  blogs?: BlogSummary[] | null
}

const MAX_POSTS = 10

/**
 * The most recent engineering posts as a horizontally scrollable carousel.
 *
 * The caller decides what "engineering" means (the home page asks the API for
 * `contentType=ENGINEERING`); this component only ever renders what it is given, at
 * most `MAX_POSTS` of them, and nothing at all when the list is empty (FR-007).
 *
 * Deliberately **not** auto-rotating, unlike the employer logo strip. These are cards
 * with titles and summaries to read — moving them under the reader fails WCAG 2.2.2 and
 * makes the section actively harder to use. Motion belongs on decorative logos, not on
 * text. Scrolling is native (`scroll-snap` + `overflow-x`), so trackpad, touch swipe,
 * shift-scroll and keyboard all work for free; the arrows are an affordance on top.
 */
export function FeaturedWriting({ blogs }: FeaturedWritingProps) {
  const posts = (blogs ?? []).slice(0, MAX_POSTS)
  const trackRef = useRef<HTMLUListElement>(null)
  const [atStart, setAtStart] = useState(true)
  const [atEnd, setAtEnd] = useState(false)

  const syncArrows = useCallback(() => {
    const track = trackRef.current
    if (!track) {
      return
    }
    // A pixel of slack: fractional scroll widths mean the exact equality never holds.
    setAtStart(track.scrollLeft <= 1)
    setAtEnd(track.scrollLeft + track.clientWidth >= track.scrollWidth - 1)
  }, [])

  useEffect(() => {
    syncArrows()
    window.addEventListener('resize', syncArrows)
    return () => window.removeEventListener('resize', syncArrows)
  }, [syncArrows, posts.length])

  const scrollByCard = (direction: 1 | -1) => {
    const track = trackRef.current
    if (!track) {
      return
    }
    const firstCard = track.querySelector('li')
    // Fall back to most of the viewport when the card width cannot be measured.
    const step = firstCard ? firstCard.getBoundingClientRect().width + 24 : track.clientWidth * 0.8
    track.scrollBy({ left: step * direction, behavior: 'smooth' })
  }

  if (posts.length === 0) {
    return null
  }

  return (
    <section className="featured-writing" aria-labelledby="featured-writing-heading">
      <div className="featured-writing__header">
        <h2 className="featured-writing__heading" id="featured-writing-heading">
          Recent writing
        </h2>
        <div className="featured-writing__header-actions">
          <div className="featured-writing__controls">
            <button
              aria-label="Scroll to previous posts"
              className="featured-writing__arrow"
              disabled={atStart}
              onClick={() => scrollByCard(-1)}
              type="button"
            >
              <ChevronLeft size={18} />
            </button>
            <button
              aria-label="Scroll to more posts"
              className="featured-writing__arrow"
              disabled={atEnd}
              onClick={() => scrollByCard(1)}
              type="button"
            >
              <ChevronRight size={18} />
            </button>
          </div>
          <Link className="featured-writing__link" to="/blogs">
            Read the blog &rarr;
          </Link>
        </div>
      </div>
      <ul
        aria-label="Recent engineering posts"
        className="featured-writing__track"
        onScroll={syncArrows}
        ref={trackRef}
      >
        {posts.map((blog) => (
          <li className="featured-writing__slide" key={blog.id}>
            <ArticleCard blog={blog} />
          </li>
        ))}
      </ul>
    </section>
  )
}
