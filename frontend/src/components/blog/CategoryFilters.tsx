import { Search } from 'lucide-react'

interface CategoryFiltersProps {
  tags: string[]
  activeTag: string | null
  onTagSelect: (tag: string | null) => void
  searchQuery: string
  onSearchChange: (q: string) => void
}

export function CategoryFilters({
  tags,
  activeTag,
  onTagSelect,
  searchQuery,
  onSearchChange,
}: CategoryFiltersProps) {
  return (
    <div className="category-filters">
      <div className="category-filters__chips">
        <button
          type="button"
          className={`chip${activeTag === null ? ' chip--active' : ''}`}
          onClick={() => onTagSelect(null)}
        >
          All
        </button>
        {tags.map((tag) => (
          <button
            key={tag}
            type="button"
            className={`chip${activeTag === tag ? ' chip--active' : ''}`}
            onClick={() => onTagSelect(tag)}
          >
            {tag}
          </button>
        ))}
      </div>
      <div className="category-filters__search">
        <Search className="category-filters__search-icon" size={16} />
        <input
          type="search"
          className="category-filters__search-input"
          placeholder="Search articles..."
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
        />
      </div>
    </div>
  )
}
