import type { GroupedSearchResponse } from '../../services/searchApi'
import { SearchResultGroup } from './SearchResultGroup'

interface SearchDropdownProps {
  results: GroupedSearchResponse | null
  loading: boolean
  hasResults: boolean
  onResultClick: () => void
}

export function SearchDropdown({ results, loading, hasResults, onResultClick }: SearchDropdownProps) {
  if (loading) {
    return (
      <div className="search-dropdown" role="status">
        <div className="search-dropdown__loading">Searching...</div>
      </div>
    )
  }

  if (!hasResults) {
    return (
      <div className="search-dropdown">
        <div className="search-dropdown__no-results">No results found</div>
      </div>
    )
  }

  return (
    <div className="search-dropdown">
      {results?.blogs && results.blogs.length > 0 && (
        <SearchResultGroup
          onResultClick={onResultClick}
          results={results.blogs}
          title="Blogs"
        />
      )}
      {results?.jobs && results.jobs.length > 0 && (
        <SearchResultGroup
          onResultClick={onResultClick}
          results={results.jobs}
          title="Jobs"
        />
      )}
      {results?.skills && results.skills.length > 0 && (
        <SearchResultGroup
          onResultClick={onResultClick}
          results={results.skills}
          title="Skills"
        />
      )}
    </div>
  )
}
