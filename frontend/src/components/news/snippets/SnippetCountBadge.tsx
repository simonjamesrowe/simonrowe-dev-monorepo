import { Highlighter } from 'lucide-react'

/** How many passages the admin kept from an article. Only ever rendered for the admin. */
export function SnippetCountBadge({ count }: { count: number }) {
  if (count <= 0) return null
  const label = `${count} snippet${count === 1 ? '' : 's'} kept`
  return (
    <span aria-label={label} className="snippet-count" title={`${label}. Only you see this.`}>
      <Highlighter aria-hidden="true" size={12} /> {count}
    </span>
  )
}
