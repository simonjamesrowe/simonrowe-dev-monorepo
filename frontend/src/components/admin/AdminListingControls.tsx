import { ArrowDown, ArrowUp } from 'lucide-react'

import type {
  AdminSourceSummary,
  AdminVisibilityFilter,
  SortDirection,
} from '../../services/adminApi'
import { PAGE_SIZES } from '../../pages/admin/useAdminListing'

interface ListingToolbarProps {
  label: string
  search: string
  onSearchChange: (value: string) => void
  visibility: AdminVisibilityFilter
  onVisibilityChange: (value: AdminVisibilityFilter) => void
  size: number
  onSizeChange: (value: number) => void
  total: number | null
  noun: { one: string; many: string }
  /** Omit to leave the source filter out. */
  sources?: AdminSourceSummary[]
  source?: string
  onSourceChange?: (value: string) => void
}

/** Search, filters and page size for one admin table. Every control filters on the server. */
export function ListingToolbar({
  label,
  search,
  onSearchChange,
  visibility,
  onVisibilityChange,
  size,
  onSizeChange,
  total,
  noun,
  sources,
  source,
  onSourceChange,
}: ListingToolbarProps) {
  return (
    <div className="admin-listing__toolbar">
      <input
        aria-label={`Search ${label}`}
        className="admin-form__input admin-listing__search"
        onChange={(e) => onSearchChange(e.target.value)}
        placeholder={`Search ${label}…`}
        type="search"
        value={search}
      />
      {sources && onSourceChange && (
        <select
          aria-label="Source"
          className="admin-listing__select"
          onChange={(e) => onSourceChange(e.target.value)}
          value={source ?? ''}
        >
          <option value="">All sources</option>
          {sources.map((s) => (
            <option key={s.name} value={s.name}>
              {s.name} ({s.count})
            </option>
          ))}
        </select>
      )}
      <select
        aria-label="Visibility"
        className="admin-listing__select"
        onChange={(e) => onVisibilityChange(e.target.value as AdminVisibilityFilter)}
        value={visibility}
      >
        <option value="all">All</option>
        <option value="visible">Visible</option>
        <option value="hidden">Hidden</option>
      </select>
      <select
        aria-label="Page size"
        className="admin-listing__select"
        onChange={(e) => onSizeChange(Number(e.target.value))}
        value={size}
      >
        {PAGE_SIZES.map((n) => (
          <option key={n} value={n}>
            {n} per page
          </option>
        ))}
      </select>
      {total !== null && (
        <span className="admin-listing__count" aria-live="polite">
          {total} {total === 1 ? noun.one : noun.many}
        </span>
      )}
    </div>
  )
}

interface SortHeaderProps<S extends string> {
  column: S
  label: string
  sort: S
  direction: SortDirection
  onSort: (column: S) => void
}

/** A column header that sorts the table on the server; clicking the active one flips it. */
export function SortHeader<S extends string>({
  column,
  label,
  sort,
  direction,
  onSort,
}: SortHeaderProps<S>) {
  const active = sort === column
  const ariaSort = active ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'
  return (
    <th aria-sort={ariaSort} className="admin-table__th">
      <button className="admin-table__sort" onClick={() => onSort(column)} type="button">
        <span>{label}</span>
        {active &&
          (direction === 'desc' ? (
            <ArrowDown aria-hidden="true" size={14} />
          ) : (
            <ArrowUp aria-hidden="true" size={14} />
          ))}
      </button>
    </th>
  )
}

interface BulkBarProps {
  selectedCount: number
  busy: boolean
  onHide: () => void
  onShow: () => void
  onDelete: () => void
  onClear: () => void
}

/** Actions on the ticked rows. Only rendered while something is ticked. */
export function BulkBar({ selectedCount, busy, onHide, onShow, onDelete, onClear }: BulkBarProps) {
  return (
    <div className="admin-listing__bulk" role="region" aria-label="Bulk actions">
      <span className="admin-listing__selected-count">{selectedCount} selected</span>
      <button className="admin-btn admin-btn--sm" disabled={busy} onClick={onHide} type="button">
        Hide selected
      </button>
      <button className="admin-btn admin-btn--sm" disabled={busy} onClick={onShow} type="button">
        Show selected
      </button>
      <button
        className="admin-btn admin-btn--sm admin-btn--danger"
        disabled={busy}
        onClick={onDelete}
        type="button"
      >
        Delete selected
      </button>
      <button
        className="admin-btn admin-btn--sm"
        disabled={busy}
        onClick={onClear}
        type="button"
      >
        Clear
      </button>
    </div>
  )
}

/**
 * The item's title, linking to the original only when that is a real http(s) address. The
 * URL is scraped from a third party, so it is parsed rather than trusted: a `javascript:`
 * href would run in the admin's session.
 */
export function TitleLink({ title, url, maxLength = 60 }: { title: string; url: string | null; maxLength?: number }) {
  const text = title.length <= maxLength ? title : `${title.slice(0, maxLength)}…`
  if (!url) return <>{text}</>
  let href: string | null = null
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'https:' || parsed.protocol === 'http:') href = parsed.href
  } catch {
    href = null
  }
  if (!href) return <>{text}</>
  return (
    <a className="admin-listing__title-link" href={href} rel="noopener noreferrer" target="_blank">
      {text}
    </a>
  )
}
