import type { BlogContentType } from '../../types/blog'

/** `'ALL'` is a view, not a stored value, so it lives here rather than in the API types. */
export type BlogContentFilter = 'ALL' | BlogContentType

interface BlogContentTabsProps {
  active: BlogContentFilter
  onChange: (filter: BlogContentFilter) => void
}

const TABS: { value: BlogContentFilter; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'ENGINEERING', label: 'Engineering' },
  { value: 'DIGEST', label: 'Weekly Digest' },
]

/**
 * Splits the blog listing into hand-written engineering posts and generated
 * weekly digests, so the digests no longer bury the writing.
 *
 * Purpose-built rather than reusing `CategoryFilters`, which is a tag chip row plus
 * a search input that this page does not have.
 */
export function BlogContentTabs({ active, onChange }: BlogContentTabsProps) {
  return (
    <div aria-label="Filter posts by type" className="blog-content-tabs tour-blog-filters" role="tablist">
      {TABS.map((tab) => (
        <button
          aria-selected={active === tab.value}
          className={`chip blog-content-tabs__tab${active === tab.value ? ' chip--active' : ''}`}
          key={tab.value}
          onClick={() => onChange(tab.value)}
          role="tab"
          type="button"
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}
