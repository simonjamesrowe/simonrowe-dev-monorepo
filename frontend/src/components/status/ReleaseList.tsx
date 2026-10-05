import { Search, X } from 'lucide-react'

import type { Release } from '../../types/platform'

import { ReleaseEntry } from './ReleaseEntry'

interface ReleaseListProps {
  releases: Release[]
  /** Releases matching the current type and search, across every page. */
  totalItems: number
  /** Every stored release, whatever the filters: the "All" pill's count. */
  totalReleases: number
  typeCounts: Record<string, number>
  activeType: string | null
  onTypeChange: (type: string | null) => void
  query: string
  onQueryChange: (query: string) => void
  hasMore: boolean
  loading: boolean
  loadingMore: boolean
  onLoadMore: () => void
}

/**
 * The changelog as a timeline: a search box, type pills and "Load more".
 *
 * Purely presentational. Filtering and paging happen on the server (`useReleases`), because the
 * page now reaches the whole history rather than the newest 20, and a search has to search all
 * of it rather than whatever happens to be loaded.
 */
export function ReleaseList({
  releases,
  totalItems,
  totalReleases,
  typeCounts,
  activeType,
  onTypeChange,
  query,
  onQueryChange,
  hasMore,
  loading,
  loadingMore,
  onLoadMore,
}: ReleaseListProps) {
  if (!loading && totalReleases === 0) {
    return <p className="status-page__empty">No release history yet.</p>
  }

  // Ordered by the server, most common first. Counted from the data rather than a hardcoded
  // list, so a new conventional-commit type shows up as its own pill the moment one is used.
  const types = Object.keys(typeCounts)
  const filtering = activeType !== null || query.trim() !== ''

  return (
    <div className="release-timeline">
      <div className="feed__search release-timeline__search">
        <Search aria-hidden="true" className="feed__search-icon" size={16} />
        <input
          aria-label="Search releases"
          className="feed__search-input"
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder="Search releases by subject, note or SHA"
          type="search"
          value={query}
        />
        {query ? (
          <button
            aria-label="Clear search"
            className="feed__search-clear"
            onClick={() => onQueryChange('')}
            type="button"
          >
            <X aria-hidden="true" size={14} />
          </button>
        ) : null}
      </div>

      {/* .feed__filters-scroll is the class that turns this row into a horizontal
          scroller below 768px (see styles.css); without it, .feed__filters' mobile rule
          (`width: max-content`) overflows the page instead of scrolling internally. */}
      <div className="feed__filters-scroll">
        <div aria-label="Filter releases by type" className="feed__filters" role="group">
          <button
            aria-pressed={activeType === null}
            className={`feed__pill${activeType === null ? ' feed__pill--active' : ''}`}
            onClick={() => onTypeChange(null)}
            type="button"
          >
            All <span className="feed__more-count">{totalReleases}</span>
          </button>
          {types.map((type) => (
            <button
              aria-pressed={activeType === type}
              className={`feed__pill${activeType === type ? ' feed__pill--active' : ''}`}
              key={type}
              onClick={() => onTypeChange(type)}
              type="button"
            >
              {type} <span className="feed__more-count">{typeCounts[type]}</span>
            </button>
          ))}
        </div>
      </div>

      {!loading && releases.length === 0 ? (
        <p className="status-page__empty">
          {filtering ? 'No releases match.' : 'No release history yet.'}
        </p>
      ) : (
        <ol className="release-list">
          {releases.map((release) => (
            <ReleaseEntry key={release.sha} release={release} />
          ))}
        </ol>
      )}

      {hasMore ? (
        <div className="feed__load-more">
          <button
            className="button button--secondary"
            disabled={loadingMore || loading}
            onClick={onLoadMore}
            type="button"
          >
            {loadingMore ? 'Loading…' : `Load more (${releases.length} of ${totalItems})`}
          </button>
        </div>
      ) : null}
    </div>
  )
}
