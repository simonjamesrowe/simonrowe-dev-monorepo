import { useMemo, useState } from 'react'

import type { Release } from '../../types/platform'

import { ReleaseEntry } from './ReleaseEntry'

interface ReleaseListProps {
  releases: Release[]
}

const PAGE_SIZE = 8

/**
 * Renders the release history as a timeline with client-side type filtering and paging.
 *
 * Filtering and paging are both client-side: the full list is already fetched by
 * `useReleases`, so there is nothing to gain from asking the backend to do this again.
 * Changing the filter resets the visible count back to `PAGE_SIZE`, otherwise switching to
 * a smaller type could leave every matching entry hidden behind a stale "show more" count.
 */
export function ReleaseList({ releases }: ReleaseListProps) {
  const [activeType, setActiveType] = useState<string | null>(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  // Counted from the data rather than a hardcoded list, so a new conventional-commit type
  // shows up as its own pill the moment a release uses it.
  const typeCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const release of releases) {
      counts.set(release.type, (counts.get(release.type) ?? 0) + 1)
    }
    return counts
  }, [releases])

  const types = useMemo(() => Array.from(typeCounts.keys()).sort(), [typeCounts])

  const filtered = useMemo(
    () => (activeType ? releases.filter((release) => release.type === activeType) : releases),
    [releases, activeType],
  )

  function selectType(type: string | null) {
    setActiveType(type)
    setVisibleCount(PAGE_SIZE)
  }

  if (releases.length === 0) {
    return <p className="status-page__empty">No release history yet.</p>
  }

  const visible = filtered.slice(0, visibleCount)
  const hasMore = visibleCount < filtered.length
  const canShowLess = visibleCount > PAGE_SIZE

  return (
    <div className="release-timeline">
      {/* .feed__filters-scroll is the class that turns this row into a horizontal
          scroller below 768px (see styles.css); without it, .feed__filters' mobile rule
          (`width: max-content`) overflows the page instead of scrolling internally. */}
      <div className="feed__filters-scroll">
        <div aria-label="Filter releases by type" className="feed__filters" role="group">
          <button
            className={`feed__pill${activeType === null ? ' feed__pill--active' : ''}`}
            onClick={() => selectType(null)}
            type="button"
          >
            All <span className="feed__more-count">{releases.length}</span>
          </button>
          {types.map((type) => (
            <button
              className={`feed__pill${activeType === type ? ' feed__pill--active' : ''}`}
              key={type}
              onClick={() => selectType(type)}
              type="button"
            >
              {type} <span className="feed__more-count">{typeCounts.get(type)}</span>
            </button>
          ))}
        </div>
      </div>

      {filtered.length === 0 ? (
        <p className="status-page__empty">No releases of this type.</p>
      ) : (
        <ol className="release-list">
          {visible.map((release) => (
            <ReleaseEntry key={release.sha} release={release} />
          ))}
        </ol>
      )}

      {hasMore || canShowLess ? (
        <div className="feed__load-more">
          {hasMore ? (
            <button
              className="button button--secondary"
              onClick={() => setVisibleCount((count) => count + PAGE_SIZE)}
              type="button"
            >
              Show more ({visible.length} of {filtered.length})
            </button>
          ) : null}
          {canShowLess ? (
            <button
              className="button button--secondary"
              onClick={() => setVisibleCount(PAGE_SIZE)}
              type="button"
            >
              Show less
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
