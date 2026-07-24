import { Heart } from 'lucide-react'

interface FavouriteButtonProps {
  active: boolean
  onClick: () => void
  label?: string
  className?: string
}

export function FavouriteButton({
  active,
  onClick,
  label = 'this item',
  className,
}: FavouriteButtonProps) {
  return (
    <button
      aria-label={active ? `Remove ${label} from favourites` : `Add ${label} to favourites`}
      aria-pressed={active}
      className={`favourite-button${active ? ' favourite-button--active' : ''}${className ? ` ${className}` : ''}`}
      onClick={(e) => {
        // Cards are anchor elements — stop the click from opening the link.
        e.preventDefault()
        e.stopPropagation()
        onClick()
      }}
      type="button"
    >
      <Heart aria-hidden="true" fill={active ? 'currentColor' : 'none'} size={18} />
    </button>
  )
}
