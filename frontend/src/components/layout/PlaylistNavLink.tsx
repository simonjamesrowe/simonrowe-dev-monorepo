import { Headphones } from 'lucide-react'
import { NavLink } from 'react-router-dom'

import { upNextOf, usePlaylist } from '../../hooks/playlistStore'

/** How many items have audio waiting to be heard, or 0 before the playlist has loaded. */
function useUpNextCount(): number {
  const { data } = usePlaylist()
  return data ? upNextOf(data.items).length : 0
}

function badgeLabel(count: number): string {
  return count === 1 ? '1 item up next' : `${count} items up next`
}

/** The header's Playlist link, beside the four menus, with how much is waiting. */
export function PlaylistNavItem() {
  const count = useUpNextCount()
  return (
    <li className="header-menu">
      <NavLink
        className={({ isActive }) => `header-menu__trigger header-menu__link${isActive ? ' header-menu__trigger--active' : ''}`}
        to="/playlist"
      >
        <Headphones aria-hidden="true" size={15} />
        Playlist
        {count > 0 && (
          <span className="playlist-badge">
            <span aria-hidden="true">{count}</span>
            <span className="playlist-badge__label">, {badgeLabel(count)}</span>
          </span>
        )}
      </NavLink>
    </li>
  )
}

/** The same link as an icon in the phone header's capsule. */
export function PlaylistIconLink() {
  const count = useUpNextCount()
  return (
    <NavLink
      aria-label={count > 0 ? `Playlist, ${badgeLabel(count)}` : 'Playlist'}
      className={({ isActive }) => `site-header__icon-button site-header__mobile-only playlist-icon-link${isActive ? ' playlist-icon-link--active' : ''}`}
      to="/playlist"
    >
      <Headphones aria-hidden="true" size={20} />
      {count > 0 && <span aria-hidden="true" className="playlist-badge playlist-badge--corner">{count}</span>}
    </NavLink>
  )
}
